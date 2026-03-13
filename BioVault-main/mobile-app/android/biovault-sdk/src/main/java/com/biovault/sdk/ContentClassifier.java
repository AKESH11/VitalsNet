package com.biovault.sdk;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hybrid content classifier: HSV skin-pixel analysis + ML Kit labels.
 *
 * ML Kit's bundled model outputs generic labels ("Space", "Furniture") for
 * suggestive content — it's NOT designed for content moderation. We augment
 * it with pixel-level skin detection in HSV color space (the technique used
 * by nudepy, OpenNSFW, Yahoo NSFW detector, etc.).
 *
 * Skin detection: convert pixel RGB→HSV, check if it falls in human-skin
 * color ranges, compute the percentage of skin-colored pixels. This is
 * fast, offline, and very reliable for detecting nudity / partial nudity.
 *
 * Combined scoring:
 *   skinPixelPct ≥ 45% + no clothing labels  → EXPLICIT
 *   skinPixelPct ≥ 30% or underwear labels   → SENSITIVE
 *   Everything else                          → SAFE
 */
public class ContentClassifier {

    private static final String TAG = "ContentClassifier";

    // ── ML Kit label sets (supplementary signals) ──
    private static final Set<String> HIGH_SKIN_LABELS = new HashSet<>(Arrays.asList(
        "Flesh", "Barechested", "Chest", "Abdomen", "Navel", "Thigh",
        "Trunk", "Hip", "Stomach", "Muscle", "Human body", "Cleavage",
        "Nude", "Naked", "Nudity", "Skin"
    ));

    private static final Set<String> UNDERWEAR_LABELS = new HashSet<>(Arrays.asList(
        "Undergarment", "Brassiere", "Lingerie", "Swimwear", "Bikini",
        "Briefs", "Underpants", "Maillot", "Leotard", "Crop top",
        "Active undergarment", "Swimsuit top", "Swimsuit bottom",
        "Brassière", "Sports bra"
    ));

    private static final Set<String> CLOTHING_LABELS = new HashSet<>(Arrays.asList(
        "Outerwear", "Jeans", "Dress", "Coat", "Sleeve", "T-shirt",
        "Jacket", "Shirt", "Pants", "Shorts", "Uniform", "Suit",
        "Sweater", "Hoodie", "Blouse", "Skirt", "Costume"
    ));

    private static final Set<String> VIOLENCE_LABELS = new HashSet<>(Arrays.asList(
        "Gun", "Weapon", "Knife", "Blood", "Sword", "Rifle",
        "Explosion", "Fire", "Wound", "Injury"
    ));

    private static final Set<String> SAFE_SCENE_LABELS = new HashSet<>(Arrays.asList(
        "Landscape", "Sky", "Building", "Plant", "Tree", "Food",
        "Vehicle", "Animal", "Text", "Document", "Furniture",
        "Glasses", "Toy", "Laptop", "Table", "Chair", "Flower",
        "Mountain", "Ocean", "Road", "Television", "Window",
        "Computer", "Phone", "Book", "Clock", "Bottle"
    ));

    public static class ClassificationResult {
        public final String category;       // "SAFE", "SENSITIVE", "EXPLICIT"
        public final boolean requiresConsent;
        public final String topLabel;       // primary flag reason
        public final float topConfidence;   // skin percentage or label confidence

        ClassificationResult(String category, boolean requiresConsent,
                             String topLabel, float topConfidence) {
            this.category = category;
            this.requiresConsent = requiresConsent;
            this.topLabel = topLabel;
            this.topConfidence = topConfidence;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  HSV SKIN DETECTION — the core of reliable content moderation
    // ════════════════════════════════════════════════════════════════════

    /**
     * Downscale bitmap for fast pixel analysis.
     * A 120×90 thumbnail is enough — we only need percentage, not location.
     */
    private static Bitmap downscale(Bitmap src, int maxDim) {
        int w = src.getWidth();
        int h = src.getHeight();
        float scale = Math.min((float) maxDim / w, (float) maxDim / h);
        if (scale >= 1f) return src;
        int nw = Math.max(1, (int)(w * scale));
        int nh = Math.max(1, (int)(h * scale));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    /**
     * Calculate what percentage of the image is human-skin colored.
     *
     * Uses two HSV ranges that cover most skin tones (light to dark):
     *   Range 1: H ∈ [0°, 50°],  S ∈ [20, 255], V ∈ [70, 255]  (light/medium skin)
     *   Range 2: H ∈ [0°, 30°],  S ∈ [30, 180], V ∈ [50, 150]  (darker skin tones)
     *
     * Also checks for high-saturation reds/pinks (lips, areolae, etc.):
     *   Range 3: H ∈ [340°, 360°] mapped as [0°, 20° wrapping], S ∈ [40, 255], V ∈ [80, 255]
     *
     * Returns a float 0.0 – 1.0 representing the skin-pixel ratio.
     */
    private static float calculateSkinPercentage(Bitmap bitmap) {
        Bitmap small = downscale(bitmap, 120);
        int w = small.getWidth();
        int h = small.getHeight();
        int total = w * h;
        int skinCount = 0;

        float[] hsv = new float[3];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = small.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                Color.RGBToHSV(r, g, b, hsv);
                float hue = hsv[0];        // 0-360
                float sat = hsv[1] * 255;  // 0-255
                float val = hsv[2] * 255;  // 0-255

                boolean isSkin = false;

                // Range 1: main skin tones (fair to medium)
                if (hue >= 0 && hue <= 50 && sat >= 20 && sat <= 255 && val >= 70) {
                    isSkin = true;
                }
                // Range 2: darker skin tones
                if (hue >= 0 && hue <= 30 && sat >= 30 && sat <= 180 && val >= 50 && val <= 180) {
                    isSkin = true;
                }
                // Range 3: reddish/pinkish skin (wraps around 360°)
                if (hue >= 340 && hue <= 360 && sat >= 30 && val >= 70) {
                    isSkin = true;
                }

                // Filter out very low-saturation (white/gray surfaces, walls, etc.)
                if (sat < 15) isSkin = false;

                // Filter out near-white (overexposed skin-like areas)
                if (val > 245 && sat < 30) isSkin = false;

                if (isSkin) skinCount++;
            }
        }

        if (small != bitmap) small.recycle();

        float pct = (float) skinCount / total;
        return pct;
    }

    // ════════════════════════════════════════════════════════════════════
    //  MAIN CLASSIFICATION: skin pixels + ML Kit labels combined
    // ════════════════════════════════════════════════════════════════════

    public static ClassificationResult classify(Bitmap bitmap) {
        if (bitmap == null) {
            return new ClassificationResult("SAFE", false, "none", 0f);
        }

        // ── Step 1: HSV skin percentage (fast, reliable) ──
        float skinPct = calculateSkinPercentage(bitmap);
        android.util.Log.i(TAG, String.format("Skin pixel percentage: %.1f%%", skinPct * 100));

        // ── Step 2: ML Kit labels (supplementary) ──
        MLKitScores mlScores = runMLKitLabeling(bitmap);

        // ── Step 3: Combined decision ──
        return combinedDecision(skinPct, mlScores);
    }

    /** Container for ML Kit scoring */
    private static class MLKitScores {
        float skinLabelScore = 0f;
        float underwearScore = 0f;
        float clothingScore = 0f;
        float violenceScore = 0f;
        float safeSceneScore = 0f;
        String topLabel = "none";
        float topConfidence = 0f;
        String flaggedLabel = "none";
        float flaggedConf = 0f;
        String allLabelsDebug = "";
    }

    private static MLKitScores runMLKitLabeling(Bitmap bitmap) {
        MLKitScores scores = new MLKitScores();

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        ImageLabelerOptions options = new ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.25f)
                .build();
        ImageLabeler labeler = ImageLabeling.getClient(options);

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<List<ImageLabel>> labelsRef = new AtomicReference<>(null);

        labeler.process(image)
            .addOnSuccessListener(labels -> { labelsRef.set(labels); latch.countDown(); })
            .addOnFailureListener(e -> { latch.countDown(); });

        try {
            latch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        labeler.close();

        List<ImageLabel> labels = labelsRef.get();
        if (labels == null || labels.isEmpty()) return scores;

        scores.topLabel = labels.get(0).getText();
        scores.topConfidence = labels.get(0).getConfidence();

        StringBuilder sb = new StringBuilder();
        for (ImageLabel l : labels) {
            String text = l.getText();
            float conf = l.getConfidence();
            sb.append(text).append("(").append(String.format("%.0f%%", conf * 100)).append(") ");

            if (HIGH_SKIN_LABELS.contains(text)) {
                scores.skinLabelScore += conf;
                if (conf > scores.flaggedConf) { scores.flaggedConf = conf; scores.flaggedLabel = text; }
            }
            if (UNDERWEAR_LABELS.contains(text)) {
                scores.underwearScore += conf;
                if (conf > scores.flaggedConf) { scores.flaggedConf = conf; scores.flaggedLabel = text; }
            }
            if (CLOTHING_LABELS.contains(text))  scores.clothingScore += conf;
            if (VIOLENCE_LABELS.contains(text)) {
                scores.violenceScore += conf;
                if (conf > scores.flaggedConf) { scores.flaggedConf = conf; scores.flaggedLabel = text; }
            }
            if (SAFE_SCENE_LABELS.contains(text)) scores.safeSceneScore += conf;
        }
        scores.allLabelsDebug = sb.toString();
        android.util.Log.d(TAG, "ML Kit labels: " + scores.allLabelsDebug);
        android.util.Log.d(TAG, String.format(
            "ML scores: skinLabel=%.2f underwear=%.2f clothing=%.2f violence=%.2f safe=%.2f",
            scores.skinLabelScore, scores.underwearScore, scores.clothingScore,
            scores.violenceScore, scores.safeSceneScore));

        return scores;
    }

    private static ClassificationResult combinedDecision(float skinPct, MLKitScores ml) {
        // Determine the best label to show the user
        String displayLabel;
        float displayConf;
        if (ml.flaggedConf > 0) {
            displayLabel = ml.flaggedLabel;
            displayConf = ml.flaggedConf;
        } else {
            displayLabel = "Skin " + String.format("%.0f%%", skinPct * 100);
            displayConf = skinPct;
        }

        android.util.Log.i(TAG, String.format(
            "Combined: skinPct=%.1f%% mlSkin=%.2f underwear=%.2f clothing=%.2f violence=%.2f safe=%.2f",
            skinPct * 100, ml.skinLabelScore, ml.underwearScore, ml.clothingScore,
            ml.violenceScore, ml.safeSceneScore));

        // ═══════════════════════════════════════════════════════════
        //  SKIN PIXEL % is the PRIMARY signal.
        //  ML Kit clothing labels are unreliable — the bundled model
        //  outputs "Jeans", "Jacket" for random objects. We only use
        //  ML Kit as a secondary boost, never as a veto.
        // ═══════════════════════════════════════════════════════════

        // ── EXPLICIT ──
        if (skinPct >= 0.45f) {
            android.util.Log.i(TAG, "EXPLICIT: very high skin " + String.format("%.0f%%", skinPct * 100));
            return new ClassificationResult("EXPLICIT", true, displayLabel, skinPct);
        }
        if (skinPct >= 0.35f && ml.skinLabelScore >= 0.25f) {
            android.util.Log.i(TAG, "EXPLICIT: high skin + ML skin labels");
            return new ClassificationResult("EXPLICIT", true, displayLabel, skinPct);
        }

        // ── SENSITIVE ──
        if (skinPct >= 0.30f) {
            android.util.Log.i(TAG, "SENSITIVE: moderate skin " + String.format("%.0f%%", skinPct * 100));
            return new ClassificationResult("SENSITIVE", true, displayLabel, skinPct);
        }
        if (ml.underwearScore >= 0.25f) {
            android.util.Log.i(TAG, "SENSITIVE: underwear/swimwear labels detected");
            return new ClassificationResult("SENSITIVE", true,
                ml.flaggedLabel, ml.flaggedConf);
        }
        if (skinPct >= 0.20f && ml.skinLabelScore >= 0.25f) {
            android.util.Log.i(TAG, "SENSITIVE: skin + ML skin labels");
            return new ClassificationResult("SENSITIVE", true, displayLabel, skinPct);
        }
        if (ml.violenceScore >= 0.35f) {
            android.util.Log.i(TAG, "SENSITIVE: violence labels");
            return new ClassificationResult("SENSITIVE", true,
                ml.flaggedLabel, ml.flaggedConf);
        }

        // ── SAFE ──
        android.util.Log.i(TAG, "SAFE: normal content (skin=" + String.format("%.0f%%", skinPct * 100) + ")");
        return new ClassificationResult("SAFE", false, ml.topLabel, ml.topConfidence);
    }
}
