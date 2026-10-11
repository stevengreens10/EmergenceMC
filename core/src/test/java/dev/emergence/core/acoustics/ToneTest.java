package dev.emergence.core.acoustics;

import dev.emergence.core.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What a moving body sounds like, against the analytic dipole. */
class ToneTest {
    static final SoundField AIR = SoundField.air(293.15, 101_325);

    static DipoleRadiator moving(java.util.function.DoubleUnaryOperator u, double dt, double span) {
        var r = new DipoleRadiator(Vec3.ZERO, new Vec3(1, 0, 0), 3e-3);
        for (double t = 0; t <= span + 1e-12; t += dt) r.record(t, u.applyAsDouble(t));
        return r;
    }

    /** A sinusoid: its frequency, a pure first harmonic, and p_rms(1 m) = ρ·M·ω²·U0/(√2·4πc). */
    @Test
    void sineVelocity() {
        double f = 440, w = 2 * Math.PI * f, u0 = 0.01;
        var r = moving(t -> u0 * Math.sin(w * t), 2e-5, 0.3);
        Tone tone = Tone.of(r, 0.3, 0.25, AIR.density(), AIR.soundSpeed());
        assertNotNull(tone);
        double expect = AIR.density() * 3e-3 * w * w * u0 / (Math.sqrt(2) * 4 * Math.PI * AIR.soundSpeed());
        System.out.printf("tone: %.2f Hz, %.4g Pa at 1 m (dipole %.4g Pa), first harmonic %.3f, second %.3f%n", tone.frequency(),
                tone.rmsPressure(), expect, tone.amplitude()[0], tone.amplitude()[1]);
        assertEquals(f, tone.frequency(), 0.005 * f);
        assertEquals(expect, tone.rmsPressure(), 0.02 * expect);
        assertEquals(Math.sqrt(2), tone.amplitude()[0], 0.02, "a unit-rms sine");
        assertTrue(tone.amplitude()[1] < 0.02 && tone.amplitude()[2] < 0.02, "no overtones");
    }

    /** A square wave of velocity: odd harmonics only (the pressure, Ü, is the sharp turnarounds). */
    @Test
    void squareVelocityHasOddHarmonics() {
        double f = 250;
        var r = moving(t -> Math.sin(2 * Math.PI * f * t) >= 0 ? 0.01 : -0.01, 1e-5, 0.3);
        Tone tone = Tone.of(r, 0.3, 0.25, AIR.density(), AIR.soundSpeed());
        assertNotNull(tone);
        assertEquals(f, tone.frequency(), 0.01 * f);
        assertTrue(tone.amplitude()[2] > 5 * tone.amplitude()[1], "the third harmonic, not the second");
    }

    /** A still body is silent. */
    @Test
    void stillBodyIsSilent() {
        assertNull(Tone.of(moving(t -> 0, 1e-4, 0.3), 0.3, 0.25, AIR.density(), AIR.soundSpeed()));
    }

    /** The synthesiser plays the tone's frequency at the level its pressure asks for. */
    @Test
    void synthesiserPlaysTheTone() {
        double f = 440;
        var r = moving(t -> 0.01 * Math.sin(2 * Math.PI * f * t), 2e-5, 0.3);
        Tone tone = Tone.of(r, 0.3, 0.25, AIR.density(), AIR.soundSpeed());
        ToneSynth synth = new ToneSynth(44_100);
        synth.set(tone, 0.5); // 0.5 Pa at the listener
        byte[] buf = new byte[2 * 44_100];
        synth.render(buf); // the glide settles
        synth.render(buf);
        int crossings = 0;
        double ms = 0, prev = 0;
        for (int k = 0; k < 44_100; k++) {
            double v = (short) ((buf[2 * k] & 0xff) | (buf[2 * k + 1] << 8)) / 32767.0;
            if (k > 0 && prev < 0 && v >= 0) crossings++;
            prev = v;
            ms += v * v / 44_100;
        }
        System.out.printf("synth: %d rising crossings in 1 s, rms %.3f%n", crossings, Math.sqrt(ms));
        assertEquals(440, crossings, 2);
        assertEquals(0.5 / ToneSynth.FULL_SCALE_PA * ToneSynth.HEADROOM, Math.sqrt(ms), 0.01);
    }
}
