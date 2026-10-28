package dev.emergence.core.assembly;

import java.util.HashMap;
import java.util.Map;

/**
 * Hot silicon: every silicon block's surface oxidises in the air, and a block carrying a dopant film ("dopant": a
 * wafer) takes the dopant in. Both run on the block's own simulated temperature, so a furnace is whatever heats it.
 * <ul>
 *   <li><b>Oxide</b> (dry, on a (100) face): dx/dt = B/(2x + A) + C·e^(−x/L). The first term is Deal and Grove's
 *       (1965): oxygen diffusing through the oxide grown (parabolic, B = 772 µm²/h·e^(−1.23 eV/kT)) and reacting at
 *       the silicon (linear, B/A = 3.71·10⁶ µm/h·e^(−2.0 eV/kT), the (111) rate over 1.68). The second is Massoud's
 *       (1985) fast growth of the first few nanometres, which Deal–Grove misses (C = 3.6·10⁸ µm/h·e^(−2.32 eV/kT),
 *       L = 7 nm: an order-of-magnitude fit). Both are for 1 atm of oxygen; in air they are taken as its 0.21 atm
 *       share (linear in pressure, as Henry's law makes B; B/A goes as about p^0.8). Native oxide: 1.5 nm.</li>
 *   <li><b>Dopant</b>: the film holds the surface at the dopant's solid solubility N_s (boron 2.5·10²⁰, phosphorus
 *       10²¹ cm⁻³), a constant source, so the profile is N_s·erfc(x/2√(Dt)) and the junction lies where it falls to
 *       the wafer's background, N_B = 10¹⁶ cm⁻³: x_j = 2√(Dt)·erfc⁻¹(N_B/N_s). D = D₀·e^(−E_a/kT), intrinsic
 *       (Fair): boron 0.76 cm²/s, 3.46 eV; phosphorus 3.85 cm²/s, 3.66 eV. Dt integrates D over the heating, so any
 *       temperature history counts.</li>
 * </ul>
 * Limits: dry oxidation in air only (no steam, no other atmosphere); the oxide does not slow the dopant, nor does
 * the dopant (or oxide growth) change diffusion (no concentration-dependent or oxidation-enhanced diffusion); one
 * film, one diffusion. Each block's state is two numbers saved with the machine.
 */
final class WaferProcessing {
    static final double NATIVE_OXIDE = 1.5e-9;
    private static final double K_EV = 8.617333262e-5, O2 = 0.21, UM = 1e-6, HOUR = 3600;
    private static final double B0 = 772 * UM * UM / HOUR, EB = 1.23, BA0 = 3.71e6 * UM / HOUR, EBA = 2.0;
    private static final double C0 = 3.6e8 * UM / HOUR, EC = 2.32, LC = 7e-9;
    /** Wafer background doping [cm⁻³]. */
    static final double BACKGROUND = 1e16;

    /** A dopant: its D₀ [m²/s], activation energy [eV] and solid solubility [cm⁻³]. */
    record Dopant(String name, double d0, double ea, double solubility) {
        double diffusivity(double t) {
            return d0 * Math.exp(-ea / (K_EV * t));
        }

        /** Junction depth [m] for a diffused Dt [m²]. */
        double junction(double dt) {
            return dt <= 0 ? 0 : 2 * Math.sqrt(dt) * inverseErfc(BACKGROUND / solubility);
        }
    }

    static final Dopant BORON = new Dopant("boron", 0.76e-4, 3.46, 2.5e20), PHOSPHORUS = new Dopant("phosphorus", 3.85e-4, 3.66, 1e21);

    static final class Wafer {
        final Part part;
        final Dopant dopant;
        double oxide, dt;

        Wafer(Part part, Dopant dopant) {
            this.part = part;
            this.dopant = dopant;
        }

        double junction() {
            return dopant == null ? 0 : dopant.junction(dt);
        }
    }

    final Map<Long, Wafer> byPart = new HashMap<>();
    private double lastTime = Double.NaN;

    private WaferProcessing() {
    }

    /** The dopant of a part's film, or null (no film). */
    static Dopant dopant(Part p) {
        int d = (int) Math.round(p.param("dopant", 0));
        return d == 1 ? BORON : d == 2 ? PHOSPHORUS : null;
    }

    static boolean isSilicon(Part p) {
        return p.kind() == PartKind.BLOCK && p.material().id().equals("silicon");
    }

    /** Null if the machine has no silicon blocks. */
    static WaferProcessing build(Assembly a, CompiledAssembly c, AssemblyState prev) {
        WaferProcessing w = null;
        for (Part p : a.parts()) {
            if (!isSilicon(p) || !c.thermalOfPart.containsKey(p.id())) continue;
            if (w == null) w = new WaferProcessing();
            Wafer f = new Wafer(p, dopant(p));
            f.oxide = prev.chemistry().getOrDefault(oxideKey(p.id()), NATIVE_OXIDE);
            f.dt = prev.chemistry().getOrDefault(diffusionKey(p.id()), 0.0);
            w.byPart.put(p.id(), f);
        }
        return w;
    }

    static String oxideKey(long id) {
        return "W" + id + ":ox";
    }

    static String diffusionKey(long id) {
        return "W" + id + ":Dt";
    }

    /** Oxide growth rate [m/s] at thickness x [m] and temperature t [K], in air. */
    static double oxideRate(double x, double t) {
        return oxideRate(x, t, O2);
    }

    /** Oxide growth rate [m/s] at thickness x [m] and temperature t [K], in oxygen at partial pressure p [atm]. */
    static double oxideRate(double x, double t, double p) {
        double kt = K_EV * t;
        double b = p * B0 * Math.exp(-EB / kt), ba = p * BA0 * Math.exp(-EBA / kt);
        return b / (2 * x + b / ba) + p * C0 * Math.exp(-EC / kt) * Math.exp(-x / LC);
    }

    /** Oxide grown from x [m] in s seconds at temperature t [K] and oxygen pressure p [atm]. */
    static double grow(double x, double t, double p, double s) {
        while (s > 0) {
            double r = oxideRate(x, t, p);
            double h = Math.min(s, 0.5e-9 / r);
            x += r * h;
            s -= h;
        }
        return x;
    }

    /** Advances every block to the machine's time, each at its temperature now. */
    void accumulate(CompiledAssembly c) {
        double now = c.time();
        double h = Double.isNaN(lastTime) ? 0 : now - lastTime;
        lastTime = now;
        if (!(h > 0)) return;
        for (Wafer f : byPart.values()) {
            double t = c.temperature(f.part.id());
            if (!(t > 700)) continue; // (below, nothing grows in a lifetime: e^(−2.3 eV/kT) ~ 10⁻¹⁷)
            // the oxide: explicit steps of at most half a nanometre (its rate falls as it thickens)
            f.oxide = grow(f.oxide, t, O2, h);
            if (f.dopant != null) f.dt += f.dopant.diffusivity(t) * h;
        }
    }

    void save(AssemblyState s) {
        for (var e : byPart.entrySet()) {
            Wafer f = e.getValue();
            if (f.oxide > NATIVE_OXIDE) s.chemistry().put(oxideKey(e.getKey()), f.oxide);
            if (f.dt > 0) s.chemistry().put(diffusionKey(e.getKey()), f.dt);
        }
    }

    /** erfc(x), fractional error below 1.2·10⁻⁷ (Numerical Recipes' Chebyshev fit). */
    static double erfc(double x) {
        double z = Math.abs(x), t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806
                + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }

    /** The x ≥ 0 with erfc(x) = y, for 0 < y ≤ 1 (bisection). */
    static double inverseErfc(double y) {
        double lo = 0, hi = 10;
        for (int i = 0; i < 80; i++) {
            double m = 0.5 * (lo + hi);
            if (erfc(m) > y) lo = m;
            else hi = m;
        }
        return 0.5 * (lo + hi);
    }
}
