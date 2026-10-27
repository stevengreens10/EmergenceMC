package dev.emergence.core.digital;

import java.util.List;

/**
 * Crystal clock oscillator module (manufactured: a quartz crystal, its sustaining amplifier and an output buffer in
 * one can): a square wave at its frequency [Hz] on its output, with the crystal's tolerance (its error in parts per
 * million) and the module's supply current, about 1 mA at 5 V whatever the frequency (its amplifier runs anyway),
 * plus its output's switching. It starts 1 ms after power comes (the crystal's oscillation building up).
 */
public final class ClockOscillator implements DigitalComponent {
    private final double half;
    private boolean level;
    private long cycles;

    /** @param frequency [Hz]; @param ppm its error from nominal (crystal tolerance), parts per million */
    public ClockOscillator(double frequency, double ppm) {
        this.half = 0.5 / (frequency * (1 + ppm * 1e-6));
    }

    @Override
    public List<String> inputs() {
        return List.of();
    }

    @Override
    public List<String> outputs() {
        return List.of("out");
    }

    @Override
    public void input(int k, boolean level, double t, Ctx ctx) {
    }

    @Override
    public void powerUp(double t, Ctx ctx) {
        level = false;
        ctx.drive(0, false, 0);
        ctx.wakeAt(t + 1e-3);
    }

    @Override
    public void wake(double t, Ctx ctx) {
        level = !level;
        if (level) cycles++;
        ctx.drive(0, level, 0);
        ctx.wakeAt(t + half);
    }

    @Override
    public boolean output(int k) {
        return level;
    }

    @Override
    public double energyPerEdge(double v) {
        return 0;
    }

    @Override
    public double leakage(double v) {
        return 1e-3 * Math.max(0, v) / 5;
    }

    /** Rising edges given since power came. */
    public long cycles() {
        return cycles;
    }
}
