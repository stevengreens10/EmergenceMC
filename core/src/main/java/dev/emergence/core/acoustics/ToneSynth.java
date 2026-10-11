package dev.emergence.core.acoustics;

/**
 * Plays a {@link Tone} as audio: 16-bit mono samples whose digital level follows the sound pressure where the listener
 * is ({@link #FULL_SCALE_PA} rms plays at {@link #HEADROOM} digital rms, leaving room for the peaks). The waveform is tabulated per tone (1024 points a period) and read at
 * the tone's frequency; level and frequency glide to new values over ~20 ms, so updates do not click.
 */
public final class ToneSynth {
    /** The rms sound pressure [Pa] that plays at {@link #HEADROOM} digital rms (94 dB). */
    public static final double FULL_SCALE_PA = 1.0, HEADROOM = 0.5;
    private static final int TABLE = 1024;
    private final int rate;
    private float[] table = new float[TABLE + 1], nextTable;
    private volatile double targetFreq, targetPa;
    private volatile Tone pending;
    private double freq, pa, phase;

    public ToneSynth(int sampleRate) {
        this.rate = sampleRate;
    }

    /** A new tone (null: fade to silence) and the rms pressure [Pa] it makes at the listener. Any thread. */
    public void set(Tone tone, double pressureAtListener) {
        if (tone != null) {
            pending = tone;
            targetFreq = tone.frequency();
        }
        targetPa = tone == null ? 0 : pressureAtListener;
    }

    /** Whether it has faded to silence. */
    public boolean silent() {
        return targetPa == 0 && pa < 1e-6;
    }

    /** Fills {@code out} with little-endian 16-bit samples (out.length / 2 of them). */
    public void render(byte[] out) {
        Tone t = pending;
        if (t != null) {
            pending = null;
            float[] tab = new float[TABLE + 1];
            for (int i = 0; i <= TABLE; i++) tab[i] = (float) t.sample(i / (double) TABLE);
            nextTable = tab;
        }
        if (nextTable != null && (pa < 1e-6 || freq == 0)) {
            table = nextTable;
            nextTable = null;
        }
        double glide = 1 - Math.exp(-1.0 / (0.02 * rate));
        int n = out.length / 2;
        for (int k = 0; k < n; k++) {
            // a new waveform takes over at the top of a period (no jump in the middle of one)
            if (nextTable != null && phase < 1e-3 + freq / rate) {
                table = nextTable;
                nextTable = null;
            }
            freq += (targetFreq - freq) * glide;
            pa += (targetPa - pa) * glide;
            double x = phase * TABLE;
            int i = (int) x;
            double v = table[i] + (x - i) * (table[i + 1] - table[i]);
            phase += freq / rate;
            phase -= Math.floor(phase);
            double s = Math.max(-1, Math.min(1, v * pa / FULL_SCALE_PA * HEADROOM));
            short q = (short) Math.round(s * 32767);
            out[2 * k] = (byte) q;
            out[2 * k + 1] = (byte) (q >> 8);
        }
    }
}
