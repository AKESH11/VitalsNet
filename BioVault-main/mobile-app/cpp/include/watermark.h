#ifndef BIOVAULT_WATERMARK_H
#define BIOVAULT_WATERMARK_H

#include <string>
#include <vector>
#include <cstdint>

#ifdef HAVE_OPENCV
#include <opencv2/core.hpp>
#endif

namespace biovault {

/**
 * DWT+DCT+SVD blind watermark — embeds/extracts a short JSON payload
 * in the Y channel of a color image.  Survives JPEG compression (~70-80%).
 *
 * Algorithm (inspired by guofei9987/blind_watermark, MIT):
 *   embed: BGR → YCrCb → Y(float32) → 1-level Haar DWT → DCT(LL) →
 *          4×4 block SVD → modify σ₀ → iDCT → iDWT → YCrCb → BGR
 *   extract: same forward path, read σ₀ per block → bits → JSON
 */
class Watermark {
public:
    /**
     * Embed a JSON payload into an RGBA image.
     * @param rgbaData  Raw RGBA pixel buffer (w*h*4 bytes)
     * @param width     Image width in pixels
     * @param height    Image height in pixels
     * @param payload   JSON string to embed (max ~60 chars)
     * @param outRgba   Output buffer (same size as input, caller-allocated)
     * @return true on success
     */
    static bool embed(const uint8_t* rgbaData, int width, int height,
                      const std::string& payload,
                      uint8_t* outRgba);

    /**
     * Extract a previously embedded JSON payload from an RGBA image.
     * @param rgbaData  Raw RGBA pixel buffer
     * @param width     Image width
     * @param height    Image height
     * @return The extracted JSON string, or "" on failure / no watermark
     */
    static std::string extract(const uint8_t* rgbaData, int width, int height);

private:
#ifdef HAVE_OPENCV
    // Embed strength — larger = more robust but more visible
    static constexpr float DELTA = 100.0f;

    // Encode a string into a bit vector with 16-bit length header + 8-bit checksum
    static std::vector<uint8_t> encodeBits(const std::string& payload);

    // Decode a bit vector back to string, returns "" on checksum failure
    static std::string decodeBits(const std::vector<uint8_t>& bits);

    // 1-level Haar DWT  (input must be even-dimensioned float32 single-channel)
    static void haarDWT(const cv::Mat& src, cv::Mat& LL, cv::Mat& LH,
                        cv::Mat& HL, cv::Mat& HH);

    // Inverse 1-level Haar DWT
    static void haarIDWT(const cv::Mat& LL, const cv::Mat& LH,
                         const cv::Mat& HL, const cv::Mat& HH,
                         cv::Mat& dst);
#endif
};

} // namespace biovault

#endif // BIOVAULT_WATERMARK_H
