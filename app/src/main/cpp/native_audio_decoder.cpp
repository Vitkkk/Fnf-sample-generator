#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <limits>
#include <vector>

#define MA_NO_DEVICE_IO
#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

namespace {
constexpr char kTag[] = "VocalImport";
constexpr uint64_t kMaxSamples = 80ull * 1000ull * 1000ull;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vitkkk_vocalstretcher_NativeAudioDecoder_nativeDecodeFile(
        JNIEnv* env, jclass, jstring path_string) {
    if (path_string == nullptr) return nullptr;

    const char* path = env->GetStringUTFChars(path_string, nullptr);
    if (path == nullptr) return nullptr;

    ma_decoder decoder;
    ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 0, 0);
    ma_result result = ma_decoder_init_file(path, &config, &decoder);
    env->ReleaseStringUTFChars(path_string, path);

    if (result != MA_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "miniaudio could not initialize decoder: %d", result);
        return nullptr;
    }

    const ma_uint32 channels = decoder.outputChannels;
    const ma_uint32 sample_rate = decoder.outputSampleRate;
    if (channels == 0 || channels > 32 || sample_rate == 0) {
        ma_decoder_uninit(&decoder);
        return nullptr;
    }

    std::vector<float> pcm;
    ma_uint64 known_frames = 0;
    if (ma_decoder_get_length_in_pcm_frames(&decoder, &known_frames) == MA_SUCCESS && known_frames > 0) {
        const uint64_t wanted = static_cast<uint64_t>(known_frames) * channels;
        if (wanted > kMaxSamples || wanted > static_cast<uint64_t>(std::numeric_limits<jsize>::max() - 2)) {
            ma_decoder_uninit(&decoder);
            return nullptr;
        }
        pcm.reserve(static_cast<size_t>(wanted));
    }

    constexpr ma_uint64 kChunkFrames = 4096;
    std::vector<float> chunk(static_cast<size_t>(kChunkFrames * channels));

    while (true) {
        ma_uint64 frames_read = 0;
        result = ma_decoder_read_pcm_frames(&decoder, chunk.data(), kChunkFrames, &frames_read);
        if (frames_read > 0) {
            const uint64_t samples_read = frames_read * channels;
            if (pcm.size() + samples_read > kMaxSamples ||
                pcm.size() + samples_read > static_cast<size_t>(std::numeric_limits<jsize>::max() - 2)) {
                ma_decoder_uninit(&decoder);
                return nullptr;
            }
            pcm.insert(pcm.end(), chunk.begin(), chunk.begin() + static_cast<size_t>(samples_read));
        }

        if (result == MA_AT_END || frames_read == 0) break;
        if (result != MA_SUCCESS) {
            ma_decoder_uninit(&decoder);
            return nullptr;
        }
    }

    ma_decoder_uninit(&decoder);
    if (pcm.empty()) return nullptr;

    const jsize total = static_cast<jsize>(pcm.size() + 2);
    jfloatArray out = env->NewFloatArray(total);
    if (out == nullptr) return nullptr;

    const jfloat header[2] = {
        static_cast<jfloat>(sample_rate),
        static_cast<jfloat>(channels)
    };
    env->SetFloatArrayRegion(out, 0, 2, header);
    env->SetFloatArrayRegion(out, 2, static_cast<jsize>(pcm.size()), pcm.data());

    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "miniaudio decoded %zu samples, %u Hz, %u ch",
                        pcm.size(), sample_rate, channels);
    return out;
}
