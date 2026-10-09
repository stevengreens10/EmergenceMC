package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.EventSource;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Geiger–Müller tube: a gas-filled cylinder with a thin anode wire. Ionising radiation that interacts in it (a
 * Poisson process of rate λ = background + Σ sources' activities × geometric coupling × attenuation × intrinsic
 * efficiency) starts a self-sustaining avalanche only if the anode is above the starting voltage; the discharge then
 * holds the tube near its sustaining voltage, passing I = (V − V_ext)/R_on, until the anode circuit's capacitance has
 * drained down to the extinction voltage V_ext, when the quench gas stops it. The external circuit recharges it through the anode resistor, and a hit arriving before the voltage has
 * recovered is lost: dead time is not a parameter but follows from that recovery. Each discharge pushes a few
 * nanocoulombs through the cathode resistor — a pulse a counter can count. Hits are drawn from a seeded random
 * stream, so a run is reproducible.
 */
public final class GeigerTube implements EventSource {
    /** A source term of the hit rate: activity of a source's nuclide × weight [counts per decay]. */
    public record Coupling(RadioactiveSource source, int nuclide, double weight) {}

    private final Node anode, cathode, thermal;
    private final double vStart, vStop, gOn, gOff = 1e-13, background;
    private static final double HOLD = 1e-4;
    private final List<Coupling> couplings = new ArrayList<>();
    private final SplittableRandom random;
    private boolean on, stopDue;
    private double nextHit = Double.NaN;
    private long discharges, lost;

    /**
     * @param startVoltage voltage needed to start an avalanche [V]
     * @param stopVoltage  voltage at which the quenched discharge dies [V]
     * @param onResistance resistance of the discharge [Ω]
     * @param background   background count rate [1/s]
     * @param seed         seed of the tube's random stream
     */
    public GeigerTube(Node anode, Node cathode, double startVoltage, double stopVoltage, double onResistance, double background,
                      long seed, Node thermal) {
        this.anode = anode;
        this.cathode = cathode;
        this.vStart = startVoltage;
        this.vStop = stopVoltage;
        this.gOn = 1 / onResistance;
        this.background = background;
        this.random = new SplittableRandom(seed);
        this.thermal = thermal;
    }

    public GeigerTube couple(RadioactiveSource source, int nuclide, double weight) {
        if (weight > 0) couplings.add(new Coupling(source, nuclide, weight));
        return this;
    }

    public List<Coupling> couplings() {
        return couplings;
    }

    /** Expected interaction rate [1/s] at the current state. */
    public double hitRate(StateView s) {
        double r = background;
        for (Coupling c : couplings) r += c.source().activity(s, c.nuclide()) * c.weight();
        return r;
    }

    @Override
    public void load(LoadContext ctx) {
        double v = ctx.x(anode.index()) - ctx.x(cathode.index());
        double g = on ? gOn : gOff, i = on ? gOn * (v - vStop) : gOff * v;
        ctx.addFlow(anode, cathode, i);
        ctx.stampConductance(anode, cathode, g);
        if (thermal != null) {
            ctx.addF(thermal.index(), -i * v);
            ctx.addJ(thermal.index(), anode.index(), -(i + g * v));
            ctx.addJ(thermal.index(), cathode.index(), i + g * v);
        }
    }

    @Override
    public double nextEvent(double time) {
        if (stopDue) return time;
        return Double.isNaN(nextHit) ? time : nextHit;
    }

    @Override
    public boolean fireEvent(StateView s, double time) {
        boolean changed = false;
        if (stopDue) {
            on = false;
            stopDue = false;
            changed = true;
        }
        if (Double.isNaN(nextHit)) {
            nextHit = time + exponential(hitRate(s));
            return changed;
        }
        if (time >= nextHit) {
            double v = s.x(anode) - s.x(cathode);
            if (!on && v >= vStart) {
                on = true;
                discharges++;
                changed = true;
            } else {
                lost++;
            }
            nextHit = time + exponential(hitRate(s));
        }
        return changed;
    }

    private double exponential(double rate) {
        return rate <= 0 ? Double.POSITIVE_INFINITY : -Math.log(1 - random.nextDouble()) / rate;
    }

    @Override
    public void acceptStep(StateView s, double time, double dt) {
        // the discharge dies when its current falls below the holding current (100 µA): with a large enough anode
        // ("quench") resistor the supply alone cannot sustain it; with too small a one the tube keeps conducting
        if (on && gOn * (s.x(anode) - s.x(cathode) - vStop) < HOLD) stopDue = true;
    }

    /** Avalanches so far (the counts). */
    public long discharges() {
        return discharges;
    }

    /** Interactions that arrived while the tube was recovering (dead-time losses). */
    public long lost() {
        return lost;
    }

    public boolean conducting() {
        return on;
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        double v = s.x(anode) - s.x(cathode);
        return (on ? gOn * (v - vStop) : gOff * v) * v;
    }
}
