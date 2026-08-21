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

double EstimateFallbackF0(const std::vector<double>& x, int fs) {
  if (x.size() < static_cast<size_t>(fs / 40)) return 220.0;

  const int desired = std::min<int>(static_cast<int>(x.size()), fs * 3 / 20);
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
    const double corr = (xx > 1e-12 && yy > 1e-12)
                            ? xy / std::sqrt(xx * yy)
                            : -1.0;
    if (corr > best_corr) {
      best_corr = corr;
      best_lag = lag;
    }
  }

  if (best_corr < 0.12) return 220.0;
  return Clamp(static_cast<double>(fs) / best_lag, kF0Floor, kF0Ceil);
}

bool IsVoicedF0(double f0) {
  return f0 >= kF0Floor && f0 <= kF0Ceil && std::isfinite(f0);
}

std::vector<double> MakeContinuousF0(const std::vector<double>& raw,
                                     double fallback) {
  const int n = static_cast<int>(raw.size());
  std::vector<double> out(raw);
  std::vector<int> previous(static_cast<size_t>(n), -1);
  std::vector<int> next(static_cast<size_t>(n), -1);

  int last = -1;
  for (int i = 0; i < n; ++i) {
    if (IsVoicedF0(raw[i])) last = i;
    previous[i] = last;
  }
  last = -1;
  for (int i = n - 1; i >= 0; --i) {
    if (IsVoicedF0(raw[i])) last = i;
    next[i] = last;
  }

  for (int i = 0; i < n; ++i) {
    if (IsVoicedF0(out[i])) continue;
    const int p = previous[i];
    const int q = next[i];
    if (p >= 0 && q >= 0 && p != q) {
      const double a = (i - p) / static_cast<double>(q - p);
      const double lp = std::log(std::max(kF0Floor, raw[p]));
      const double lq = std::log(std::max(kF0Floor, raw[q]));
      out[i] = std::exp(lp * (1.0 - a) + lq * a);
    } else if (p >= 0) {
      out[i] = raw[p];
    } else if (q >= 0) {
      out[i] = raw[q];
    } else {
      out[i] = fallback;
    }
  }

  // Tiny three-frame smoothing removes isolated tracker jumps without flattening
  // the actual pitch contour/vibrato of the selected vowel.
  if (n >= 3) {
    std::vector<double> smooth(out);
    for (int i = 1; i < n - 1; ++i) {
      const double a = std::log(std::max(kF0Floor, out[i - 1]));
      const double b = std::log(std::max(kF0Floor, out[i]));
      const double c = std::log(std::max(kF0Floor, out[i + 1]));
      smooth[i] = std::exp(a * 0.20 + b * 0.60 + c * 0.20);
    }
    out.swap(smooth);
  }
  return out;
}

// Time map used by v0.4. The source is traversed exactly once and never wraps.
// The central 64% of the selected vowel occupies 76% of output time, while the
// first/last 18% move a little faster. This protects vowel onset/release from
// becoming unnaturally slow while still truly stretching the whole trajectory.
double ElasticSourcePosition(double u) {
  u = Clamp(u, 0.0, 1.0);
  constexpr double out_edge = 0.12;
  constexpr double src_edge = 0.18;
  if (u <= out_edge) {
    return (u / out_edge) * src_edge;
  }
  if (u >= 1.0 - out_edge) {
    const double local = (u - (1.0 - out_edge)) / out_edge;
    return (1.0 - src_edge) + local * src_edge;
  }
  const double local = (u - out_edge) / (1.0 - 2.0 * out_edge);
  return src_edge + local * (1.0 - 2.0 * src_edge);
}

double InterpolateLog(double a, double b, double t) {
  a = std::max(1e-12, a);
  b = std::max(1e-12, b);
  return std::exp(std::log(a) * (1.0 - t) + std::log(b) * t);
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vitkkk_vocalstretcher_WorldVocoderEngine_nativeSynthesizeVowel(
    JNIEnv* env, jclass, jfloatArray mono_samples, jint sample_rate,
    jint target_frames) {
  if (mono_samples == nullptr || sample_rate <= 0 || target_frames <= 0) {
    return nullptr;
  }

  const jsize input_length = env->GetArrayLength(mono_samples);
  if (input_length < 256) return nullptr;

  std::vector<jfloat> input_float(static_cast<size_t>(input_length));
  env->GetFloatArrayRegion(mono_samples, 0, input_length, input_float.data());

  std::vector<double> x(static_cast<size_t>(input_length));
  double mean = 0.0;
  for (int i = 0; i < input_length; ++i) mean += input_float[i];
  mean /= input_length;
  for (int i = 0; i < input_length; ++i) {
    x[i] = static_cast<double>(input_float[i]) - mean;
  }

  DioOption dio_option;
  InitializeDioOption(&dio_option);
  dio_option.frame_period = kFramePeriodMs;
  dio_option.speed = 1;
  dio_option.f0_floor = kF0Floor;
  dio_option.f0_ceil = kF0Ceil;
  dio_option.allowed_range = 0.10;

  const int f0_length =
      GetSamplesForDIO(sample_rate, input_length, kFramePeriodMs);
  if (f0_length < 2) return nullptr;

  std::vector<double> time_axis(static_cast<size_t>(f0_length));
  std::vector<double> f0(static_cast<size_t>(f0_length));
  std::vector<double> refined_f0(static_cast<size_t>(f0_length));

  Dio(x.data(), input_length, sample_rate, &dio_option, time_axis.data(),
      f0.data());
  StoneMask(x.data(), input_length, sample_rate, time_axis.data(), f0.data(),
            f0_length, refined_f0.data());
  f0.swap(refined_f0);

  const double fallback_f0 = EstimateFallbackF0(x, sample_rate);
  const std::vector<double> continuous_f0 =
      MakeContinuousF0(f0, fallback_f0);

  CheapTrickOption cheap_option;
  InitializeCheapTrickOption(sample_rate, &cheap_option);
  cheap_option.f0_floor = kF0Floor;
  const int fft_size = GetFFTSizeForCheapTrick(sample_rate, &cheap_option);
  const int bins = fft_size / 2 + 1;

  std::vector<std::vector<double>> spectrogram(
      static_cast<size_t>(f0_length),
      std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> spec_ptrs(static_cast<size_t>(f0_length));
  for (int i = 0; i < f0_length; ++i) {
    spec_ptrs[i] = spectrogram[i].data();
  }

  // WORLD still receives its original voiced/unvoiced decisions for analysis.
  CheapTrick(x.data(), input_length, sample_rate, time_axis.data(), f0.data(),
             f0_length, &cheap_option, spec_ptrs.data());

  D4COption d4c_option;
  InitializeD4COption(&d4c_option);
  std::vector<std::vector<double>> aperiodicity(
      static_cast<size_t>(f0_length),
      std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> aper_ptrs(static_cast<size_t>(f0_length));
  for (int i = 0; i < f0_length; ++i) {
    aper_ptrs[i] = aperiodicity[i].data();
  }

  D4C(x.data(), input_length, sample_rate, time_axis.data(), f0.data(),
      f0_length, fft_size, &d4c_option, aper_ptrs.data());

  const double frame_hop_samples =
      sample_rate * kFramePeriodMs / 1000.0;
  const int out_parameter_frames = std::max(
      2, static_cast<int>(std::ceil(target_frames / frame_hop_samples)) + 2);

  // v0.4: no stationary average. Every output analysis frame is generated by
  // interpolating a progressively moving position in the ORIGINAL WORLD
  // parameter trajectory. The source position is strictly monotonic and never
  // wraps, so neither PCM nor vocoder frames are duplicated in a tiled loop.
  std::vector<double> out_f0(static_cast<size_t>(out_parameter_frames));
  std::vector<std::vector<double>> out_spec(
      static_cast<size_t>(out_parameter_frames),
      std::vector<double>(static_cast<size_t>(bins)));
  std::vector<std::vector<double>> out_aper(
      static_cast<size_t>(out_parameter_frames),
      std::vector<double>(static_cast<size_t>(bins)));
  std::vector<const double*> out_spec_ptrs(
      static_cast<size_t>(out_parameter_frames));
  std::vector<const double*> out_aper_ptrs(
      static_cast<size_t>(out_parameter_frames));

  for (int j = 0; j < out_parameter_frames; ++j) {
    const double u = (out_parameter_frames <= 1)
                         ? 0.0
                         : j / static_cast<double>(out_parameter_frames - 1);
    const double source_norm = ElasticSourcePosition(u);
    const double source_pos =
        source_norm * static_cast<double>(std::max(0, f0_length - 1));
    const int i0 = std::max(
        0, std::min(static_cast<int>(std::floor(source_pos)), f0_length - 1));
    const int i1 = std::max(
        0, std::min(i0 + 1, f0_length - 1));
    const double a = Clamp(source_pos - i0, 0.0, 1.0);

    out_f0[j] = InterpolateLog(continuous_f0[i0], continuous_f0[i1], a);

    for (int b = 0; b < bins; ++b) {
      // Log interpolation preserves formant peaks much better than linearly
      // averaging magnitudes and, crucially, keeps their movement through time.
      out_spec[j][b] = InterpolateLog(
          spectrogram[i0][b], spectrogram[i1][b], a);
      out_aper[j][b] = Clamp(
          aperiodicity[i0][b] * (1.0 - a) + aperiodicity[i1][b] * a,
          0.001, 0.999);
    }
    out_spec_ptrs[j] = out_spec[j].data();
    out_aper_ptrs[j] = out_aper[j].data();
  }

  std::vector<double> y(static_cast<size_t>(target_frames), 0.0);
  Synthesis(out_f0.data(), out_parameter_frames, out_spec_ptrs.data(),
            out_aper_ptrs.data(), fft_size, kFramePeriodMs, sample_rate,
            target_frames, y.data());

  double y_mean =
      std::accumulate(y.begin(), y.end(), 0.0) /
      std::max<size_t>(1, y.size());
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
    output[i] = static_cast<jfloat>(
        Clamp(y[i] * safety, -1.0, 1.0));
  }
  env->SetFloatArrayRegion(result, 0, target_frames, output.data());

  __android_log_print(
      ANDROID_LOG_INFO, kLogTag,
      "WORLD v0.4 elastic synth: input=%d target=%d fs=%d f0Frames=%d outFrames=%d",
      input_length, target_frames, sample_rate, f0_length,
      out_parameter_frames);
  return result;
}
