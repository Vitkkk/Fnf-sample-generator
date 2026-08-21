#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <numeric>
#include <vector>

#include "world/cheaptrick.h"
#include "world/d4c.h"
#include "world/dio.h"
#include "world/stonemask.h"
#include "world/synthesis.h"

namespace {
constexpr double kFramePeriodMs = 5.0;
constexpr double kF0Floor = 71.0;
constexpr double kF0Ceil = 1100.0;
constexpr char kLogTag[] = "VocalWorld";

double Clamp(double v, double lo, double hi) {
  return std::max(lo, std::min(hi, v));
}

double Median(std::vector<double> values) {
  if (values.empty()) return 0.0;
  const size_t mid = values.size() / 2;
  std::nth_element(values.begin(), values.begin() + mid, values.end());
  double result = values[mid];
  if ((values.size() & 1u) == 0u) {
    auto max_it = std::max_element(values.begin(), values.begin() + mid);
    result = (result + *max_it) * 0.5;
  }
  return result;
}

double EstimateFallbackF0(const std::vector<double>& x, int fs) {
  if (x.size() < static_cast<size_t>(fs / 40)) return 220.0;

  const int desired = std::min<int>(static_cast<int>(x.size()), fs * 3 / 20); // 150 ms
  const int start = std::max(0, (static_cast<int>(x.size()) - desired) / 2);
  const int n = desired;
  if (n < 256) return 220.0;

  double mean = 0.0;
  for (int i = 0; i < n; ++i) mean += x[start + i];
  mean /= n;

  const int min_lag = std::max(8, fs / static_cast<int>(kF0Ceil));
  const int max_lag = std::min(n / 2, fs / static_cast<int>(kF0Floor));
  double best_corr = -1.0;
  int best_lag = std::max(min_lag, 1);

  for (int lag = min_lag; lag <= max_lag; ++lag) {
    double xy = 0.0;
    double xx = 0.0;
    double yy = 0.0;
    const int count = n - lag;
    for (int i = 0; i < count; ++i) {
      const double a = x[start + i] - mean;
      const double b = x[start + i + lag] - mean;
      xy += a * b;
      xx += a * a;
      yy += b * b;
    }
    const double corr = (xx > 1e-12 && yy > 1e-12) ? xy / std::sqrt(xx * yy) : -1.0;
    if (corr > best_corr) {
      best_corr = corr;
      best_lag = lag;
    }
  }

  if (best_corr < 0.12) return 220.0;
  return Clamp(static_cast<double>(fs) / best_lag, kF0Floor, kF0Ceil);
}

std::vector<int> StableFrames(const std::vector<double>& f0) {
  std::vector<int> voiced;
  if (f0.empty()) return voiced;

  int from = static_cast<int>(std::floor(f0.size() * 0.20));
  int to = static_cast<int>(std::ceil(f0.size() * 0.80));
  from = std::max(0, std::min(from, static_cast<int>(f0.size()) - 1));
  to = std::max(from + 1, std::min(to, static_cast<int>(f0.size())));

  for (int i = from; i < to; ++i) {
    if (f0[i] >= kF0Floor && f0[i] <= kF0Ceil) voiced.push_back(i);
  }

  if (voiced.size() < 3) {
    voiced.clear();
    for (int i = 0; i < static_cast<int>(f0.size()); ++i) {
      if (f0[i] >= kF0Floor && f0[i] <= kF0Ceil) voiced.push_back(i);
    }
  }

  if (voiced.size() < 3) {
    voiced.clear();
    for (int i = from; i < to; ++i) voiced.push_back(i);
  }
  return voiced;
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vitkkk_vocalstretcher_WorldVocoderEngine_nativeSynthesizeVowel(
    JNIEnv* env, jclass, jfloatArray mono_samples, jint sample_rate, jint target_frames) {
  if (mono_samples == nullptr || sample_rate <= 0 || target_frames <= 0) return nullptr;

  const jsize input_length = env->GetArrayLength(mono_samples);
  if (input_length < 256) return nullptr;

  std::vector<jfloat> input_float(static_cast<size_t>(input_length));
  env->GetFloatArrayRegion(mono_samples, 0, input_length, input_float.data());

  std::vector<double> x(static_cast<size_t>(input_length));
  double mean = 0.0;
  for (int i = 0; i < input_length; ++i) mean += input_float[i];
  mean /= input_length;
  for (int i = 0; i < input_length; ++i) x[i] = static_cast<double>(input_float[i]) - mean;

  DioOption dio_option;
  InitializeDioOption(&dio_option);
  dio_option.frame_period = kFramePeriodMs;
  dio_option.speed = 1;
  dio_option.f0_floor = kF0Floor;
  dio_option.f0_ceil = kF0Ceil;
  dio_option.allowed_range = 0.10;

  const int f0_length = GetSamplesForDIO(sample_rate, input_length, kFramePeriodMs);
  if (f0_length < 2) return nullptr;

  std::vector<double> time_axis(static_cast<size_t>(f0_length));
  std::vector<double> f0(static_cast<size_t>(f0_length));
  std::vector<double> refined_f0(static_cast<size_t>(f0_length));

  Dio(x.data(), input_length, sample_rate, &dio_option, time_axis.data(), f0.data());
  StoneMask(x.data(), input_length, sample_rate, time_axis.data(), f0.data(),
            f0_length, refined_f0.data());
  f0.swap(refined_f0);

  std::vector<int> stable = StableFrames(f0);
  std::vector<double> voiced_f0;
  voiced_f0.reserve(stable.size());
  for (int index : stable) {
    if (f0[index] >= kF0Floor && f0[index] <= kF0Ceil) voiced_f0.push_back(f0[index]);
  }

  double sustained_f0 = Median(voiced_f0);
  if (!(sustained_f0 >= kF0Floor && sustained_f0 <= kF0Ceil)) {
    sustained_f0 = EstimateFallbackF0(x, sample_rate);
  }

  // WORLD's CheapTrick analyzes the spectral envelope independently from the
  // final sustained F0. This is the key difference from repeating waveform grains.
  CheapTrickOption cheap_option;
  InitializeCheapTrickOption(sample_rate, &cheap_option);
  cheap_option.f0_floor = kF0Floor;
  const int fft_size = GetFFTSizeForCheapTrick(sample_rate, &cheap_option);
  const int bins = fft_size / 2 + 1;

  std::vector<std::vector<double>> spectrogram(
      static_cast<size_t>(f0_length), std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> spec_ptrs(static_cast<size_t>(f0_length));
  for (int i = 0; i < f0_length; ++i) spec_ptrs[i] = spectrogram[i].data();

  CheapTrick(x.data(), input_length, sample_rate, time_axis.data(), f0.data(),
             f0_length, &cheap_option, spec_ptrs.data());

  D4COption d4c_option;
  InitializeD4COption(&d4c_option);
  std::vector<std::vector<double>> aperiodicity(
      static_cast<size_t>(f0_length), std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> aper_ptrs(static_cast<size_t>(f0_length));
  for (int i = 0; i < f0_length; ++i) aper_ptrs[i] = aperiodicity[i].data();

  D4C(x.data(), input_length, sample_rate, time_axis.data(), f0.data(), f0_length,
      fft_size, &d4c_option, aper_ptrs.data());

  if (stable.empty()) {
    for (int i = 0; i < f0_length; ++i) stable.push_back(i);
  }

  // Build one robust, stationary vowel model. Averaging the spectrum in log
  // domain preserves the formant shape while suppressing frame-to-frame wobble
  // that was perceived as alternating "thick/thin" timbre in v0.2.
  std::vector<double> avg_spec(static_cast<size_t>(bins), 0.0);
  std::vector<double> avg_aper(static_cast<size_t>(bins), 0.0);
  for (int index : stable) {
    for (int b = 0; b < bins; ++b) {
      avg_spec[b] += std::log(std::max(1e-12, spectrogram[index][b]));
      avg_aper[b] += Clamp(aperiodicity[index][b], 0.0, 1.0);
    }
  }
  const double inv_count = 1.0 / std::max<size_t>(1, stable.size());
  for (int b = 0; b < bins; ++b) {
    avg_spec[b] = std::exp(avg_spec[b] * inv_count);
    avg_aper[b] = Clamp(avg_aper[b] * inv_count, 0.001, 0.999);
  }

  const double frame_hop_samples = sample_rate * kFramePeriodMs / 1000.0;
  const int out_parameter_frames =
      std::max(2, static_cast<int>(std::ceil(target_frames / frame_hop_samples)) + 2);

  std::vector<double> out_f0(static_cast<size_t>(out_parameter_frames), sustained_f0);
  std::vector<const double*> out_spec_ptrs(static_cast<size_t>(out_parameter_frames), avg_spec.data());
  std::vector<const double*> out_aper_ptrs(static_cast<size_t>(out_parameter_frames), avg_aper.data());
  std::vector<double> y(static_cast<size_t>(target_frames), 0.0);

  Synthesis(out_f0.data(), out_parameter_frames, out_spec_ptrs.data(),
            out_aper_ptrs.data(), fft_size, kFramePeriodMs, sample_rate,
            target_frames, y.data());

  // Remove any tiny DC bias and return normalized float PCM. Java performs the
  // final RMS match and edge crossfades against the untouched source audio.
  double y_mean = std::accumulate(y.begin(), y.end(), 0.0) / std::max<size_t>(1, y.size());
  double peak = 1e-9;
  for (double& v : y) {
    v -= y_mean;
    peak = std::max(peak, std::abs(v));
  }
  const double safety = peak > 1.0 ? 0.98 / peak : 1.0;

  jfloatArray result = env->NewFloatArray(target_frames);
  if (result == nullptr) return nullptr;
  std::vector<jfloat> output(static_cast<size_t>(target_frames));
  for (int i = 0; i < target_frames; ++i) {
    output[i] = static_cast<jfloat>(Clamp(y[i] * safety, -1.0, 1.0));
  }
  env->SetFloatArrayRegion(result, 0, target_frames, output.data());

  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "WORLD synth: input=%d target=%d fs=%d f0=%.2fHz frames=%d",
                      input_length, target_frames, sample_rate, sustained_f0, f0_length);
  return result;
}
