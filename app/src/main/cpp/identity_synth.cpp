#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
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
constexpr char kLogTag[] = "VocalIdentity";

struct PitchEstimate {
  double f0 = 0.0;
  double confidence = 0.0;
};

double Clamp(double v, double lo, double hi) {
  return std::max(lo, std::min(hi, v));
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

bool IsF0(double f) {
  return std::isfinite(f) && f >= kF0Floor && f <= kF0Ceil;
}

void RemoveMean(std::vector<double>* x) {
  if (x == nullptr || x->empty()) return;
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

  // Prefer the earliest strong peak. For voiced audio this is much less likely
  // to pick 1/2 F0 than simply taking the highest autocorrelation value.
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

bool BuildIdentityF0(const std::vector<double>& x, int fs,
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
      const double folded_dio = FoldOctave(reference, global_auto.f0);
      const double cents = std::abs(1200.0 * std::log2(folded_dio / global_auto.f0));
      reference = cents < 320.0
                      ? std::exp(0.35 * std::log(folded_dio) +
                                 0.65 * std::log(global_auto.f0))
                      : global_auto.f0;
    }
  }
  if (!IsF0(reference)) return false;

  f0->assign(static_cast<size_t>(n), reference);
  confidence->assign(static_cast<size_t>(n), 0.08);

  for (int i = 0; i < n; ++i) {
    const int center = static_cast<int>(std::llround((*time_axis)[i] * fs));
    const PitchEstimate local = AutoPitch(x, fs, center, reference, true);
    double value = reference;
    double conf = 0.08;

    const bool local_ok = IsF0(local.f0) && local.confidence >= 0.10;
    const bool dio_ok = IsF0(dio[i]);
    if (local_ok) {
      value = FoldOctave(local.f0, reference);
      conf = local.confidence;
    }
    if (dio_ok) {
      const double d = FoldOctave(dio[i], reference);
      if (local_ok) {
        const double w = Clamp(local.confidence, 0.25, 0.85);
        value = std::exp(std::log(d) * (1.0 - w) + std::log(value) * w);
        conf = std::max(conf, 0.35);
      } else {
        value = d;
        conf = 0.30;
      }
    }

    // Chromatic samples are one sustained note. Preserve vibrato/inflection but
    // never allow an octave or huge tracker jump to turn the voice into Mickey.
    const double cents = Clamp(1200.0 * std::log2(value / reference), -420.0, 420.0);
    (*f0)[i] = reference * std::pow(2.0, cents / 1200.0);
    (*confidence)[i] = Clamp(conf, 0.0, 1.0);
  }

  if (n >= 3) {
    std::vector<double> smooth(*f0);
    for (int i = 1; i < n - 1; ++i) {
      const double a = std::log((*f0)[i - 1]);
      const double b = std::log((*f0)[i]);
      const double c = std::log((*f0)[i + 1]);
      smooth[i] = std::exp(0.18 * a + 0.64 * b + 0.18 * c);
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

double ElasticSourcePosition(double u) {
  u = Clamp(u, 0.0, 1.0);
  constexpr double out_edge = 0.12;
  constexpr double src_edge = 0.18;
  if (u <= out_edge) return (u / out_edge) * src_edge;
  if (u >= 1.0 - out_edge) {
    const double local = (u - (1.0 - out_edge)) / out_edge;
    return (1.0 - src_edge) + local * src_edge;
  }
  const double local = (u - out_edge) / (1.0 - 2.0 * out_edge);
  return src_edge + local * (1.0 - 2.0 * src_edge);
}

double LogInterp(double a, double b, double t) {
  return std::exp(std::log(std::max(1e-12, a)) * (1.0 - t) +
                  std::log(std::max(1e-12, b)) * t);
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_vitkkk_vocalstretcher_WorldVocoderEngine_nativeSynthesizeIdentityLocked(
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
  if (!BuildIdentityF0(x, sample_rate, &time_axis, &f0, &periodicity,
                       &reference_f0)) {
    return nullptr;
  }
  const int frame_count = static_cast<int>(f0.size());

  CheapTrickOption cheap;
  InitializeCheapTrickOption(sample_rate, &cheap);
  cheap.f0_floor = kF0Floor;
  const int fft_size = GetFFTSizeForCheapTrick(sample_rate, &cheap);
  const int bins = fft_size / 2 + 1;

  std::vector<std::vector<double>> spec(
      static_cast<size_t>(frame_count), std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> spec_ptr(static_cast<size_t>(frame_count));
  for (int i = 0; i < frame_count; ++i) spec_ptr[i] = spec[i].data();
  CheapTrick(x.data(), input_length, sample_rate, time_axis.data(), f0.data(),
             frame_count, &cheap, spec_ptr.data());

  D4COption d4c;
  InitializeD4COption(&d4c);
  std::vector<std::vector<double>> aper(
      static_cast<size_t>(frame_count), std::vector<double>(static_cast<size_t>(bins)));
  std::vector<double*> aper_ptr(static_cast<size_t>(frame_count));
  for (int i = 0; i < frame_count; ++i) aper_ptr[i] = aper[i].data();
  D4C(x.data(), input_length, sample_rate, time_axis.data(), f0.data(), frame_count,
      fft_size, &d4c, aper_ptr.data());

  // Build a stable spectral/formant identity anchor from the central voiced
  // frames. A median in log-spectrum space is resistant to one bad WORLD frame.
  int anchor_from = std::max(0, frame_count / 5);
  int anchor_to = std::min(frame_count, frame_count - frame_count / 5);
  if (anchor_to <= anchor_from) {
    anchor_from = 0;
    anchor_to = frame_count;
  }

  std::vector<double> anchor_log(static_cast<size_t>(bins), 0.0);
  std::vector<double> anchor_aper(static_cast<size_t>(bins), 0.0);
  for (int b = 0; b < bins; ++b) {
    std::vector<double> logs;
    std::vector<double> aps;
    logs.reserve(static_cast<size_t>(anchor_to - anchor_from));
    aps.reserve(static_cast<size_t>(anchor_to - anchor_from));
    for (int i = anchor_from; i < anchor_to; ++i) {
      if (periodicity[i] < 0.08 && anchor_to - anchor_from > 4) continue;
      logs.push_back(std::log(std::max(1e-12, spec[i][b])));
      aps.push_back(Clamp(aper[i][b], 0.001, 0.999));
    }
    if (logs.empty()) {
      for (int i = anchor_from; i < anchor_to; ++i) {
        logs.push_back(std::log(std::max(1e-12, spec[i][b])));
        aps.push_back(Clamp(aper[i][b], 0.001, 0.999));
      }
    }
    anchor_log[b] = Median(logs);
    anchor_aper[b] = Median(aps);
  }

  // Separate overall loudness motion from spectral-shape motion. We preserve
  // the former freely, while constraining the latter so A cannot morph into I/O.
  std::vector<double> frame_gain(static_cast<size_t>(frame_count), 0.0);
  std::vector<std::vector<double>> locked_log(
      static_cast<size_t>(frame_count), std::vector<double>(static_cast<size_t>(bins)));

  const int gain_bin_from = std::max(1, static_cast<int>(80.0 * fft_size / sample_rate));
  const int gain_bin_to = std::min(bins - 1,
      static_cast<int>(5000.0 * fft_size / sample_rate));

  for (int i = 0; i < frame_count; ++i) {
    std::vector<double> gains;
    gains.reserve(static_cast<size_t>(std::max(1, gain_bin_to - gain_bin_from)));
    for (int b = gain_bin_from; b <= gain_bin_to; ++b) {
      gains.push_back(std::log(std::max(1e-12, spec[i][b])) - anchor_log[b]);
    }
    const double gain = Clamp(Median(gains), -0.80, 0.80);  // roughly +/-7 dB.
    frame_gain[i] = gain;

    for (int b = 0; b < bins; ++b) {
      const double hz = b * sample_rate / static_cast<double>(fft_size);
      const double local = std::log(std::max(1e-12, spec[i][b]));
      double shape = local - anchor_log[b] - gain;
      const double db_limit = hz < 5000.0 ? 3.0 : (hz < 10000.0 ? 4.5 : 6.0);
      const double limit = db_limit * std::log(10.0) / 20.0;
      shape = Clamp(shape, -limit, limit);
      // 60% of the safe local shape motion, 40% identity anchor.
      locked_log[i][b] = anchor_log[b] + gain + 0.60 * shape;
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
    const double pos = ElasticSourcePosition(u) * std::max(0, frame_count - 1);
    const int i0 = std::max(0, std::min(static_cast<int>(std::floor(pos)), frame_count - 1));
    const int i1 = std::max(0, std::min(i0 + 1, frame_count - 1));
    const double a = Clamp(pos - i0, 0.0, 1.0);

    out_f0[j] = LogInterp(f0[i0], f0[i1], a);
    const double p = Clamp(periodicity[i0] * (1.0 - a) + periodicity[i1] * a,
                           0.0, 1.0);

    for (int b = 0; b < bins; ++b) {
      const double log_s = locked_log[i0][b] * (1.0 - a) + locked_log[i1][b] * a;
      out_spec[j][b] = std::exp(log_s);

      const double raw = aper[i0][b] * (1.0 - a) + aper[i1][b] * a;
      const double stable = anchor_aper[b];
      double ap = 0.35 * raw + 0.65 * stable;
      const double hz = b * sample_rate / static_cast<double>(fft_size);
      double cap = hz < 4500.0 ? 0.44 : (hz < 9000.0 ? 0.68 : 0.91);
      cap += (1.0 - p) * 0.10;
      out_aper[j][b] = Clamp(std::min(ap, cap), 0.001, 0.96);
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
      "Identity synth: input=%d target=%d fs=%d refF0=%.2f frames=%d",
      input_length, target_frames, sample_rate, reference_f0, frame_count);
  return result;
}
