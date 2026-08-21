#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <numeric>
#include <vector>

#include "world/cheaptrick.h"
#include "world/d4c.h"
#include "world/dio.h"
#include "world/fft.h"
#include "world/stonemask.h"
#include "world/synthesis.h"

namespace {
constexpr double kFramePeriodMs = 5.0;
constexpr double kF0Floor = 45.0;
constexpr double kF0Ceil = 1800.0;
constexpr double kPi = 3.14159265358979323846;
constexpr char kLogTag[] = "VocalRawEnvelope";

struct PitchEstimate {
  double f0 = 0.0;
  double confidence = 0.0;
};

double Clamp(double v, double lo, double hi) {
  return std::max(lo, std::min(hi, v));
}

bool IsF0(double f) {
  return std::isfinite(f) && f >= kF0Floor && f <= kF0Ceil;
}

double Median(std::vector<double> values) {
  if (values.empty()) return 0.0;
  const size_t mid = values.size() / 2;
  std::nth_element(values.begin(), values.begin() + mid, values.end());
  double result = values[mid];
  if ((values.size() & 1u) == 0u && mid > 0) {
    const auto lower = std::max_element(values.begin(), values.begin() + mid);
    if (lower != values.begin() + mid) result = (*lower + result) * 0.5;
  }
  return result;
}

void RemoveMean(std::vector<double>* x) {
  if (!x || x->empty()) return;
  const double mean = std::accumulate(x->begin(), x->end(), 0.0) /
                      static_cast<double>(x->size());
  for (double& v : *x) v -= mean;
}

PitchEstimate AutoPitch(const std::vector<double>& x, int fs, int center,
                        double preferred, bool narrow) {
  PitchEstimate out;
  if (x.empty() || fs <= 0) return out;

  const int radius = std::max(192, static_cast<int>(std::llround(fs * 0.040)));
  const int from = std::max(0, center - radius);
  const int to = std::min(static_cast<int>(x.size()), center + radius);
  const int n = to - from;
  if (n < 256) return out;

  double mean = 0.0;
  for (int i = from; i < to; ++i) mean += x[i];
  mean /= n;

  double min_hz = kF0Floor;
  double max_hz = kF0Ceil;
  if (narrow && IsF0(preferred)) {
    min_hz = std::max(kF0Floor, preferred / 1.48);
    max_hz = std::min(kF0Ceil, preferred * 1.48);
  }

  const int min_lag = std::max(3, static_cast<int>(std::floor(fs / max_hz)));
  const int max_lag = std::min(n / 2, static_cast<int>(std::ceil(fs / min_hz)));
  if (max_lag <= min_lag + 2) return out;

  std::vector<double> corr(static_cast<size_t>(max_lag + 1), -1.0);
  double best = -1.0;
  for (int lag = min_lag; lag <= max_lag; ++lag) {
    double xy = 0.0, xx = 0.0, yy = 0.0;
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
  if (best < 0.09) return out;

  const double threshold = std::max(0.09, best * 0.80);
  int chosen = -1;
  for (int lag = min_lag + 1; lag < max_lag; ++lag) {
    if (corr[lag] >= threshold && corr[lag] >= corr[lag - 1] &&
        corr[lag] >= corr[lag + 1]) {
      chosen = lag;
      break;
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
  if (!IsF0(f0)) return out;
  out.f0 = f0;
  out.confidence = Clamp(corr[chosen], 0.0, 1.0);
  return out;
}

double FoldOctave(double f, double reference) {
  if (!IsF0(f) || !IsF0(reference)) return reference;
  while (f / reference > std::sqrt(2.0)) f *= 0.5;
  while (reference / f > std::sqrt(2.0)) f *= 2.0;
  return Clamp(f, kF0Floor, kF0Ceil);
}

bool BuildPitch(const std::vector<double>& x, int fs,
                std::vector<double>* time_axis,
                std::vector<double>* f0,
                std::vector<double>* confidence,
                double* reference_f0) {
  if (!time_axis || !f0 || !confidence || !reference_f0 || x.empty()) return false;

  DioOption option;
  InitializeDioOption(&option);
  option.frame_period = kFramePeriodMs;
  option.speed = 1;
  option.f0_floor = kF0Floor;
  option.f0_ceil = kF0Ceil;
  option.allowed_range = 0.12;

  const int n = GetSamplesForDIO(fs, static_cast<int>(x.size()), kFramePeriodMs);
  if (n < 2) return false;
  time_axis->assign(static_cast<size_t>(n), 0.0);
  std::vector<double> dio_raw(static_cast<size_t>(n), 0.0);
  std::vector<double> dio(static_cast<size_t>(n), 0.0);
  Dio(x.data(), static_cast<int>(x.size()), fs, &option,
      time_axis->data(), dio_raw.data());
  StoneMask(x.data(), static_cast<int>(x.size()), fs, time_axis->data(),
            dio_raw.data(), n, dio.data());

  std::vector<double> dio_values;
  for (double v : dio) if (IsF0(v)) dio_values.push_back(v);
  const double dio_median = dio_values.empty() ? 0.0 : Median(dio_values);
  const PitchEstimate global_auto = AutoPitch(
      x, fs, static_cast<int>(x.size() / 2), dio_median, false);

  double reference = dio_median;
  if (!IsF0(reference)) reference = global_auto.f0;
  if (IsF0(global_auto.f0) && global_auto.confidence >= 0.14) {
    if (!IsF0(reference)) {
      reference = global_auto.f0;
    } else {
      const double folded = FoldOctave(reference, global_auto.f0);
      const double cents = std::abs(1200.0 * std::log2(folded / global_auto.f0));
      reference = cents < 320.0
                      ? std::exp(0.30 * std::log(folded) +
                                 0.70 * std::log(global_auto.f0))
                      : global_auto.f0;
    }
  }
  if (!IsF0(reference)) return false;

  f0->assign(static_cast<size_t>(n), reference);
  confidence->assign(static_cast<size_t>(n), 0.08);

  for (int i = 0; i < n; ++i) {
    const int center = static_cast<int>(std::llround((*time_axis)[i] * fs));
    const PitchEstimate local = AutoPitch(x, fs, center, reference, true);
    const bool local_ok = IsF0(local.f0) && local.confidence >= 0.10;
    const bool dio_ok = IsF0(dio[i]);
    double value = reference;
    double conf = 0.08;

    if (local_ok) {
      value = FoldOctave(local.f0, reference);
      conf = local.confidence;
    }
    if (dio_ok) {
      const double d = FoldOctave(dio[i], reference);
      if (local_ok) {
        const double w = Clamp(local.confidence, 0.30, 0.88);
        value = std::exp(std::log(d) * (1.0 - w) + std::log(value) * w);
        conf = std::max(conf, 0.35);
      } else {
        value = d;
        conf = 0.30;
      }
    }

    // A chromatic sample is one sustained note. Keep real inflection/vibrato,
    // but never let a tracker octave error create a different character voice.
    const double cents = Clamp(1200.0 * std::log2(value / reference), -360.0, 360.0);
    (*f0)[i] = reference * std::pow(2.0, cents / 1200.0);
    (*confidence)[i] = Clamp(conf, 0.0, 1.0);
  }

  if (n >= 3) {
    std::vector<double> smooth(*f0);
    for (int i = 1; i < n - 1; ++i) {
      smooth[i] = std::exp(0.16 * std::log((*f0)[i - 1]) +
                           0.68 * std::log((*f0)[i]) +
                           0.16 * std::log((*f0)[i + 1]));
    }
    f0->swap(smooth);
  }

  std::vector<double> reliable;
  for (int i = 0; i < n; ++i) {
    if ((*confidence)[i] >= 0.12) reliable.push_back((*f0)[i]);
  }
  if (!reliable.empty()) reference = Median(reliable);
  *reference_f0 = reference;
  return true;
}

int ReflectIndex(int index, int length) {
  if (length <= 1) return 0;
  while (index < 0 || index >= length) {
    if (index < 0) index = -index;
    if (index >= length) index = 2 * length - 2 - index;
  }
  return index;
}

void BuildHarmonicEnvelope(const std::vector<double>& power, double f0, int fs,
                           int fft_size, std::vector<double>* envelope) {
  const int bins = fft_size / 2 + 1;
  envelope->assign(static_cast<size_t>(bins), 1e-12);
  const double bin_hz = fs / static_cast<double>(fft_size);
  if (!IsF0(f0) || bins < 4) {
    *envelope = power;
    return;
  }

  const int max_harmonic = std::max(2, static_cast<int>(std::floor(
      (fs * 0.5 - bin_hz) / f0)));
  std::vector<int> harmonic_bin;
  std::vector<double> harmonic_log;
  harmonic_bin.reserve(static_cast<size_t>(max_harmonic));
  harmonic_log.reserve(static_cast<size_t>(max_harmonic));

  for (int h = 1; h <= max_harmonic; ++h) {
    const double hz = h * f0;
    int center = static_cast<int>(std::llround(hz / bin_hz));
    if (center <= 0 || center >= bins) continue;
    const int search = std::max(1, static_cast<int>(std::llround(
        Clamp(f0 * 0.22, 35.0, 120.0) / bin_hz)));
    const int from = std::max(1, center - search);
    const int to = std::min(bins - 1, center + search);
    int peak = from;
    for (int b = from + 1; b <= to; ++b) {
      if (power[b] > power[peak]) peak = b;
    }
    // Average the peak and its immediate neighbors so one FFT-bin accident
    // cannot redefine a formant.
    double p = 0.0;
    double w = 0.0;
    for (int d = -1; d <= 1; ++d) {
      const int b = std::max(0, std::min(bins - 1, peak + d));
      const double weight = d == 0 ? 2.0 : 1.0;
      p += power[b] * weight;
      w += weight;
    }
    harmonic_bin.push_back(center);
    harmonic_log.push_back(std::log(std::max(1e-14, p / std::max(1.0, w))));
  }

  if (harmonic_bin.size() < 2) {
    *envelope = power;
    return;
  }

  // Interpolate the measured harmonic amplitudes over frequency. Unlike
  // CheapTrick, these points come directly from the waveform, so F1/F2 stay at
  // the frequencies that actually made the source sound like A/E/I/O/U.
  size_t right = 1;
  for (int b = 0; b < bins; ++b) {
    while (right < harmonic_bin.size() && b > harmonic_bin[right]) ++right;
    double log_value;
    if (b <= harmonic_bin.front()) {
      log_value = harmonic_log.front();
    } else if (right >= harmonic_bin.size()) {
      // Preserve the source spectral tilt rather than making the top end flat.
      const size_t n = harmonic_bin.size();
      const double dx = std::max(1, harmonic_bin[n - 1] - harmonic_bin[n - 2]);
      const double slope = Clamp((harmonic_log[n - 1] - harmonic_log[n - 2]) / dx,
                                 -0.025, 0.010);
      log_value = harmonic_log[n - 1] + slope * (b - harmonic_bin[n - 1]);
    } else {
      const size_t left = right - 1;
      const double denom = std::max(1, harmonic_bin[right] - harmonic_bin[left]);
      const double a = Clamp((b - harmonic_bin[left]) / denom, 0.0, 1.0);
      log_value = harmonic_log[left] * (1.0 - a) + harmonic_log[right] * a;
    }
    (*envelope)[b] = std::exp(log_value);
  }

  // Very small frequency smoothing removes corners from the piecewise-linear
  // harmonic envelope without moving broad formant peaks.
  const int radius = std::max(1, static_cast<int>(std::llround(45.0 / bin_hz)));
  std::vector<double> smooth(*envelope);
  for (int b = 0; b < bins; ++b) {
    double sum = 0.0, weight_sum = 0.0;
    for (int d = -radius; d <= radius; ++d) {
      const int k = std::max(0, std::min(bins - 1, b + d));
      const double weight = radius + 1 - std::abs(d);
      sum += std::log(std::max(1e-14, (*envelope)[k])) * weight;
      weight_sum += weight;
    }
    smooth[b] = std::exp(sum / std::max(1e-12, weight_sum));
  }
  envelope->swap(smooth);
}

bool AnalyzeRawEnvelope(const std::vector<double>& x, int fs,
                        const std::vector<double>& time_axis,
                        const std::vector<double>& f0,
                        int fft_size,
                        std::vector<std::vector<double>>* envelope) {
  if (!envelope || time_axis.size() != f0.size() || x.empty()) return false;
  const int frames = static_cast<int>(f0.size());
  const int bins = fft_size / 2 + 1;
  envelope->assign(static_cast<size_t>(frames),
                   std::vector<double>(static_cast<size_t>(bins), 1e-12));

  std::vector<double> fft_in(static_cast<size_t>(fft_size), 0.0);
  std::vector<fft_complex> fft_out(static_cast<size_t>(bins));
  fft_plan plan = fft_plan_dft_r2c_1d(
      fft_size, fft_in.data(), fft_out.data(), FFT_ESTIMATE);

  for (int frame = 0; frame < frames; ++frame) {
    std::fill(fft_in.begin(), fft_in.end(), 0.0);
    const double frame_f0 = IsF0(f0[frame]) ? f0[frame] : 220.0;
    const double seconds = Clamp(std::max(0.038, 4.5 / frame_f0), 0.038, 0.080);
    int window = static_cast<int>(std::llround(seconds * fs));
    window = std::max(256, std::min(window, fft_size - 2));
    const int center = static_cast<int>(std::llround(time_axis[frame] * fs));
    const int start = center - window / 2;

    double window_energy = 0.0;
    for (int n = 0; n < window; ++n) {
      const double w = 0.5 - 0.5 * std::cos(2.0 * kPi * n /
                                           std::max(1, window - 1));
      const int src = ReflectIndex(start + n, static_cast<int>(x.size()));
      fft_in[n] = x[src] * w;
      window_energy += w * w;
    }

    fft_execute(plan);
    std::vector<double> power(static_cast<size_t>(bins), 1e-14);
    const double normalization = std::max(1e-12, window_energy);
    for (int b = 0; b < bins; ++b) {
      const double re = fft_out[b][0];
      const double im = fft_out[b][1];
      power[b] = std::max(1e-14, (re * re + im * im) / normalization);
    }
    BuildHarmonicEnvelope(power, frame_f0, fs, fft_size, &(*envelope)[frame]);
  }

  fft_destroy_plan(plan);

  // Suppress isolated analysis-frame mistakes while retaining actual vowel
  // motion. Three-frame median in log-power does not move formant frequencies.
  if (frames >= 3) {
    std::vector<std::vector<double>> stabilized(*envelope);
    for (int i = 1; i < frames - 1; ++i) {
      for (int b = 0; b < bins; ++b) {
        std::vector<double> v = {
            std::log(std::max(1e-14, (*envelope)[i - 1][b])),
            std::log(std::max(1e-14, (*envelope)[i][b])),
            std::log(std::max(1e-14, (*envelope)[i + 1][b]))};
        stabilized[i][b] = std::exp(Median(v));
      }
    }
    envelope->swap(stabilized);
  }
  return true;
}

double SourcePosition(double u) {
  u = Clamp(u, 0.0, 1.0);
  // The outer 10% of the result traverses the source edges. Most added time is
  // spent inside the stable center of the selected vowel.
  constexpr double out_edge = 0.10;
  constexpr double src_edge = 0.18;
  if (u <= out_edge) return (u / out_edge) * src_edge;
  if (u >= 1.0 - out_edge) {
    const double a = (u - (1.0 - out_edge)) / out_edge;
    return (1.0 - src_edge) + a * src_edge;
  }
  const double a = (u - out_edge) / (1.0 - 2.0 * out_edge);
  return src_edge + a * (1.0 - 2.0 * src_edge);
}

double LogInterp(double a, double b, double t) {
  return std::exp(std::log(std::max(1e-14, a)) * (1.0 - t) +
                  std::log(std::max(1e-14, b)) * t);
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vitkkk_vocalstretcher_WorldVocoderEngine_nativeSynthesizeRawEnvelope(
    JNIEnv* env, jclass, jfloatArray mono_samples, jint sample_rate,
    jint target_frames) {
  if (!mono_samples || sample_rate <= 0 || target_frames <= 0) return nullptr;
  const jsize input_length = env->GetArrayLength(mono_samples);
  if (input_length < 256) return nullptr;

  std::vector<jfloat> input(static_cast<size_t>(input_length));
  env->GetFloatArrayRegion(mono_samples, 0, input_length, input.data());
  std::vector<double> x(static_cast<size_t>(input_length));
  for (int i = 0; i < input_length; ++i) x[i] = input[i];
  RemoveMean(&x);

  std::vector<double> time_axis;
  std::vector<double> f0;
  std::vector<double> periodicity;
  double reference_f0 = 0.0;
  if (!BuildPitch(x, sample_rate, &time_axis, &f0, &periodicity, &reference_f0)) {
    return nullptr;
  }
  const int frame_count = static_cast<int>(f0.size());

  CheapTrickOption size_option;
  InitializeCheapTrickOption(sample_rate, &size_option);
  size_option.f0_floor = kF0Floor;
  size_option.fft_size = GetFFTSizeForCheapTrick(sample_rate, &size_option);
  const int fft_size = size_option.fft_size;
  const int bins = fft_size / 2 + 1;

  std::vector<std::vector<double>> raw_envelope;
  if (!AnalyzeRawEnvelope(x, sample_rate, time_axis, f0, fft_size, &raw_envelope)) {
    return nullptr;
  }

  // Keep D4C only for harmonic-vs-noise character. The actual vowel identity
  // now comes from raw harmonic measurements, not CheapTrick.
  D4COption d4c;
  InitializeD4COption(&d4c);
  std::vector<std::vector<double>> aper(
      static_cast<size_t>(frame_count), std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> aper_ptr(static_cast<size_t>(frame_count));
  for (int i = 0; i < frame_count; ++i) aper_ptr[i] = aper[i].data();
  D4C(x.data(), input_length, sample_rate, time_axis.data(), f0.data(), frame_count,
      fft_size, &d4c, aper_ptr.data());

  // Form a central reference only for outlier clipping. Unlike v0.5.5, this is
  // NOT blended into every frame, so it cannot turn A into an averaged O/I.
  int anchor_from = std::max(0, frame_count / 5);
  int anchor_to = std::max(anchor_from + 1, frame_count - frame_count / 5);
  anchor_to = std::min(frame_count, anchor_to);
  std::vector<double> anchor_log(static_cast<size_t>(bins), 0.0);
  for (int b = 0; b < bins; ++b) {
    std::vector<double> values;
    for (int i = anchor_from; i < anchor_to; ++i) {
      values.push_back(std::log(std::max(1e-14, raw_envelope[i][b])));
    }
    anchor_log[b] = Median(values);
  }

  std::vector<std::vector<double>> safe_envelope(raw_envelope);
  const int gain_from = std::max(1, static_cast<int>(100.0 * fft_size / sample_rate));
  const int gain_to = std::min(bins - 1, static_cast<int>(5000.0 * fft_size / sample_rate));
  for (int i = 0; i < frame_count; ++i) {
    std::vector<double> gain_values;
    for (int b = gain_from; b <= gain_to; ++b) {
      gain_values.push_back(std::log(std::max(1e-14, raw_envelope[i][b])) - anchor_log[b]);
    }
    const double gain = Clamp(Median(gain_values), -1.0, 1.0);
    for (int b = 0; b < bins; ++b) {
      const double local = std::log(std::max(1e-14, raw_envelope[i][b]));
      // Only clip catastrophic envelope mistakes. Normal formant movement is
      // left at 100% instead of being pulled toward a synthetic median vowel.
      const double deviation = local - anchor_log[b] - gain;
      const double limit_db = b * sample_rate / static_cast<double>(fft_size) < 5500.0
                                  ? 9.0 : 12.0;
      const double limit = limit_db * std::log(10.0) / 10.0;  // power-domain dB
      safe_envelope[i][b] = std::exp(anchor_log[b] + gain +
                                     Clamp(deviation, -limit, limit));
    }
  }

  const double hop_samples = sample_rate * kFramePeriodMs / 1000.0;
  const int out_frames = std::max(
      2, static_cast<int>(std::ceil(target_frames / hop_samples)) + 2);
  std::vector<double> out_f0(static_cast<size_t>(out_frames));
  std::vector<std::vector<double>> out_spec(
      static_cast<size_t>(out_frames), std::vector<double>(static_cast<size_t>(bins)));
  std::vector<std::vector<double>> out_aper(
      static_cast<size_t>(out_frames), std::vector<double>(static_cast<size_t>(bins)));
  std::vector<const double*> out_spec_ptr(static_cast<size_t>(out_frames));
  std::vector<const double*> out_aper_ptr(static_cast<size_t>(out_frames));

  for (int j = 0; j < out_frames; ++j) {
    const double u = out_frames <= 1 ? 0.0 : j / static_cast<double>(out_frames - 1);
    const double pos = SourcePosition(u) * std::max(0, frame_count - 1);
    const int i0 = std::max(0, std::min(static_cast<int>(std::floor(pos)), frame_count - 1));
    const int i1 = std::max(0, std::min(i0 + 1, frame_count - 1));
    const double a = Clamp(pos - i0, 0.0, 1.0);

    out_f0[j] = LogInterp(f0[i0], f0[i1], a);
    const double p = Clamp(periodicity[i0] * (1.0 - a) + periodicity[i1] * a,
                           0.0, 1.0);
    for (int b = 0; b < bins; ++b) {
      out_spec[j][b] = LogInterp(safe_envelope[i0][b], safe_envelope[i1][b], a);
      const double raw_ap = aper[i0][b] * (1.0 - a) + aper[i1][b] * a;
      const double hz = b * sample_rate / static_cast<double>(fft_size);
      double cap = hz < 4500.0 ? 0.42 : (hz < 9000.0 ? 0.68 : 0.92);
      cap += (1.0 - p) * 0.10;
      out_aper[j][b] = Clamp(std::min(raw_ap, cap), 0.001, 0.96);
    }
    out_spec_ptr[j] = out_spec[j].data();
    out_aper_ptr[j] = out_aper[j].data();
  }

  std::vector<double> y(static_cast<size_t>(target_frames), 0.0);
  Synthesis(out_f0.data(), out_frames, out_spec_ptr.data(), out_aper_ptr.data(),
            fft_size, kFramePeriodMs, sample_rate, target_frames, y.data());

  const double mean = std::accumulate(y.begin(), y.end(), 0.0) /
                      std::max<size_t>(1, y.size());
  double peak = 1e-9;
  for (double& v : y) {
    v -= mean;
    peak = std::max(peak, std::abs(v));
  }
  const double safety = peak > 1.0 ? 0.98 / peak : 1.0;

  jfloatArray result = env->NewFloatArray(target_frames);
  if (!result) return nullptr;
  std::vector<jfloat> output(static_cast<size_t>(target_frames));
  for (int i = 0; i < target_frames; ++i) {
    output[i] = static_cast<jfloat>(Clamp(y[i] * safety, -1.0, 1.0));
  }
  env->SetFloatArrayRegion(result, 0, target_frames, output.data());

  __android_log_print(ANDROID_LOG_INFO, kLogTag,
      "Raw-envelope synth: input=%d target=%d fs=%d refF0=%.2f frames=%d fft=%d",
      input_length, target_frames, sample_rate, reference_f0, frame_count, fft_size);
  return result;
}
