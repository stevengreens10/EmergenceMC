package dev.emergence.core.sim.elements;

import dev.emergence.core.materials.Material;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.StateView;

/**
 * A segment of real conductive material: R(T) = ρ(T)·L/A with the material's measured temperature
 * dependence. Fully coupled electro-thermal element: the resistance depends on the temperature of the
 * thermal node, and Joule heating is delivered to that node. This is what lets a thin tungsten wire
 * glow, a copper wire act as a fuse, or a thermistor-like drift appear without special cases.
 * <p>
 * Assumes uniform current density (valid below the skin-effect frequency f ≈ ρ/(π μ0 μr r²), e.g.
 * ~4 kHz for a 1 mm copper wire).
 * <p>
 * Thermoelectricity (optional, {@link #withSeebeck}): with the ends at temperatures T_a and T_b, the current obeys
 * I·R = V_a − V_b − S·(T_b − T_a) (Seebeck: J = σ(E − S∇T)), and the current carries Peltier heat S·T·I — taken
 * from end a, delivered at end b. Around any loop the EMFs add to ∮S dT, so a junction of two different conductors
 * at different temperatures is a thermocouple, a current through it pumps heat (Peltier) and the work the EMF does
 * is exactly the heat it moves: energy is conserved without a separate source term. S is the material's absolute
 * Seebeck coefficient, taken constant (no Thomson heat).
 */
public final class Conductor implements Element {
    private final Node a, b, thermal;
    private final Material material;
    private final double geom; // L / A  [1/m]
    private final double fixedT;
    private double seebeck;
    private Node[] aNodes = new Node[0], bNodes = new Node[0];
    private double[] aW = new double[0], bW = new double[0];
    /** Scratch for the thermoelectric Jacobian (sized when the end nodes are set; no allocation per load). */
    private int[] col = new int[3];
    private double[] di = new double[3], dta = new double[3], dtb = new double[3], dj = new double[3];

    /**
     * @param length       conduction path length [m]
     * @param area         cross-sectional area [m²]
     * @param thermal      thermal node of the conductor (null → evaluated at {@code fixedTemperature})
     */
    public Conductor(Node a, Node b, Material material, double length, double area, Node thermal, double fixedTemperature) {
        this.a = a;
        this.b = b;
        this.material = material;
        this.geom = length / area;
        this.thermal = thermal;
        this.fixedT = fixedTemperature;
    }

    /**
     * Adds the thermoelectric EMF: the temperature at end a is Σ aW·T(aNodes), at end b Σ bW·T(bNodes) (weights
     * summing to 1, e.g. a contact between two parts).
     */
    public Conductor withSeebeck(double seebeck, Node[] aNodes, double[] aW, Node[] bNodes, double[] bW) {
        this.seebeck = seebeck;
        this.aNodes = aNodes;
        this.aW = aW;
        this.bNodes = bNodes;
        this.bW = bW;
        int nc = 3 + aNodes.length + bNodes.length;
        col = new int[nc];
        di = new double[nc];
        dta = new double[nc];
        dtb = new double[nc];
        dj = new double[nc];
        return this;
    }

    private static double weighted(StateView s, Node[] n, double[] w) {
        double t = 0;
        for (int k = 0; k < n.length; k++) t += w[k] * s.x(n[k].index());
        return t;
    }

    private double temperature(StateView s) {
        return thermal != null ? s.x(thermal.index()) : fixedT;
    }

    public double resistance(StateView s) {
        return material.resistivity(temperature(s)) * geom;
    }

    @Override
    public void load(LoadContext ctx) {
        if (seebeck != 0 && thermal != null) {
            loadThermoelectric(ctx);
            return;
        }
        double T = temperature(ctx);
        if (T != rAtT) {
            rCached = material.resistivity(T) * geom;
            drdTCached = material.resistivityDerivative(T) * geom;
            rAtT = T;
        }
        double r = rCached, drdT = drdTCached;
        double v = ctx.x(a.index()) - ctx.x(b.index());
        double g = 1.0 / r;
        ctx.addFlow(a, b, g * v);
        ctx.stampConductance(a, b, g);
        if (thermal != null) {
            int t = thermal.index();
            double didT = -v * drdT / (r * r);
            ctx.addJ(a.index(), t, didT);
            ctx.addJ(b.index(), t, -didT);
            double p = g * v * v;
            ctx.addF(t, -p);
            ctx.addJ(t, a.index(), -2 * g * v);
            ctx.addJ(t, b.index(), 2 * g * v);
            ctx.addJ(t, t, v * v * drdT / (r * r));
        }
    }

    /** The resistance and its temperature slope at {@link #rAtT} (held through a fast partition's steps: computed once). */
    private double rAtT = Double.NaN, rCached, drdTCached;

    private void loadThermoelectric(LoadContext ctx) {
        int t = thermal.index(), ia = a.index(), ib = b.index();
        double T = ctx.x(t);
        if (T != rAtT) {
            rCached = material.resistivity(T) * geom;
            drdTCached = material.resistivityDerivative(T) * geom;
            rAtT = T;
        }
        double r = rCached, drdT = drdTCached;
        double ta = weighted(ctx, aNodes, aW), tb = weighted(ctx, bNodes, bW);
        double v = ctx.x(ia) - ctx.x(ib), emf = seebeck * (tb - ta), g = 1 / r;
        double i = g * (v - emf);
        ctx.addFlow(a, b, i);
        ctx.addF(t, -i * i * r);                          // Joule heat in the conductor
        for (int k = 0; k < aNodes.length; k++) ctx.addF(aNodes[k].index(), aW[k] * seebeck * ta * i);  // Peltier heat taken at a
        for (int k = 0; k < bNodes.length; k++) ctx.addF(bNodes[k].index(), -bW[k] * seebeck * tb * i); // and given at b
        if (ctx.held(t)) {
            // temperatures held (multirate, fast partition): the electrical derivatives, and the Joule heat's own
            // temperature slope ∂(−I²r)/∂T = I²·dr/dT (at fixed EMF) — the coupling's stiffness the solver watches
            ctx.stampConductance(a, b, g);
            ctx.addJ(t, t, i * i * drdT);
            return;
        }
        // Jacobian: columns V_a, V_b, T (resistance), the end temperatures
        int nc = col.length;
        java.util.Arrays.fill(dta, 0);
        java.util.Arrays.fill(dtb, 0);
        col[0] = ia;
        di[0] = g;
        dj[0] = 2 * i;
        col[1] = ib;
        di[1] = -g;
        dj[1] = -2 * i;
        col[2] = t;
        di[2] = -i * drdT / r;
        dj[2] = -i * i * drdT;
        for (int k = 0; k < aNodes.length; k++) {
            int c = 3 + k;
            col[c] = aNodes[k].index();
            dta[c] = aW[k];
            di[c] = g * seebeck * aW[k];
            dj[c] = 2 * i * r * di[c] - 0;
        }
        for (int k = 0; k < bNodes.length; k++) {
            int c = 3 + aNodes.length + k;
            col[c] = bNodes[k].index();
            dtb[c] = bW[k];
            di[c] = -g * seebeck * bW[k];
            dj[c] = 2 * i * r * di[c];
        }
        // Joule heat J = I²r: dJ/dx = 2Ir·dI/dx for the electrical/end columns, plus I²·dr/dT for the conductor's own T
        dj[0] = 2 * i * r * di[0];
        dj[1] = 2 * i * r * di[1];
        dj[2] = 2 * i * r * di[2] + i * i * drdT;
        for (int c = 0; c < nc; c++) {
            if (col[c] < 0) continue;
            ctx.addJ(ia, col[c], di[c]);
            ctx.addJ(ib, col[c], -di[c]);
            ctx.addJ(t, col[c], -dj[c]);
            for (int k = 0; k < aNodes.length; k++)
                ctx.addJ(aNodes[k].index(), col[c], aW[k] * seebeck * (dta[c] * i + ta * di[c]));
            for (int k = 0; k < bNodes.length; k++)
                ctx.addJ(bNodes[k].index(), col[c], -bW[k] * seebeck * (dtb[c] * i + tb * di[c]));
        }
    }

    public double current(StateView s) {
        if (seebeck != 0 && thermal != null) {
            return (s.across(a, b) - seebeck * (weighted(s, bNodes, bW) - weighted(s, aNodes, aW))) / resistance(s);
        }
        return s.across(a, b) / resistance(s);
    }

    public double power(StateView s) {
        double v = s.across(a, b);
        return v * v / resistance(s);
    }

    @Override
    public double untrackedLoss(StateView s) {
        return thermal == null ? power(s) : 0.0;
    }
}
