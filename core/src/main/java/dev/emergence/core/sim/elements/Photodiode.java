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

    /**
     * A body in view: its thermal node, g [m²] and the surroundings' temperature; the radiation it sends here net of
     * what this junction sends back (at the surroundings' temperature, as its own radiation element has it) is
     * g·σ(T⁴ − T_a⁴), and the photocurrent g·(f(T) − f(T_a)) (see {@link #addHotSource}).
     */
    record HotSource(Node thermal, double g, double ambient) {
    }

    private final List<HotSource> hot = new ArrayList<>();

    /**
     * A hot body whose light reaches this junction by way of mirrors, lenses or glass, band by band: g[b] [m²] is the
     * coupling in wavelength band b (its emissivity and surface times the fraction of its light in that band the optics
     * deliver here), so that g[b]·(F_b(T) − F_b(T_a)) arrives in the band (see {@link #BAND_EDGES}).
     */
    record SpectralSource(Node thermal, double[] g, double ambient) {
    }

    private final List<SpectralSource> spectral = new ArrayList<>();

    /** Wavelength bands [nm] for light traced through dispersive optics: 18 bands of 40 nm across silicon's response. */
    public static final double[] BAND_EDGES = new double[19];
    /** Per band: a black body's emitted power F_b(T) = ∫ πB dλ [W/m²] and its photocurrent ∫ R·πB dλ [A/m²], every 10 K. */
    private static final double[][] BAND_POWER = new double[18][601], BAND_CURRENT = new double[18][601];

    /** Silicon's response to blackbody radiation, f(T) = R_b(T)·σT⁴ [A/m²], tabulated every 10 K from 0 to 6000 K. */
    private static final double[] BLACKBODY = new double[601];

    static {
        double c1 = 2 * PhysicalConstants.PLANCK * 299_792_458.0 * 299_792_458.0;
        double c2 = PhysicalConstants.PLANCK * 299_792_458.0 / PhysicalConstants.BOLTZMANN;
        for (int i = 1; i < BLACKBODY.length; i++) {
            double t = 10.0 * i, sum = 0;
            // ∫ R(λ)·π B(λ, T) dλ over silicon's band (R vanishes below 350 and above 1107 nm), 1 nm steps
            for (int nm = 350; nm <= 1107; nm++) {
                double l = nm * 1e-9, x = c2 / (l * t);
                if (x > 700) continue;
                sum += responsivity(nm) * Math.PI * c1 / Math.pow(l, 5) / Math.expm1(x) * 1e-9;
            }
            BLACKBODY[i] = sum;
        }
        SUN_RESPONSIVITY = blackbodyResponsivity(5778);
        for (int b = 0; b <= 18; b++) BAND_EDGES[b] = 380 + 40 * b;
        for (int b = 0; b < 18; b++) {
            for (int i = 1; i < 601; i++) {
                double t = 10.0 * i, pw = 0, cu = 0;
                for (double nm = BAND_EDGES[b] + 0.5; nm < BAND_EDGES[b + 1]; nm += 1) {
                    double l = nm * 1e-9, x = c2 / (l * t);
                    if (x > 700) continue;
                    double e = Math.PI * c1 / Math.pow(l, 5) / Math.expm1(x) * 1e-9;
                    pw += e;
                    cu += responsivity(nm) * e;
                }
                BAND_POWER[b][i] = pw;
                BAND_CURRENT[b][i] = cu;
            }
        }
    }

    /** Centre wavelength of band b [nm]. */
    public static double bandCentre(int b) {
        return 0.5 * (BAND_EDGES[b] + BAND_EDGES[b + 1]);
    }

    private static double table(double[] tab, double t) {
        double x = Math.max(0, Math.min(5999.999, t)) / 10;
        int i = (int) x;
        double f = x - i;
        return tab[i] * (1 - f) + tab[i + 1] * f;
    }

    /** A black body's emitted power in band b [W/m²]. */
    public static double bandPower(int b, double t) {
        return table(BAND_POWER[b], t);
    }

    /** The photocurrent a black body's light in band b drives [A per W/m²·m²]. */
    public static double bandCurrent(int b, double t) {
        return table(BAND_CURRENT[b], t);
    }

    /**
     * Photocurrent per square metre of a black body's surface [A/m² per unit g]: f(T) = ∫ R(λ) πB(λ, T) dλ, Planck's
     * spectrum weighted by silicon's responsivity — what a hot body's light drives through a photodiode.
     */
    public static double blackbodyCurrent(double t) {
        double x = Math.max(0, Math.min(5999.999, t)) / 10;
        int i = (int) x;
        double f = x - i;
        return BLACKBODY[i] * (1 - f) + BLACKBODY[i + 1] * f;
    }

    /** Silicon's responsivity to a black body's whole radiation at T [A/W]: f(T)/σT⁴. */
    public static double blackbodyResponsivity(double t) {
        return blackbodyCurrent(t) / (PhysicalConstants.STEFAN_BOLTZMANN * Math.pow(t, 4));
    }
    private double ambientWatts;
    /** Daylight's responsivity: silicon's to the sun's 5778 K spectrum (~0.25 A/W with the 0.8 quantum efficiency). */
    private double ambientResponsivity = SUN_RESPONSIVITY;
    private static final double SUN_RESPONSIVITY;

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

    /**
     * A hot body whose thermal radiation reaches this junction: g = ε·Ā·A·cos θ_r/(π d²) × visibility (its emissivity, its
     * mean projected area, the window and its tilt), so that g·σT⁴ arrives, with Planck's spectrum at the body's
     * temperature T (its thermal node's).
     */
    public void addHotSource(Node thermal, double g, double ambient) {
        hot.add(new HotSource(thermal, g, ambient));
    }

    /** A hot body seen through optics, band by band (see {@link SpectralSource}). */
    public void addSpectralHotSource(Node thermal, double[] g, double ambient) {
        spectral.add(new SpectralSource(thermal, g.clone(), ambient));
    }

    /** The summed band couplings [m²] of the hot bodies seen through optics (diagnostics, tests). */
    public double[] spectralCoupling() {
        double[] g = new double[BAND_EDGES.length - 1];
        for (SpectralSource h : spectral) for (int b = 0; b < g.length; b++) g[b] += h.g[b];
        return g;
    }

    /** Daylight arriving on the sensitive face [W], converted with silicon's responsivity to a 5778 K black body. */
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
        for (HotSource h : hot) {
            double t = ctx.x(h.thermal.index());
            iph += h.g * (blackbodyCurrent(t) - blackbodyCurrent(h.ambient));
            // ∂i_ph/∂T, the table's slope
            ctx.addFlowJ(k, a, h.thermal.index(), h.g * (blackbodyCurrent(t + 5) - blackbodyCurrent(t - 5)) / 10);
        }
        for (SpectralSource h : spectral) {
            double t = ctx.x(h.thermal.index()), di = 0;
            for (int b = 0; b < h.g.length; b++) {
                if (h.g[b] == 0) continue;
                iph += h.g[b] * (bandCurrent(b, t) - bandCurrent(b, h.ambient));
                di += h.g[b] * (bandCurrent(b, t + 5) - bandCurrent(b, t - 5)) / 10;
            }
            ctx.addFlowJ(k, a, h.thermal.index(), di);
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
        for (HotSource h : hot) pin += h.g * PhysicalConstants.STEFAN_BOLTZMANN * (Math.pow(s.x(h.thermal.index()), 4) - Math.pow(h.ambient, 4));
        for (SpectralSource h : spectral) {
            double t = s.x(h.thermal.index());
            for (int b = 0; b < h.g.length; b++) if (h.g[b] != 0) pin += h.g[b] * (bandPower(b, t) - bandPower(b, h.ambient));
        }
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
        for (HotSource h : hot) iph += h.g * (blackbodyCurrent(s.x(h.thermal.index())) - blackbodyCurrent(h.ambient));
        for (SpectralSource h : spectral) {
            double t = s.x(h.thermal.index());
            for (int b = 0; b < h.g.length; b++) if (h.g[b] != 0) iph += h.g[b] * (bandCurrent(b, t) - bandCurrent(b, h.ambient));
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
