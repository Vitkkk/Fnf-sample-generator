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
constexpr double kF0Floor = 45.0;
constexpr double kF0Ceil = 1800.0;
constexpr char kLogTag[] = "VocalWorld";

struct PitchEstimate {
  double f0 = 0.0;
  double confidence = 0.0;
};

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

// Local normalized autocorrelation pitch detector. This deliberately does not
// depend on WORLD's voiced/unvoiced decision. Short FNF character vowels can be
// very periodic while DIO still emits F0=0 because the timbre is unusual.
PitchEstimate AutocorrelationPitch(const std::vector<double>& x, int fs,
                                   int center_sample, double preferred_f0) {
  PitchEstimate result;
  if (x.empty() || fs <= 0) return result;

  const int radius = std::max(192, static_cast<int>(std::llround(fs * 0.040)));
  const int from = std::max(0, center_sample - radius);
  const int to = std::min(static_cast<int>(x.size()), center_sample + radius);
  const int n = to - from;
  if (n < 256) return result;

  double mean = 0.0;
  for (int i = from; i < to; ++i) mean += x[i];
  mean /= n;

  double min_hz = kF0Floor;
  double max_hz = kF0Ceil;
  if (IsVoicedF0(preferred_f0)) {
    // Broad enough to recover an octave error, but still uses the known pitch
    // neighborhood to avoid formants winning the autocorrelation search.
    min_hz = std::max(kF0Floor, preferred_f0 * 0.45);
    max_hz = std::min(kF0Ceil, preferred_f0 * 2.20);
  }

  const int min_lag = std::max(3, static_cast<int>(std::floor(fs / max_hz)));
  const int max_lag = std::min(n / 2, static_cast<int>(std::ceil(fs / min_hz)));
  if (max_lag <= min_lag + 2) return result;

  std::vector<double> corr(static_cast<size_t>(max_lag + 1), -1.0);
  double best = -1.0;
  for (int lag = min_lag; lag <= max_lag; ++lag) {
    double xy = 0.0;
    double xx = 0.0;
    double yy = 0.0;
    const int count = n - lag;
    for (int i = 0; i < count; ++i) {
      const double a = x[from + i] - mean;
      const double b = x[from + i + lag] - mean;
      xy += a * b;
      xx += a * a;
      yy += b * b;
    }
    const double c = (xx > 1e-14 && yy > 1e-14)
                         ? xy / std::sqrt(xx * yy)
                         : -1.0;
    corr[lag] = c;
    best = std::max(best, c);
  }
  if (best < 0.10) return result;

  const double threshold = std::max(0.10, best * 0.78);
  int chosen = -1;
  double chosen_score = -1e9;
  for (int lag = min_lag + 1; lag < max_lag; ++lag) {
    if (corr[lag] < threshold || corr[lag] < corr[lag - 1] ||
        corr[lag] < corr[lag + 1]) {
      continue;
    }
    const double f = fs / static_cast<double>(lag);
    double score = corr[lag];
    if (IsVoicedF0(preferred_f0)) {
      const double octave_distance = std::abs(std::log2(f / preferred_f0));
      score -= 0.07 * octave_distance;
    } else {
      // With no prior, prefer the first strong autocorrelation peak instead of
      // a later multiple of the same period.
      score -= 0.00008 * (lag - min_lag);
    }
    if (score > chosen_score) {
      chosen_score = score;
      chosen = lag;
    }
  }

  if (chosen < 0) {
    chosen = min_lag;
    for (int lag = min_lag + 1; lag <= max_lag; ++lag) {
      if (corr[lag] > corr[chosen]) chosen = lag;
    }
  }

  double refined = chosen;
  if (chosen > min_lag && chosen < max_lag) {
    const double y1 = corr[chosen - 1];
    const double y2 = corr[chosen];
    const double y3 = corr[chosen + 1];
    const double denom = y1 - 2.0 * y2 + y3;
    if (std::abs(denom) > 1e-10) {
      refined += Clamp(0.5 * (y1 - y3) / denom, -0.5, 0.5);
    }
  }

  const double f0 = fs / std::max(1.0, refined);
  if (!IsVoicedF0(f0)) return result;
  result.f0 = f0;
  result.confidence = Clamp(corr[chosen], 0.0, 1.0);
  return result;
}

bool BuildRobustF0(const std::vector<double>& x, int fs,
                   std::vector<double>* time_axis,
                   std::vector<double>* robust_f0,
                   std::vector<double>* periodicity) {
  if (time_axis == nullptr || robust_f0 == nullptr || periodicity == nullptr) {
    return false;
  }

  std::vector<double> dio_f0;
  if (!AnalyzeF0(x, fs, time_axis, &dio_f0)) return false;
  const int n = static_cast<int>(dio_f0.size());
  if (n < 2) return false;

  std::vector<double> dio_values;
  for (double f : dio_f0) {
    if (IsVoicedF0(f)) dio_values.push_back(f);
  }
  double preferred = dio_values.size() >= 2 ? Median(dio_values) : 0.0;

  const PitchEstimate center_auto = AutocorrelationPitch(
      x, fs, static_cast<int>(x.size() / 2), preferred);
  if (!IsVoicedF0(preferred) ||
      (center_auto.confidence >= 0.18 &&
       std::abs(1200.0 * std::log2(center_auto.f0 / preferred)) > 850.0)) {
    if (IsVoicedF0(center_auto.f0)) preferred = center_auto.f0;
  }
  if (!IsVoicedF0(preferred)) preferred = 220.0;

  robust_f0->assign(static_cast<size_t>(n), preferred);
  periodicity->assign(static_cast<size_t>(n), 0.0);

  for (int i = 0; i < n; ++i) {
    const int center = static_cast<int>(std::llround((*time_axis)[i] * fs));
    const PitchEstimate local = AutocorrelationPitch(x, fs, center, preferred);
    const bool dio_ok = IsVoicedF0(dio_f0[i]);
    const bool auto_ok = IsVoicedF0(local.f0) && local.confidence >= 0.10;

    double chosen = preferred;
    double conf = 0.06;
    if (dio_ok && auto_ok) {
      const double cents = std::abs(1200.0 * std::log2(local.f0 / dio_f0[i]));
      if (cents <= 650.0) {
        const double w = Clamp(local.confidence, 0.15, 0.85);
        chosen = std::exp(std::log(dio_f0[i]) * (1.0 - w) +
                          std::log(local.f0) * w);
        conf = std::max(0.45, local.confidence);
      } else {
        const double auto_distance = std::abs(std::log2(local.f0 / preferred));
        const double dio_distance = std::abs(std::log2(dio_f0[i] / preferred));
        chosen = (auto_distance <= dio_distance || local.confidence >= 0.35)
                     ? local.f0
                     : dio_f0[i];
        conf = (chosen == local.f0) ? local.confidence : 0.42;
      }
    } else if (auto_ok) {
      chosen = local.f0;
      conf = local.confidence;
    } else if (dio_ok) {
      chosen = dio_f0[i];
      conf = 0.40;
    }

    if (!IsVoicedF0(chosen)) chosen = preferred;
    (*robust_f0)[i] = Clamp(chosen, kF0Floor, kF0Ceil);
    (*periodicity)[i] = Clamp(conf, 0.0, 1.0);
  }

  // Remove isolated octave jumps while retaining genuine pitch motion.
  if (n >= 3) {
    std::vector<double> smoothed(*robust_f0);
    for (int i = 1; i < n - 1; ++i) {
      const double a = std::log(std::max(kF0Floor, (*robust_f0)[i - 1]));
      const double b = std::log(std::max(kF0Floor, (*robust_f0)[i]));
      const double c = std::log(std::max(kF0Floor, (*robust_f0)[i + 1]));
      const double neighbor = 0.5 * (a + c);
      if (std::abs(b - neighbor) > std::log(1.75)) {
        smoothed[i] = std::exp(0.25 * a + 0.50 * neighbor + 0.25 * c);
      } else {
        smoothed[i] = std::exp(0.16 * a + 0.68 * b + 0.16 * c);
      }
    }
    robust_f0->swap(smoothed);
  }

  return true;
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
  std::vector<double> periodicity;
  if (!BuildRobustF0(x, sample_rate, &time_axis, &f0, &periodicity)) return nullptr;

  const int n = static_cast<int>(f0.size());
  std::vector<double> energy(static_cast<size_t>(n), 0.0);
  std::vector<double> positive_energy;
  std::vector<double> confident_f0;
  for (int i = 0; i < n; ++i) {
    energy[i] = FrameRms(x, sample_rate, time_axis[i]);
    if (energy[i] > 1e-8) positive_energy.push_back(energy[i]);
    if (IsVoicedF0(f0[i]) && periodicity[i] >= 0.10) confident_f0.push_back(f0[i]);
  }
  if (positive_energy.empty() || confident_f0.empty()) return nullptr;

  const double median_f0 = Median(confident_f0);
  const double median_energy = std::max(1e-9, Median(positive_energy));
  std::vector<uint8_t> good(static_cast<size_t>(n), 0);

  for (int i = 0; i < n; ++i) {
    if (!IsVoicedF0(f0[i])) continue;
    const double cents = std::abs(1200.0 * std::log2(
        std::max(kF0Floor, f0[i]) / std::max(kF0Floor, median_f0)));
    const bool enough_energy = energy[i] >= std::max(1e-7, median_energy * 0.07);
    const bool periodic = periodicity[i] >= 0.10;
    if (enough_energy && periodic && cents <= 1200.0) good[i] = 1;
  }

  // Bridge up to two isolated misses. This is important for 100-200 ms vowels
  // where losing one WORLD frame should not make the whole sample invalid.
  for (int pass = 0; pass < 2; ++pass) {
    std::vector<uint8_t> next = good;
    for (int i = 1; i < n - 1; ++i) {
      if (!good[i] && good[i - 1] && good[i + 1]) next[i] = 1;
    }
    good.swap(next);
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

  const int minimum_frames = std::max(
      4, static_cast<int>(std::ceil(22.0 / kFramePeriodMs)));
  if (best_start < 0 || best_end - best_start < minimum_frames) {
    // Final short-vowel fallback: if the entire selection is strongly periodic,
    // trust the waveform and use its central 80% instead of rejecting a real A.
    const PitchEstimate global = AutocorrelationPitch(
        x, sample_rate, static_cast<int>(x.size() / 2), median_f0);
    if (global.confidence < 0.14) return nullptr;
    best_start = std::min(n - 1, std::max(0, n / 10));
    best_end = std::max(best_start + 1, std::min(n, n - n / 10));
  }

  const int best_frames = best_end - best_start;
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
  std::vector<double> run_periodicity;
  for (int i = best_start; i < best_end; ++i) {
    if (IsVoicedF0(f0[i])) {
      run_cents.push_back(std::abs(1200.0 * std::log2(
          std::max(kF0Floor, f0[i]) / std::max(kF0Floor, median_f0))));
    }
    run_periodicity.push_back(periodicity[i]);
  }
  const double cents_mad = run_cents.empty() ? 900.0 : Median(run_cents);
  const double pitch_stability = std::exp(-cents_mad / 500.0);
  const double periodic_strength = Clamp(Median(run_periodicity), 0.0, 1.0);
  const double coverage = best_frames / static_cast<double>(std::max(1, n));
  const int quality = static_cast<int>(std::llround(1000.0 * Clamp(
      0.50 * periodic_strength +
      0.30 * pitch_stability +
      0.20 * std::min(1.0, coverage / 0.35),
      0.0, 1.0)));

  jintArray result = env->NewIntArray(3);
  if (result == nullptr) return nullptr;
  const jint values[3] = {start_sample, end_sample, quality};
  env->SetIntArrayRegion(result, 0, 3, values);

  __android_log_print(
      ANDROID_LOG_INFO, kLogTag,
      "Robust core: samples=%d..%d quality=%d periodic=%.3f medianF0=%.2f",
      start_sample, end_sample, quality, periodic_strength, median_f0);
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
  std::vector<double> periodicity;
  if (!BuildRobustF0(x, sample_rate, &time_axis, &f0, &periodicity)) return nullptr;
  const int f0_length = static_cast<int>(f0.size());

  std::vector<double> confident_periodicity;
  for (double p : periodicity) {
    if (p >= 0.08) confident_periodicity.push_back(p);
  }
  const double median_periodicity = confident_periodicity.empty()
                                        ? 0.0
                                        : Median(confident_periodicity);
  if (median_periodicity < 0.09) {
    __android_log_print(ANDROID_LOG_WARN, kLogTag,
                        "Rejected synthesis: periodicity=%.3f",
                        median_periodicity);
    return nullptr;
  }

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

  CheapTrick(x.data(), input_length, sample_rate, time_axis.data(),
             f0.data(), f0_length, &cheap_option, spec_ptrs.data());

  D4COption d4c_option;
  InitializeD4COption(&d4c_option);
  std::vector<std::vector<double>> aperiodicity(
      static_cast<size_t>(f0_length),
      std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> aper_ptrs(static_cast<size_t>(f0_length));
  for (int i = 0; i < f0_length; ++i) aper_ptrs[i] = aperiodicity[i].data();

  D4C(x.data(), input_length, sample_rate, time_axis.data(),
      f0.data(), f0_length, fft_size, &d4c_option, aper_ptrs.data());

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

    out_f0[j] = InterpolateLog(f0[i0], f0[i1], a);
    const double local_periodicity = Clamp(
        periodicity[i0] * (1.0 - a) + periodicity[i1] * a, 0.0, 1.0);

    for (int b = 0; b < bins; ++b) {
      out_spec[j][b] = InterpolateLog(spectrogram[i0][b], spectrogram[i1][b], a);
      const double raw_aper = aperiodicity[i0][b] * (1.0 - a) +
                              aperiodicity[i1][b] * a;
      const double hz = b * sample_rate / static_cast<double>(fft_size);
      double cap = hz < 4500.0 ? 0.62 : (hz < 9000.0 ? 0.80 : 0.93);
      cap += (1.0 - local_periodicity) * 0.14;
      cap = Clamp(cap, 0.45, 0.97);
      out_aper[j][b] = Clamp(std::min(raw_aper, cap), 0.001, 0.97);
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
      "WORLD v0.5.4 robust-periodicity synth: input=%d target=%d fs=%d periodic=%.3f",
      input_length, target_frames, sample_rate, median_periodicity);
  return result;
}
