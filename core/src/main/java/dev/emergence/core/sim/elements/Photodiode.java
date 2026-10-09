package dev.emergence.core.sim.elements;

import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;
import dev.emergence.core.units.PhysicalConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Silicon photodiode (or, with a large area, a solar cell): a PN junction that turns absorbed photons into
 * current. The terminal current anode → cathode is the Shockley diode current minus the photocurrent:
 * <pre>
 *   i = I_s(e^{v/nV_T} − 1) − R(λ)·P_in,        R(λ) = η_q(λ)·λ / 1240 nm   [A/W]
 * </pre>
 * with silicon's internal quantum efficiency η_q ≈ 0.8 across 550–950 nm, falling in the blue (surface
 * recombination) and to zero beyond the band edge (1.12 eV, ~1107 nm). The saturation current scales with the
 * junction area. P_in is the optical power arriving: from each coupled LED, its emitted power times a geometric
 * coupling factor (radiant intensity × projected solid angle × visibility, which may depend on a rotor's angle
 * when a disk or vane can interrupt the beam), plus daylight on the sensitive face. In photoconductive use (reverse
 * biased) the current is proportional to the light; open-circuited it develops ≈ 0.4–0.6 V (photovoltaic), so a
 * large-area junction is a solar cell. The absorbed light is external power entering the network; what is not
 * delivered electrically heats the part.
 */
public final class Photodiode implements Element {
    /** A coupled source: an LED, the fraction of its emitted power that reaches this receiver as a function of
     *  the given body angle (null = fixed geometry), and the angle unknown's index (or −1). */
    record Source(Led led, DoubleUnaryOperator coupling, AngleTracker angle) {
    }

    private final Node a, k, thermal;
    private final double is, n, area;
    private final List<Source> sources = new ArrayList<>();
    private double ambientWatts;
    private double ambientResponsivity = 0.5;

    public Photodiode(Node anode, Node cathode, double area, Node thermal) {
        this.a = anode;
        this.k = cathode;
        this.thermal = thermal;
        this.area = area;
        this.n = 1.0;
        this.is = 1e-14 * Math.max(area / 1e-6, 1e-3); // 10 fA per mm² of junction
    }

    /** Silicon responsivity [A/W] at a wavelength [nm]. */
    public static double responsivity(double nm) {
        double qe;
        if (nm >= 1107) qe = 0;
        else if (nm > 950) qe = 0.8 * (1107 - nm) / 157;
        else if (nm >= 550) qe = 0.8;
        else if (nm > 350) qe = 0.8 * (nm - 350) / 200;
        else qe = 0;
        return qe * nm / 1239.84;
    }

    /** Couples an LED: {@code coupling(θ)} is the fraction of its light arriving here (θ: the angle of {@code angle}'s body). */
    public void addSource(Led led, DoubleUnaryOperator coupling, AngleTracker angle) {
        sources.add(new Source(led, coupling, angle));
    }

    /** Daylight (or other broad-band light) arriving on the sensitive face [W], absorbed with ~0.5 A/W (sunlight on silicon). */
    public void setAmbientPower(double watts) {
        this.ambientWatts = Math.max(0, watts);
    }

    public double area() {
        return area;
    }

    @Override
    public void load(LoadContext ctx) {
        double v = ctx.x(a.index()) - ctx.x(k.index());
        double vt = PhysicalConstants.K_OVER_Q * 300, x = v / (n * vt);
        double e = Math.exp(Math.min(x, 60)), id = is * (e - 1), gd = is * e / (n * vt);
        if (x > 60) id += gd * (v - 60 * n * vt);
        ctx.addFlow(a, k, id);
        ctx.stampConductance(a, k, gd);
        // photocurrent flows cathode → anode inside the junction (against the diode current)
        double iph = ambientResponsivity * ambientWatts;
        for (Source s : sources) {
            double r = responsivity(1239.84 / s.led.photonEnergyEv());
            double c = s.coupling.applyAsDouble(s.angle == null ? 0 : ctx.x(s.angle.angleIndex()));
            double[] il = s.led.currentAndSlope(ctx);
            double p = s.led.photonEnergyEv() * s.led.quantumEfficiency();
            if (il[0] <= 0) continue;
            iph += r * c * p * il[0];
            // ∂i_ph/∂(LED junction voltage)
            ctx.addFlowJ(k, a, s.led.junctionIndex(), r * c * p * il[1]);
            if (s.angle != null) {
                double h = 1e-4, th = ctx.x(s.angle.angleIndex());
                double dc = (s.coupling.applyAsDouble(th + h) - c) / h;
                ctx.addFlowJ(k, a, s.angle.angleIndex(), r * dc * p * il[0]);
            }
        }
        ctx.addFlow(k, a, iph);
        if (thermal != null) {
            // absorbed light minus the electrical power delivered becomes heat: P_heat = P_in − (−v·i) with i the
            // terminal current a → k (delivered power is −v·i when the cell drives a load)
            double pin = pinFrom(ctx);
            double i = id - iph;
            ctx.addF(thermal.index(), -(pin + v * i));
        }
    }

    private double pinFrom(StateView s) {
        double pin = ambientWatts;
        for (Source src : sources) {
            double c = src.coupling.applyAsDouble(src.angle == null ? 0 : s.x(src.angle.angleIndex()));
            pin += c * src.led.opticalPower(s);
        }
        return pin;
    }

    /** Optical power arriving [W]. */
    public double incidentPower(StateView s) {
        return pinFrom(s);
    }

    /** Terminal current anode → cathode [A] (negative when light drives current out of the anode). */
    public double current(StateView s) {
        double v = s.across(a, k), vt = PhysicalConstants.K_OVER_Q * 300;
        double id = is * (Math.exp(Math.min(v / (n * vt), 60)) - 1);
        double iph = ambientResponsivity * ambientWatts;
        for (Source src : sources) {
            double c = src.coupling.applyAsDouble(src.angle == null ? 0 : s.x(src.angle.angleIndex()));
            iph += responsivity(1239.84 / src.led.photonEnergyEv()) * c * src.led.opticalPower(s);
        }
        return id - iph;
    }

    @Override
    public double externalPower(StateView s) {
        return incidentPower(s);
    }

    @Override
    public double untrackedLoss(StateView s) {
        if (thermal != null) return 0;
        return incidentPower(s) + s.across(a, k) * current(s);
    }
}
