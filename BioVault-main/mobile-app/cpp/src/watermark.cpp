#include "watermark.h"

#ifdef HAVE_OPENCV
#include <opencv2/opencv.hpp>
#include <cmath>
#include <algorithm>
#include <numeric>
#endif

#ifdef ANDROID
#include <android/log.h>
#define WM_TAG "BioVaultWM"
#define WM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  WM_TAG, __VA_ARGS__)
#define WM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, WM_TAG, __VA_ARGS__)
#else
#define WM_LOGI(...) printf(__VA_ARGS__)
#define WM_LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

namespace biovault {

// ============================================================================
// Bit encoding / decoding  (16-bit length + payload bytes + 8-bit checksum)
// ============================================================================

std::vector<uint8_t> Watermark::encodeBits(const std::string& payload) {
    std::vector<uint8_t> bits;
    uint16_t len = static_cast<uint16_t>(payload.size());

    // 16-bit length header (big-endian)
    for (int i = 15; i >= 0; --i)
        bits.push_back((len >> i) & 1);

    // Payload bytes → bits
    uint8_t checksum = 0;
    for (char c : payload) {
        uint8_t b = static_cast<uint8_t>(c);
        checksum ^= b;
        for (int i = 7; i >= 0; --i)
            bits.push_back((b >> i) & 1);
    }

    // 8-bit XOR checksum
    for (int i = 7; i >= 0; --i)
        bits.push_back((checksum >> i) & 1);

    return bits;
}

std::string Watermark::decodeBits(const std::vector<uint8_t>& bits) {
    if (bits.size() < 24) return "";           // need at least header + checksum

    // Read 16-bit length
    uint16_t len = 0;
    for (int i = 0; i < 16; ++i)
        len = (len << 1) | (bits[i] & 1);

    size_t totalBits = 16 + static_cast<size_t>(len) * 8 + 8;
    if (totalBits > bits.size() || len == 0 || len > 256) return "";

    // Read payload bytes
    std::string result;
    result.reserve(len);
    uint8_t checksum = 0;
    for (uint16_t bi = 0; bi < len; ++bi) {
        uint8_t byte = 0;
        size_t offset = 16 + bi * 8;
        for (int j = 0; j < 8; ++j)
            byte = (byte << 1) | (bits[offset + j] & 1);
        checksum ^= byte;
        result.push_back(static_cast<char>(byte));
    }

    // Verify checksum
    uint8_t storedChecksum = 0;
    size_t csOffset = 16 + static_cast<size_t>(len) * 8;
    for (int j = 0; j < 8; ++j)
        storedChecksum = (storedChecksum << 1) | (bits[csOffset + j] & 1);

    if (storedChecksum != checksum) {
        WM_LOGE("Watermark checksum mismatch: stored=%02x computed=%02x", storedChecksum, checksum);
        return "";
    }
    return result;
}

// ============================================================================
// Haar DWT / IDWT
// ============================================================================
#ifdef HAVE_OPENCV

void Watermark::haarDWT(const cv::Mat& src, cv::Mat& LL, cv::Mat& LH,
                        cv::Mat& HL, cv::Mat& HH) {
    // Make sure dimensions are even
    int rows = src.rows & ~1;
    int cols = src.cols & ~1;
    cv::Mat in = src(cv::Rect(0, 0, cols, rows));

    int halfR = rows / 2;
    int halfC = cols / 2;

    LL.create(halfR, halfC, CV_32F);
    LH.create(halfR, halfC, CV_32F);
    HL.create(halfR, halfC, CV_32F);
    HH.create(halfR, halfC, CV_32F);

    for (int r = 0; r < halfR; ++r) {
        const float* r0 = in.ptr<float>(r * 2);
        const float* r1 = in.ptr<float>(r * 2 + 1);
        float* pLL = LL.ptr<float>(r);
        float* pLH = LH.ptr<float>(r);
        float* pHL = HL.ptr<float>(r);
        float* pHH = HH.ptr<float>(r);
        for (int c = 0; c < halfC; ++c) {
            float a = r0[c * 2],     b = r0[c * 2 + 1];
            float cc_ = r1[c * 2],   d = r1[c * 2 + 1];
            pLL[c] = (a + b + cc_ + d) * 0.25f;
            pLH[c] = (a - b + cc_ - d) * 0.25f;
            pHL[c] = (a + b - cc_ - d) * 0.25f;
            pHH[c] = (a - b - cc_ + d) * 0.25f;
        }
    }
}

void Watermark::haarIDWT(const cv::Mat& LL, const cv::Mat& LH,
                         const cv::Mat& HL, const cv::Mat& HH,
                         cv::Mat& dst) {
    int halfR = LL.rows;
    int halfC = LL.cols;
    dst.create(halfR * 2, halfC * 2, CV_32F);

    for (int r = 0; r < halfR; ++r) {
        const float* pLL = LL.ptr<float>(r);
        const float* pLH = LH.ptr<float>(r);
        const float* pHL = HL.ptr<float>(r);
        const float* pHH = HH.ptr<float>(r);
        float* d0 = dst.ptr<float>(r * 2);
        float* d1 = dst.ptr<float>(r * 2 + 1);
        for (int c = 0; c < halfC; ++c) {
            float ll = pLL[c], lh = pLH[c], hl = pHL[c], hh = pHH[c];
            d0[c * 2]     = ll + lh + hl + hh;  // a
            d0[c * 2 + 1] = ll - lh + hl - hh;  // b
            d1[c * 2]     = ll + lh - hl - hh;  // c
            d1[c * 2 + 1] = ll - lh - hl + hh;  // d
        }
    }
}

// ============================================================================
// Embed — block-DCT + SVD quantisation watermark on Y channel
// ============================================================================

bool Watermark::embed(const uint8_t* rgbaData, int width, int height,
                      const std::string& payload, uint8_t* outRgba) {
    if (!rgbaData || !outRgba || width < 64 || height < 64 || payload.empty()) {
        WM_LOGE("embed: invalid args w=%d h=%d payload_len=%zu", width, height, payload.size());
        return false;
    }

    try {
        // 1. RGBA → BGR → YCrCb, extract Y channel as float32
        cv::Mat rgba(height, width, CV_8UC4, const_cast<uint8_t*>(rgbaData));
        cv::Mat bgr;
        cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
        cv::Mat ycrcb;
        cv::cvtColor(bgr, ycrcb, cv::COLOR_BGR2YCrCb);
        std::vector<cv::Mat> channels;
        cv::split(ycrcb, channels);
        cv::Mat Y;
        channels[0].convertTo(Y, CV_32F);

        // 2. Encode payload → bits
        auto bits = encodeBits(payload);
        const int BS = 8;
        int bRows = (Y.rows / BS) * BS;
        int bCols = (Y.cols / BS) * BS;
        int numBlocks = (bRows / BS) * (bCols / BS);
        if (static_cast<int>(bits.size()) > numBlocks) {
            WM_LOGE("embed: payload too large (%zu bits, %d blocks available)",
                     bits.size(), numBlocks);
            return false;
        }
        WM_LOGI("embed: %zu bits into %d blocks (img %dx%d)",
                bits.size(), numBlocks, width, height);

        // 3. For each 8×8 block of Y: DCT → SVD → modify σ₀ → iSVD → iDCT
        int bitIdx = 0;
        for (int br = 0; br < bRows && bitIdx < static_cast<int>(bits.size()); br += BS) {
            for (int bc = 0; bc < bCols && bitIdx < static_cast<int>(bits.size()); bc += BS) {
                cv::Mat block = Y(cv::Rect(bc, br, BS, BS)).clone();
                cv::Mat dctBlock;
                cv::dct(block, dctBlock);

                cv::Mat w, u, vt;
                cv::SVD::compute(dctBlock, w, u, vt);

                float sigma0 = w.at<float>(0);
                float base = std::round(sigma0 / DELTA) * DELTA;
                float newSigma;
                if (bits[bitIdx])
                    newSigma = base + DELTA * 0.25f;
                else
                    newSigma = base - DELTA * 0.25f;
                w.at<float>(0) = newSigma;

                // Reconstruct
                cv::Mat diag = cv::Mat::zeros(BS, BS, CV_32F);
                for (int k = 0; k < std::min(w.rows, BS); ++k)
                    diag.at<float>(k, k) = w.at<float>(k);
                cv::Mat reconDct = u * diag * vt;
                cv::Mat reconSpatial;
                cv::idct(reconDct, reconSpatial);

                // Clamp to [0, 255] to minimize clipping damage on uint8 conversion
                cv::min(reconSpatial, 255.0f, reconSpatial);
                cv::max(reconSpatial, 0.0f, reconSpatial);

                reconSpatial.copyTo(Y(cv::Rect(bc, br, BS, BS)));
                ++bitIdx;
            }
        }

        // 4. Float Y → uint8 → merge back → BGR → RGBA
        cv::Mat Yuint8;
        Y.convertTo(Yuint8, CV_8U);
        Yuint8.copyTo(channels[0]);

        cv::Mat ycrcbOut;
        cv::merge(channels, ycrcbOut);
        cv::Mat bgrOut;
        cv::cvtColor(ycrcbOut, bgrOut, cv::COLOR_YCrCb2BGR);
        cv::Mat rgbaOut;
        cv::cvtColor(bgrOut, rgbaOut, cv::COLOR_BGR2RGBA);
        std::memcpy(outRgba, rgbaOut.data, static_cast<size_t>(width) * height * 4);

        WM_LOGI("embed: success — %d bits embedded", bitIdx);
        return true;

    } catch (const cv::Exception& e) {
        WM_LOGE("embed cv::Exception: %s", e.what());
        return false;
    } catch (const std::exception& e) {
        WM_LOGE("embed exception: %s", e.what());
        return false;
    }
}

// ============================================================================
// Extract — block-DCT + SVD quantisation detection on Y channel
// ============================================================================

std::string Watermark::extract(const uint8_t* rgbaData, int width, int height) {
    if (!rgbaData || width < 64 || height < 64) {
        WM_LOGE("extract: invalid args w=%d h=%d", width, height);
        return "";
    }

    try {
        // 1. RGBA → BGR → YCrCb → Y float
        cv::Mat rgba(height, width, CV_8UC4, const_cast<uint8_t*>(rgbaData));
        cv::Mat bgr;
        cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
        cv::Mat ycrcb;
        cv::cvtColor(bgr, ycrcb, cv::COLOR_BGR2YCrCb);
        std::vector<cv::Mat> channels;
        cv::split(ycrcb, channels);
        cv::Mat Y;
        channels[0].convertTo(Y, CV_32F);

        // 2. Block-wise DCT+SVD extraction
        const int BS = 8;
        int bRows = (Y.rows / BS) * BS;
        int bCols = (Y.cols / BS) * BS;
        if (bRows < BS || bCols < BS) return "";

        int numBlocks = (bRows / BS) * (bCols / BS);
        int maxBits = std::min(numBlocks, 16 + 256 * 8 + 8);

        std::vector<uint8_t> bits;
        bits.reserve(maxBits);

        for (int br = 0; br < bRows && static_cast<int>(bits.size()) < maxBits; br += BS) {
            for (int bc = 0; bc < bCols && static_cast<int>(bits.size()) < maxBits; bc += BS) {
                cv::Mat block = Y(cv::Rect(bc, br, BS, BS)).clone();
                cv::Mat dctBlock;
                cv::dct(block, dctBlock);

                cv::Mat w, u, vt;
                cv::SVD::compute(dctBlock, w, u, vt);

                float sigma0 = w.at<float>(0);
                float remainder = std::fmod(sigma0, DELTA);
                if (remainder < 0) remainder += DELTA;
                uint8_t bit = remainder < DELTA * 0.5f ? 1 : 0;
                bits.push_back(bit);

            }
        }

        std::string decoded = decodeBits(bits);
        if (!decoded.empty()) {
            WM_LOGI("extract: recovered %zu-char payload", decoded.size());
        } else {
            WM_LOGI("extract: no valid watermark found");
        }
        return decoded;

    } catch (const cv::Exception& e) {
        WM_LOGE("extract cv::Exception: %s", e.what());
        return "";
    } catch (const std::exception& e) {
        WM_LOGE("extract exception: %s", e.what());
        return "";
    }
}

#else // !HAVE_OPENCV stubs

bool Watermark::embed(const uint8_t*, int, int, const std::string&, uint8_t*) {
    WM_LOGE("embed: OpenCV required");
    return false;
}

std::string Watermark::extract(const uint8_t*, int, int) {
    WM_LOGE("extract: OpenCV required");
    return "";
}

#endif // HAVE_OPENCV

} // namespace biovault
