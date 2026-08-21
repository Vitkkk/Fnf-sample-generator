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
// FNF/chromatic voices can be much lower or higher than normal speech.
constexpr double kF0Floor = 45.0;
constexpr double kF0Ceil = 1800.0;
constexpr char kLogTag[] = "VocalWorld";

double Clamp(double v, double lo, double hi) {
  return std::max(lo, std::min(hi, v));
}

bool IsVoicedF0(double f0) {
  return f0 >= kF0Floor && f0 <= kF0Ceil && std::isfinite(f0);
}

double Median(std::vector<double> values) {
  if (values.empty()) return 0.0;
  const size_t mid = values.size() / 2;
  std::nth_element(values.begin(), values.begin() + mid, values.end());
  double result = values[mid];
  if ((values.size() & 1u) == 0u) {
    const auto lower = std::max_element(values.begin(), values.begin() + mid);
    if (lower != values.begin() + mid) result = (*lower + result) * 0.5;
  }
  return result;
}

void RemoveMean(std::vector<double>* x) {
  if (x == nullptr || x->empty()) return;
  const double mean = std::accumulate(x->begin(), x->end(), 0.0) /
                      static_cast<double>(x->size());
  for (double& v : *x) v -= mean;
}

double EstimateFallbackF0(const std::vector<double>& x, int fs) {
  if (x.size() < static_cast<size_t>(fs / 50)) return 220.0;

  const int desired = std::min<int>(static_cast<int>(x.size()), fs * 3 / 20);
  const int start = std::max(0, (static_cast<int>(x.size()) - desired) / 2);
  const int n = desired;
  if (n < 256) return 220.0;

  double mean = 0.0;
  for (int i = 0; i < n; ++i) mean += x[start + i];
  mean /= n;

  const int min_lag = std::max(4, fs / static_cast<int>(kF0Ceil));
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

bool AnalyzeF0(const std::vector<double>& x, int fs,
               std::vector<double>* time_axis,
               std::vector<double>* f0) {
  if (time_axis == nullptr || f0 == nullptr || x.empty() || fs <= 0) return false;

  DioOption dio_option;
  InitializeDioOption(&dio_option);
  dio_option.frame_period = kFramePeriodMs;
  dio_option.speed = 1;
  dio_option.f0_floor = kF0Floor;
  dio_option.f0_ceil = kF0Ceil;
  dio_option.allowed_range = 0.10;

  const int length = GetSamplesForDIO(
      fs, static_cast<int>(x.size()), kFramePeriodMs);
  if (length < 2) return false;

  time_axis->assign(static_cast<size_t>(length), 0.0);
  f0->assign(static_cast<size_t>(length), 0.0);
  std::vector<double> raw(static_cast<size_t>(length), 0.0);

  Dio(x.data(), static_cast<int>(x.size()), fs, &dio_option,
      time_axis->data(), raw.data());
  StoneMask(x.data(), static_cast<int>(x.size()), fs,
            time_axis->data(), raw.data(), length, f0->data());
  return true;
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

double FrameRms(const std::vector<double>& x, int fs, double time_seconds) {
  const int center = static_cast<int>(std::llround(time_seconds * fs));
  const int radius = std::max(16, static_cast<int>(std::llround(fs * 0.0125)));
  const int from = std::max(0, center - radius);
  const int to = std::min(static_cast<int>(x.size()), center + radius + 1);
  if (to <= from) return 0.0;
  double energy = 0.0;
  for (int i = from; i < to; ++i) energy += x[i] * x[i];
  return std::sqrt(energy / std::max(1, to - from));
}

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

std::vector<double> JFloatToDouble(JNIEnv* env, jfloatArray samples) {
  const jsize n = env->GetArrayLength(samples);
  std::vector<jfloat> tmp(static_cast<size_t>(n));
  env->GetFloatArrayRegion(samples, 0, n, tmp.data());
  std::vector<double> x(static_cast<size_t>(n));
  for (int i = 0; i < n; ++i) x[i] = static_cast<double>(tmp[i]);
  RemoveMean(&x);
  return x;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_vitkkk_vocalstretcher_WorldVocoderEngine_nativeFindStableVoicedCore(
    JNIEnv* env, jclass, jfloatArray mono_samples, jint sample_rate) {
  if (mono_samples == nullptr || sample_rate <= 0) return nullptr;
  const jsize input_length = env->GetArrayLength(mono_samples);
  if (input_length < 256) return nullptr;

  std::vector<double> x = JFloatToDouble(env, mono_samples);
  std::vector<double> time_axis;
  std::vector<double> f0;
  if (!AnalyzeF0(x, sample_rate, &time_axis, &f0)) return nullptr;

  const int n = static_cast<int>(f0.size());
  std::vector<double> voiced_f0;
  std::vector<double> voiced_energy;
  std::vector<double> energy(static_cast<size_t>(n), 0.0);
  for (int i = 0; i < n; ++i) {
    energy[i] = FrameRms(x, sample_rate, time_axis[i]);
    if (IsVoicedF0(f0[i])) {
      voiced_f0.push_back(f0[i]);
      voiced_energy.push_back(energy[i]);
    }
  }
  if (voiced_f0.size() < 3) return nullptr;

  const double median_f0 = Median(voiced_f0);
  const double median_energy = std::max(1e-9, Median(voiced_energy));
  std::vector<uint8_t> good(static_cast<size_t>(n), 0);
  int voiced_total = 0;
  for (int i = 0; i < n; ++i) {
    if (!IsVoicedF0(f0[i])) continue;
    ++voiced_total;
    const double cents = std::abs(1200.0 * std::log2(
        std::max(kF0Floor, f0[i]) / std::max(kF0Floor, median_f0)));
    const bool enough_energy = energy[i] >= std::max(1e-6, median_energy * 0.10);
    // Wide enough for strong vibrato / character voices, tight enough to reject
    // octave-tracker mistakes and consonant/breath frames.
    if (enough_energy && cents <= 850.0) good[i] = 1;
  }

  // Bridge one isolated detector dropout inside an otherwise voiced vowel.
  for (int i = 1; i < n - 1; ++i) {
    if (!good[i] && good[i - 1] && good[i + 1]) good[i] = 1;
  }

  int best_start = -1;
  int best_end = -1;
  int run_start = -1;
  for (int i = 0; i <= n; ++i) {
    const bool is_good = i < n && good[i] != 0;
    if (is_good && run_start < 0) run_start = i;
    if ((!is_good || i == n) && run_start >= 0) {
      if (best_start < 0 || i - run_start > best_end - best_start) {
        best_start = run_start;
        best_end = i;
      }
      run_start = -1;
    }
  }

  if (best_start < 0 || best_end <= best_start) return nullptr;
  const int best_frames = best_end - best_start;
  const int minimum_frames = std::max(
      4, static_cast<int>(std::ceil(35.0 / kFramePeriodMs)));
  if (best_frames < minimum_frames) return nullptr;

  // Give the stable core half a WORLD frame of context at each side, but do not
  // absorb consonants/silence back into it.
  const double start_seconds = std::max(
      0.0, time_axis[best_start] - kFramePeriodMs / 2000.0);
  const double end_seconds = std::min(
      input_length / static_cast<double>(sample_rate),
      time_axis[best_end - 1] + kFramePeriodMs / 1000.0 +
          kFramePeriodMs / 2000.0);
  int start_sample = static_cast<int>(std::llround(start_seconds * sample_rate));
  int end_sample = static_cast<int>(std::llround(end_seconds * sample_rate));
  start_sample = std::max(0, std::min(start_sample, input_length - 1));
  end_sample = std::max(start_sample + 1, std::min(end_sample, input_length));

  std::vector<double> run_cents;
  run_cents.reserve(static_cast<size_t>(best_frames));
  for (int i = best_start; i < best_end; ++i) {
    if (IsVoicedF0(f0[i])) {
      run_cents.push_back(std::abs(1200.0 * std::log2(
          std::max(kF0Floor, f0[i]) / std::max(kF0Floor, median_f0))));
    }
  }
  const double cents_mad = Median(run_cents);
  const double pitch_stability = std::exp(-cents_mad / 420.0);
  const double coverage = best_frames / static_cast<double>(std::max(1, n));
  const double voiced_ratio = voiced_total / static_cast<double>(std::max(1, n));
  const int quality = static_cast<int>(std::llround(1000.0 * Clamp(
      0.58 * pitch_stability +
      0.24 * std::min(1.0, coverage / 0.40) +
      0.18 * voiced_ratio,
      0.0, 1.0)));

  jintArray result = env->NewIntArray(3);
  if (result == nullptr) return nullptr;
  const jint values[3] = {start_sample, end_sample, quality};
  env->SetIntArrayRegion(result, 0, 3, values);

  __android_log_print(
      ANDROID_LOG_INFO, kLogTag,
      "Stable core: samples=%d..%d quality=%d voiced=%d/%d medianF0=%.2f",
      start_sample, end_sample, quality, voiced_total, n, median_f0);
  return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vitkkk_vocalstretcher_WorldVocoderEngine_nativeSynthesizeVowel(
    JNIEnv* env, jclass, jfloatArray mono_samples, jint sample_rate,
    jint target_frames) {
  if (mono_samples == nullptr || sample_rate <= 0 || target_frames <= 0) {
    return nullptr;
  }

  const jsize input_length = env->GetArrayLength(mono_samples);
  if (input_length < 256) return nullptr;
  std::vector<double> x = JFloatToDouble(env, mono_samples);

  std::vector<double> time_axis;
  std::vector<double> f0;
  if (!AnalyzeF0(x, sample_rate, &time_axis, &f0)) return nullptr;
  const int f0_length = static_cast<int>(f0.size());

  int voiced_count = 0;
  std::vector<double> voiced_values;
  for (double value : f0) {
    if (IsVoicedF0(value)) {
      ++voiced_count;
      voiced_values.push_back(value);
    }
  }
  // Refuse to turn mostly-unvoiced material into synthetic whisper noise.
  if (voiced_count < 3 || voiced_count < static_cast<int>(std::ceil(f0_length * 0.25))) {
    __android_log_print(ANDROID_LOG_WARN, kLogTag,
                        "Rejected WORLD synthesis: voiced=%d/%d",
                        voiced_count, f0_length);
    return nullptr;
  }

  const double fallback_f0 = voiced_values.empty()
                                 ? EstimateFallbackF0(x, sample_rate)
                                 : Median(voiced_values);
  const std::vector<double> continuous_f0 = MakeContinuousF0(f0, fallback_f0);

  CheapTrickOption cheap_option;
  InitializeCheapTrickOption(sample_rate, &cheap_option);
  cheap_option.f0_floor = kF0Floor;
  const int fft_size = GetFFTSizeForCheapTrick(sample_rate, &cheap_option);
  const int bins = fft_size / 2 + 1;

  std::vector<std::vector<double>> spectrogram(
      static_cast<size_t>(f0_length),
      std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> spec_ptrs(static_cast<size_t>(f0_length));
  for (int i = 0; i < f0_length; ++i) spec_ptrs[i] = spectrogram[i].data();

  // IMPORTANT v0.5.3: this is a verified voiced vowel core. Feeding raw DIO
  // zeros into CheapTrick/D4C made some files become whisper/TTS noise. Use the
  // repaired continuous F0 for analysis of this core as well as synthesis.
  CheapTrick(x.data(), input_length, sample_rate, time_axis.data(),
             continuous_f0.data(), f0_length, &cheap_option, spec_ptrs.data());

  D4COption d4c_option;
  InitializeD4COption(&d4c_option);
  std::vector<std::vector<double>> aperiodicity(
      static_cast<size_t>(f0_length),
      std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> aper_ptrs(static_cast<size_t>(f0_length));
  for (int i = 0; i < f0_length; ++i) aper_ptrs[i] = aperiodicity[i].data();

  D4C(x.data(), input_length, sample_rate, time_axis.data(),
      continuous_f0.data(), f0_length, fft_size, &d4c_option, aper_ptrs.data());

  const double frame_hop_samples = sample_rate * kFramePeriodMs / 1000.0;
  const int out_parameter_frames = std::max(
      2, static_cast<int>(std::ceil(target_frames / frame_hop_samples)) + 2);

  std::vector<double> out_f0(static_cast<size_t>(out_parameter_frames));
  std::vector<std::vector<double>> out_spec(
      static_cast<size_t>(out_parameter_frames),
      std::vector<double>(static_cast<size_t>(bins)));
  std::vector<std::vector<double>> out_aper(
      static_cast<size_t>(out_parameter_frames),
      std::vector<double>(static_cast<size_t>(bins)));
  std::vector<const double*> out_spec_ptrs(static_cast<size_t>(out_parameter_frames));
  std::vector<const double*> out_aper_ptrs(static_cast<size_t>(out_parameter_frames));

  for (int j = 0; j < out_parameter_frames; ++j) {
    const double u = (out_parameter_frames <= 1)
                         ? 0.0
                         : j / static_cast<double>(out_parameter_frames - 1);
    const double source_norm = ElasticSourcePosition(u);
    const double source_pos = source_norm * static_cast<double>(std::max(0, f0_length - 1));
    const int i0 = std::max(
        0, std::min(static_cast<int>(std::floor(source_pos)), f0_length - 1));
    const int i1 = std::max(0, std::min(i0 + 1, f0_length - 1));
    const double a = Clamp(source_pos - i0, 0.0, 1.0);

    out_f0[j] = InterpolateLog(continuous_f0[i0], continuous_f0[i1], a);
    for (int b = 0; b < bins; ++b) {
      out_spec[j][b] = InterpolateLog(spectrogram[i0][b], spectrogram[i1][b], a);
      out_aper[j][b] = Clamp(
          aperiodicity[i0][b] * (1.0 - a) + aperiodicity[i1][b] * a,
          0.001, 0.985);
    }
    out_spec_ptrs[j] = out_spec[j].data();
    out_aper_ptrs[j] = out_aper[j].data();
  }

  std::vector<double> y(static_cast<size_t>(target_frames), 0.0);
  Synthesis(out_f0.data(), out_parameter_frames, out_spec_ptrs.data(),
            out_aper_ptrs.data(), fft_size, kFramePeriodMs, sample_rate,
            target_frames, y.data());

  const double y_mean = std::accumulate(y.begin(), y.end(), 0.0) /
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
    output[i] = static_cast<jfloat>(Clamp(y[i] * safety, -1.0, 1.0));
  }
  env->SetFloatArrayRegion(result, 0, target_frames, output.data());

  __android_log_print(
      ANDROID_LOG_INFO, kLogTag,
      "WORLD v0.5.3 voiced-core synth: input=%d target=%d fs=%d voiced=%d/%d",
      input_length, target_frames, sample_rate, voiced_count, f0_length);
  return result;
}
