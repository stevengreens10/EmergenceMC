package dev.emergence.core.acoustics;

/**
 * What a sounding body's recent motion sounds like, for playback: its fundamental frequency, the shape of the pressure
 * it radiates (the far-field pressure follows the second derivative of its velocity, Ü), and how loud that is.
 * <p>
 * The velocity history is resampled uniformly; the fundamental is the first strong peak of its autocorrelation
 * (between 5 Hz and 5 kHz); the pressure waveform is a Fourier series of up to {@link #HARMONICS} harmonics below 4 kHz
 * (a buzzer's click train keeps some edge) — the
 * velocity's harmonics, from a DFT over the last whole periods, each weighted by (2πkf)² — so it is band-limited and
 * free of the noise twice differencing the samples would bring. The level is the rms far-field pressure 1 m away on the
 * axis, ρ·M·Ü_rms/(4πc).
 *
 * @param frequency fundamental [Hz]
 * @param rmsPressure rms sound pressure 1 m away on the axis [Pa]
 * @param amplitude per harmonic k = 1…: amplitude of the pressure waveform, normalised to a unit rms waveform
 * @param phase per harmonic: phase [rad] (cosine series)
 */
public record Tone(double frequency, double rmsPressure, double[] amplitude, double[] phase) {
    public static final int HARMONICS = 64;

    /** Sound pressure level [dB re 20 µPa] at distance r [m] and angle θ off the axis (far field): p·|cos θ|/r. */
    public double level(double r, double cosTheta) {
        double p = rmsPressure * Math.abs(cosTheta) / Math.max(r, 0.05);
        return 20 * Math.log10(Math.max(p, 1e-12) / 20e-6);
    }

    /** One sample of the unit-rms waveform at phase φ (in periods). */
    public double sample(double phi) {
        double v = 0, w = 2 * Math.PI * phi;
        for (int k = 0; k < amplitude.length; k++) v += amplitude[k] * Math.cos((k + 1) * w + phase[k]);
        return v;
    }

    /**
     * The tone of a radiator's motion over the last {@code window} seconds before {@code now}, in air of density ρ and
     * sound speed c; null if it was still, too short, or not periodic.
     */
    public static Tone of(DipoleRadiator r, double now, double window, double rho, double c) {
        double[][] h = r.history(now - window);
        double[] ts = h[0], us = h[1];
        int m = ts.length;
        if (m < 16) return null;
        double t0 = ts[0], t1 = ts[m - 1], span = t1 - t0;
        if (span < 0.004) return null;
        int n = 1024;
        double dt = span / (n - 1);
        double[] u = new double[n];
        int j = 0;
        double mean = 0;
        for (int i = 0; i < n; i++) {
            double t = t0 + i * dt;
            while (j < m - 2 && ts[j + 1] < t) j++;
            double f = (t - ts[j]) / Math.max(ts[j + 1] - ts[j], 1e-300);
            u[i] = us[j] + Math.max(0, Math.min(1, f)) * (us[j + 1] - us[j]);
            mean += u[i] / n;
        }
        double var = 0;
        for (int i = 0; i < n; i++) {
            u[i] -= mean;
            var += u[i] * u[i] / n;
        }
        if (var < 1e-20) return null;
        // fundamental: first autocorrelation peak above 0.4 after the curve first falls below zero (5 Hz – 5 kHz)
        int minLag = Math.max(1, (int) Math.floor(1.0 / (5000 * dt))), maxLag = Math.min(n / 2, (int) Math.ceil(1.0 / (5 * dt)));
        if (maxLag <= minLag + 2) return null;
        double[] ac = new double[maxLag + 2];
        for (int lag = minLag; lag <= maxLag + 1; lag++) {
            double s = 0;
            for (int i = 0; i + lag < n; i++) s += u[i] * u[i + lag];
            ac[lag] = s / ((n - lag) * var);
        }
        int period = -1;
        boolean below = false;
        for (int lag = minLag + 1; lag <= maxLag; lag++) {
            if (ac[lag] < 0) below = true;
            if (below && ac[lag] > 0.4 && ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1]) {
                period = lag;
                break;
            }
        }
        if (period < 0) return null;
        // refine the period by a parabola through the peak
        double y0 = ac[period - 1], y1 = ac[period], y2 = ac[period + 1], den = y0 - 2 * y1 + y2;
        double p = period + (den != 0 ? 0.5 * (y0 - y2) / den : 0);
        double freq = 1 / (p * dt);
        // the velocity's harmonics over the last whole periods
        int periods = (int) Math.floor((n - 1) / p);
        if (periods < 1) return null;
        int len = (int) Math.round(periods * p);
        int start = n - 1 - len;
        double[] amp = new double[HARMONICS], ph = new double[HARMONICS];
        double ms = 0;
        for (int k = 1; k <= HARMONICS; k++) {
            if (k * freq > Math.min(0.45 / dt, 4000)) break;
            double re = 0, im = 0;
            for (int i = 0; i < len; i++) {
                double w = 2 * Math.PI * k * i / p;
                re += u[start + i] * Math.cos(w);
                im -= u[start + i] * Math.sin(w);
            }
            re *= 2.0 / len;
            im *= 2.0 / len;
            double uk = Math.hypot(re, im), wk = 2 * Math.PI * k * freq;
            // Ü's harmonic: −ω²·U_k (a phase of π, which a cosine series carries as its sign)
            amp[k - 1] = wk * wk * uk;
            ph[k - 1] = Math.atan2(im, re) + Math.PI;
            ms += 0.5 * amp[k - 1] * amp[k - 1];
        }
        if (ms <= 0) return null;
        double rms = Math.sqrt(ms);
        for (int k = 0; k < HARMONICS; k++) amp[k] /= rms;
        return new Tone(freq, rho * r.moment() * rms / (4 * Math.PI * c), amp, ph);
    }
}
