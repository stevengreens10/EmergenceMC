package dev.emergence.core.em;

import dev.emergence.core.materials.Material;
import dev.emergence.core.math.UniformSpline;
import dev.emergence.core.math.PeriodicFourier;
import dev.emergence.core.math.PeriodicFunction;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Electromechanical coupling of every coil and permanent magnet in an island, derived from field
 * geometry rather than from any notion of "generator" or "motor".
 *
 * <h2>Model</h2>
 * Generalised coordinates are the coil currents i_j and rigid-body angles θ_b. The magnetic co-energy
 * <pre>
 *   W'(i, θ) = ½ Σ_jk L_jk(θ) i_j i_k + Σ_j Φ_j(θ) i_j − U_mm(θ)
 * </pre>
 * contains the (geometry-dependent) self/mutual inductances, the permanent-magnet flux linkage of each
 * coil and the magnet–magnet interaction energy. From it follow, with no further assumptions,
 * <ul>
 *   <li>flux linkage λ_j = ∂W'/∂i_j and Faraday's law  v_j = R_j(T)·i_j + dλ_j/dt,</li>
 *   <li>torque on each body τ_b = ∂W'/∂θ_b (Lorentz force on the windings, magnet attraction/cogging),</li>
 *   <li>stored field energy W = Σ λ_j i_j − W', so P_elec − P_mech = dW/dt exactly in continuous time.</li>
 * </ul>
 * Back-EMF, generator braking torque, motor torque constants, transformer action and reluctance-free
 * cogging all emerge from this single formulation.
 *
 * <h2>Numerics</h2>
 * Couplings between parts on different bodies that rotate about a common axis line depend only on the
 * relative angle φ; they are tabulated once (on build) over a full revolution and represented by
 * periodic cubic splines, giving C² values and analytic derivatives for the Newton Jacobian.
 *
 * <h2>Validity</h2>
 * Magnetostatic (quasi-static) fields, rigid magnetisation. Ferromagnetic parts add to the inductances of the
 * coils fixed relative to them (a {@link ReluctanceLattice} over their cells, linear, unsaturated); iron moving
 * relative to coils (reluctance torque), iron's pull on magnets and eddy currents in structural conductors are not
 * modelled yet. Non-coaxial pairs of moving bodies are not coupled (reported in
 * {@link #warnings()}). See PHYSICS_MODELS.md §Electromagnetism.
 */
public final class MagneticSystem implements Element {

    public static final class Coil {
        final String name;
        final CoilGeometry geom;
        final EmBody body;
        final Node a, b, thermal;
        final Material wire;
        final double wireArea, wireDiameter, fixedTemperature;
        int iIdx, slot;

        Coil(String name, CoilGeometry geom, EmBody body, Node a, Node b, Material wire, double wireDiameter,
             Node thermal, double fixedTemperature) {
            this.name = name;
            this.geom = geom;
            this.body = body;
            this.a = a;
            this.b = b;
            this.wire = wire;
            this.wireDiameter = wireDiameter;
            this.wireArea = Math.PI * wireDiameter * wireDiameter / 4;
            this.thermal = thermal;
            this.fixedTemperature = fixedTemperature;
        }

        public double resistance(double temperature) {
            return wire.resistivity(temperature) * geom.wireLength() / wireArea;
        }

        public CoilGeometry geometry() {
            return geom;
        }

        public int currentIndex() {
            return iIdx;
        }
    }

    public record Magnet(CuboidMagnet magnet, EmBody body) {}

    private enum Kind { FLUX, MUTUAL, MAGNET }

    private static final class Term {
        Kind kind;
        int j = -1, k = -1;      // coils
        int bodyA = -1, bodyB = -1;
        double cA, cB;          // φ = cA·θ_A + cB·θ_B
        PeriodicFunction spline;  // tabulated coupling vs relative angle
        double constant;
    }

    private final List<Coil> coils = new ArrayList<>();
    private final List<Magnet> magnets = new ArrayList<>();
    private final List<EmBody> bodies = new ArrayList<>();
    private final List<Term> terms = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private double[][] lConst;
    private boolean built;
    /** Ferromagnetic cells: {body, int[]{x, y, z}, relative permeability}; and the cell size [m]. */
    private final List<Object[]> iron = new ArrayList<>();
    private double cell = Double.NaN;

    /** A ferromagnetic cell (relative permeability μr) of a part on {@code body}, at integer cell (x, y, z). */
    public void addIronCell(EmBody body, int x, int y, int z, double muR, double cellSize) {
        iron.add(new Object[]{body, new int[]{x, y, z}, muR});
        cell = cellSize;
    }
    private int samples = 256;

    // work arrays
    private double[] lambda, tau, th, cur;
    private double[][] dLdI, dLdTh, dTdI, dTdTh;

    public MagneticSystem tableResolution(int n) {
        this.samples = n;
        return this;
    }

    public Coil addCoil(String name, CoilGeometry geom, EmBody body, Node a, Node b, Material wire,
                        double wireDiameter, Node thermal) {
        requireNotBuilt();
        Coil c = new Coil(name, geom, body, a, b, wire, wireDiameter, thermal, 293.15);
        coils.add(c);
        registerBody(body);
        return c;
    }

    public void addMagnet(CuboidMagnet m, EmBody body) {
        requireNotBuilt();
        magnets.add(new Magnet(m, body));
        registerBody(body);
    }

    private void registerBody(EmBody b) {
        if (!bodies.contains(b)) bodies.add(b);
    }

    private void requireNotBuilt() {
        if (built) throw new IllegalStateException("MagneticSystem already built");
    }

    public List<Coil> coils() {
        return coils;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Iron within this many cells of a coil is taken into its magnetic circuit; air margin around it. */
    static final int IRON_REACH = 6, AIR_MARGIN = 3;

    /**
     * For each rigid body, the inductance its iron adds to the coils on it (and between them): solved on the cell
     * lattice with the iron and with air, the difference added to the analytic air inductances.
     */
    private void addIronInductance() {
        for (EmBody body : bodies) {
            List<Integer> idx = new ArrayList<>();
            List<ReluctanceLattice.Cut> cuts = new ArrayList<>();
            int[] o = null; // the lattice works relative to its first coil (world cell coordinates run to millions)
            for (int j = 0; j < coils.size(); j++) {
                if (coils.get(j).body != body) continue;
                idx.add(j);
                ReluctanceLattice.Cut w = cut(coils.get(j).geom);
                if (o == null) o = new int[]{w.x(), w.y(), w.z()};
                cuts.add(new ReluctanceLattice.Cut(w.x() - o[0], w.y() - o[1], w.z() - o[2], w.axis(), w.sign(), w.turns()));
            }
            if (cuts.isEmpty()) continue;
            java.util.Map<Long, Double> mu = ReluctanceLattice.cells();
            for (Object[] f : iron) {
                if (f[0] != body) continue;
                int[] w = (int[]) f[1], c = {w[0] - o[0], w[1] - o[1], w[2] - o[2]};
                for (ReluctanceLattice.Cut k : cuts) {
                    if (Math.abs(c[0] - k.x()) <= IRON_REACH && Math.abs(c[1] - k.y()) <= IRON_REACH && Math.abs(c[2] - k.z()) <= IRON_REACH) {
                        mu.merge(ReluctanceLattice.key(c[0], c[1], c[2]), (Double) f[2], Math::max);
                        break;
                    }
                }
            }
            if (mu.isEmpty()) continue;
            ReluctanceLattice lattice = new ReluctanceLattice(mu, cuts, cell, AIR_MARGIN);
            double[][] withIron = lattice.inductances(cuts, false), air = lattice.inductances(cuts, true);
            for (int a = 0; a < idx.size(); a++)
                for (int b = 0; b < idx.size(); b++) {
                    lConst[idx.get(a)][idx.get(b)] += withIron[a][b] - air[a][b];
                }
        }
    }

    /** A coil's cut face: across its winding, through the face nearest its centre along its axis. */
    private ReluctanceLattice.Cut cut(CoilGeometry g) {
        Vec3 c = g.center(), ax = g.axis();
        int axis = Math.abs(ax.x()) > 0.5 ? 0 : Math.abs(ax.y()) > 0.5 ? 1 : 2;
        double[] u = {c.x() / cell, c.y() / cell, c.z() / cell};
        int[] i = {(int) Math.floor(u[0]), (int) Math.floor(u[1]), (int) Math.floor(u[2])};
        double frac = u[axis] - Math.floor(u[axis]);
        // centre on a cell boundary (an even number of cells long): the face there; else the cell's +axis face
        if (frac < 0.25 || frac > 0.75) i[axis] = (int) Math.round(u[axis]) - 1;
        double sign = (axis == 0 ? ax.x() : axis == 1 ? ax.y() : ax.z()) > 0 ? 1 : -1;
        return new ReluctanceLattice.Cut(i[0], i[1], i[2], axis, sign, g.turns());
    }

    /** The inductance matrix of the coils with no relative motion [H] (a copy), in the order of {@link #coils()}. */
    public double[][] constantInductances() {
        build();
        double[][] l = new double[lConst.length][];
        for (int i = 0; i < l.length; i++) l[i] = lConst[i].clone();
        return l;
    }

    public double selfInductance(Coil c) {
        build();
        return lConst[coils.indexOf(c)][coils.indexOf(c)];
    }

    // ------------------------------------------------------------------ build

    /** Computes all coupling tables. Expensive (~10–500 ms); may be called ahead of time off-thread. */
    public synchronized void build() {
        if (built) return;
        int nc = coils.size();
        lConst = new double[nc][nc];
        // inductances
        for (int j = 0; j < nc; j++) {
            Coil cj = coils.get(j);
            lConst[j][j] = cj.geom.selfInductance(cj.wireDiameter);
            for (int k = j + 1; k < nc; k++) {
                Coil ck = coils.get(k);
                if (cj.body == ck.body || (cj.body.isStatic() && ck.body.isStatic())) {
                    double m = cj.geom.mutualInductance(ck.geom);
                    lConst[j][k] = lConst[k][j] = m;
                } else {
                    Term t = relativeTerm(cj.body, ck.body);
                    if (t == null) continue;
                    t.kind = Kind.MUTUAL;
                    t.j = j;
                    t.k = k;
                    final Vec3[] ax = commonAxis(cj.body, ck.body);
                    boolean sl = slides(cj.body, ck.body);
                    t.spline = table(cj.body, ck.body, phi -> cj.geom.mutualInductance(sl ? ck.geom.translated(ax[1].mul(phi))
                            : ck.geom.rotated(ax[0], ax[1], phi)));
                    terms.add(t);
                }
            }
        }
        if (!iron.isEmpty()) addIronInductance();
        // coil ↔ magnet flux linkage (only bodies different from the coil's: same-body flux is constant)
        for (int j = 0; j < nc; j++) {
            Coil cj = coils.get(j);
            for (EmBody mb : bodies) {
                if (mb == cj.body) continue;
                List<CuboidMagnet> ms = magnetsOn(mb);
                if (ms.isEmpty()) continue;
                if (cj.body.isStatic() && mb.isStatic()) continue;
                Term t = relativeTerm(cj.body, mb);
                if (t == null) continue;
                t.kind = Kind.FLUX;
                t.j = j;
                Vec3[] ax = commonAxis(cj.body, mb);
                boolean sl = slides(cj.body, mb);
                t.spline = table(cj.body, mb, phi -> {
                    List<CuboidMagnet> rot = new ArrayList<>(ms.size());
                    for (CuboidMagnet m : ms) rot.add(sl ? m.translated(ax[1].mul(phi)) : m.rotated(ax[0], ax[1], phi));
                    return cj.geom.fluxLinkage(composite(rot));
                });
                terms.add(t);
            }
        }
        // magnet ↔ magnet interaction energy between different bodies
        for (int a = 0; a < bodies.size(); a++) {
            for (int bIdx = a + 1; bIdx < bodies.size(); bIdx++) {
                EmBody ba = bodies.get(a), bb = bodies.get(bIdx);
                if (ba.isStatic() && bb.isStatic()) continue;
                List<CuboidMagnet> ma = magnetsOn(ba), mbs = magnetsOn(bb);
                if (ma.isEmpty() || mbs.isEmpty()) continue;
                Term t = relativeTerm(ba, bb);
                if (t == null) continue;
                t.kind = Kind.MAGNET;
                Vec3[] ax = commonAxis(ba, bb);
                boolean sl = slides(ba, bb);
                t.spline = table(ba, bb, phi -> {
                    List<CuboidMagnet> rot = new ArrayList<>(mbs.size());
                    for (CuboidMagnet m : mbs) rot.add(sl ? m.translated(ax[1].mul(phi)) : m.rotated(ax[0], ax[1], phi));
                    return interactionEnergy(ma, composite(rot));
                });
                terms.add(t);
            }
        }
        int nb = bodies.size();
        lambda = new double[nc];
        cur = new double[nc];
        tau = new double[nb];
        th = new double[nb];
        dLdI = new double[nc][nc];
        dLdTh = new double[nc][nb];
        dTdI = new double[nb][nc];
        dTdTh = new double[nb][nb];
        built = true;
    }

    /** True if the pair's relative motion is a slide (one of them a slider, the other static). */
    private static boolean slides(EmBody a, EmBody b) {
        return a.slider || b.slider;
    }

    /**
     * The coupling table over the pair's relative coordinate φ (B relative to A): a full revolution for a rotor
     * (Fourier series), the travel for a slide (cubic spline over the stroke plus a margin; φ = −x when the slider is
     * A, since then B moves by −x relative to it).
     */
    private PeriodicFunction table(EmBody a, EmBody b, java.util.function.DoubleUnaryOperator f) {
        if (!slides(a, b)) return new PeriodicFourier(sample(f));
        EmBody s = a.slider ? a : b;
        double margin = 0.1 * (s.hi - s.lo) + 1e-3;
        double lo = a.slider ? -s.hi - margin : s.lo - margin, hi = a.slider ? -s.lo + margin : s.hi + margin;
        int n = Math.max(32, samples / 2);
        double[] y = new double[n];
        IntStream.range(0, n).parallel().forEach(k -> y[k] = f.applyAsDouble(lo + (hi - lo) * k / (n - 1)));
        return new UniformSpline(lo, hi, y);
    }

    private double[] sample(java.util.function.DoubleUnaryOperator f) {
        double[] y = new double[samples];
        IntStream.range(0, samples).parallel().forEach(k -> y[k] = f.applyAsDouble(2 * Math.PI * k / samples));
        return y;
    }

    private List<CuboidMagnet> magnetsOn(EmBody b) {
        List<CuboidMagnet> l = new ArrayList<>();
        for (Magnet m : magnets) if (m.body == b) l.add(m.magnet);
        return l;
    }

    private static FieldSource composite(List<CuboidMagnet> ms) {
        return (px, py, pz, out) -> {
            for (CuboidMagnet m : ms) m.addB(px, py, pz, out);
        };
    }

    /**
     * U = −∫ M_a·B_ext dV over magnets {@code a}, by 4×4×4 Gauss–Legendre quadrature per magnet
     * (energy of rigidly magnetised bodies in an external field).
     */
    static double interactionEnergy(List<CuboidMagnet> a, FieldSource ext) {
        double[] gx = {-0.861136311594053, -0.339981043584856, 0.339981043584856, 0.861136311594053};
        double[] gw = {0.347854845137454, 0.652145154862546, 0.652145154862546, 0.347854845137454};
        double u = 0;
        double[] b = new double[3];
        for (CuboidMagnet m : a) {
            Vec3 mag = m.magnetization();
            double jac = m.half[0] * m.half[1] * m.half[2];
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) for (int k = 0; k < 4; k++) {
                Vec3 p = m.center.add(m.axes[0].mul(gx[i] * m.half[0])).add(m.axes[1].mul(gx[j] * m.half[1]))
                        .add(m.axes[2].mul(gx[k] * m.half[2]));
                b[0] = b[1] = b[2] = 0;
                ext.addB(p.x(), p.y(), p.z(), b);
                u -= gw[i] * gw[j] * gw[k] * jac * (mag.x() * b[0] + mag.y() * b[1] + mag.z() * b[2]);
            }
        }
        return u;
    }

    /** Axis line used to tabulate the pair (A at rest, B rotated by φ). */
    private static Vec3[] commonAxis(EmBody a, EmBody b) {
        EmBody r = a.isStatic() ? b : a;
        return new Vec3[]{r.pivot, r.axis};
    }

    /** Builds a term skeleton with φ coefficients, or null (with a warning) if the pair is not coaxial. */
    private Term relativeTerm(EmBody a, EmBody b) {
        Term t = new Term();
        t.bodyA = bodies.indexOf(a);
        t.bodyB = bodies.indexOf(b);
        if (!a.isStatic() && !b.isStatic() && (a.slider || b.slider)) {
            warnings.add("Two moving bodies " + a.name + " / " + b.name + " where one slides are not magnetically coupled (not yet supported)");
            return null;
        }
        if (!a.isStatic() && !b.isStatic()) {
            double d = a.axis.dot(b.axis);
            Vec3 off = b.pivot.sub(a.pivot);
            Vec3 perp = off.sub(a.axis.mul(off.dot(a.axis)));
            if (Math.abs(Math.abs(d) - 1) > 1e-6 || perp.length() > 1e-6) {
                warnings.add("Non-coaxial moving bodies " + a.name + " / " + b.name + " are not magnetically coupled (not yet supported)");
                return null;
            }
            t.cA = -1;
            t.cB = Math.signum(d);
        } else if (a.isStatic()) {
            t.cA = 0;
            t.cB = 1;
        } else {
            t.cA = -1;
            t.cB = 0;
        }
        return t;
    }

    // ------------------------------------------------------------------ element

    @Override
    public void setup(SetupContext ctx) {
        build();
        for (Coil c : coils) {
            int j = coils.indexOf(c);
            c.iIdx = ctx.allocUnknown("I(" + c.name + ")", 1e-9);
            double l = lConst[j][j];
            // Truncation-error tolerance on the flux linkage scaled to the signal: 1e-4 of the largest
            // permanent-magnet flux swing this coil sees, or L·1 µA, whichever is larger. (An absolute
            // tolerance far below the signal forces needlessly tiny steps whenever λ crosses zero.)
            double swing = 0;
            for (Term t : terms) if (t.kind == Kind.FLUX && t.j == j) swing = Math.max(swing, t.spline.range());
            c.slot = ctx.allocState(Math.max(Math.max(l * 1e-6, 1e-4 * swing), 1e-12));
        }
    }

    private void readAngles(StateView s) {
        for (int b = 0; b < bodies.size(); b++) th[b] = s.x(bodies.get(b).angleIndex());
    }

    /** Evaluates λ, τ and all partial derivatives at the state's currents and angles. */
    private double evaluate(StateView s) {
        int nc = coils.size(), nb = bodies.size();
        readAngles(s);
        for (int j = 0; j < nc; j++) {
            cur[j] = s.x(coils.get(j).iIdx);
            lambda[j] = 0;
            java.util.Arrays.fill(dLdI[j], 0);
            java.util.Arrays.fill(dLdTh[j], 0);
        }
        for (int b = 0; b < nb; b++) {
            tau[b] = 0;
            java.util.Arrays.fill(dTdI[b], 0);
            java.util.Arrays.fill(dTdTh[b], 0);
        }
        double energy = 0; // stored field energy W = ½ i·L·i + U_mm
        for (int j = 0; j < nc; j++) {
            for (int k = 0; k < nc; k++) {
                double l = lConst[j][k];
                if (l == 0) continue;
                lambda[j] += l * cur[k];
                dLdI[j][k] += l;
                energy += 0.5 * l * cur[j] * cur[k];
            }
        }
        double[] f = new double[3];
        for (Term t : terms) {
            double phi = t.cA * th[t.bodyA] + t.cB * th[t.bodyB];
            t.spline.eval(phi, f);
            double v = f[0], d1 = f[1], d2 = f[2];
            int[] bs = {t.bodyA, t.bodyB};
            double[] cs = {t.cA, t.cB};
            switch (t.kind) {
                case FLUX -> {
                    int j = t.j;
                    double i = cur[j];
                    lambda[j] += v;
                    for (int x = 0; x < 2; x++) {
                        if (cs[x] == 0) continue;
                        dLdTh[j][bs[x]] += d1 * cs[x];
                        tau[bs[x]] += i * d1 * cs[x];
                        dTdI[bs[x]][j] += d1 * cs[x];
                        for (int y = 0; y < 2; y++) {
                            if (cs[y] != 0) dTdTh[bs[x]][bs[y]] += i * d2 * cs[x] * cs[y];
                        }
                    }
                }
                case MUTUAL -> {
                    int j = t.j, k = t.k;
                    double ij = cur[j], ik = cur[k];
                    lambda[j] += v * ik;
                    lambda[k] += v * ij;
                    dLdI[j][k] += v;
                    dLdI[k][j] += v;
                    energy += v * ij * ik;
                    for (int x = 0; x < 2; x++) {
                        if (cs[x] == 0) continue;
                        int bx = bs[x];
                        dLdTh[j][bx] += d1 * cs[x] * ik;
                        dLdTh[k][bx] += d1 * cs[x] * ij;
                        tau[bx] += ij * ik * d1 * cs[x];
                        dTdI[bx][j] += ik * d1 * cs[x];
                        dTdI[bx][k] += ij * d1 * cs[x];
                        for (int y = 0; y < 2; y++) {
                            if (cs[y] != 0) dTdTh[bx][bs[y]] += ij * ik * d2 * cs[x] * cs[y];
                        }
                    }
                }
                case MAGNET -> {
                    energy += v;
                    for (int x = 0; x < 2; x++) {
                        if (cs[x] == 0) continue;
                        tau[bs[x]] -= d1 * cs[x];
                        for (int y = 0; y < 2; y++) {
                            if (cs[y] != 0) dTdTh[bs[x]][bs[y]] -= d2 * cs[x] * cs[y];
                        }
                    }
                }
            }
        }
        return energy;
    }

    @Override
    public void load(LoadContext ctx) {
        evaluate(ctx);
        int nc = coils.size(), nb = bodies.size();
        double alpha = ctx.alpha();
        for (int j = 0; j < nc; j++) {
            Coil c = coils.get(j);
            double i = cur[j];
            double T = c.thermal != null ? ctx.x(c.thermal.index()) : c.fixedTemperature;
            double r = c.resistance(T);
            double drdT = c.wire.resistivityDerivative(T) * c.geom.wireLength() / c.wireArea;
            double emf = ctx.ddt(c.slot, lambda[j]);
            int row = c.iIdx;
            ctx.addF(row, ctx.x(c.a.index()) - ctx.x(c.b.index()) - r * i - emf);
            ctx.addJ(row, c.a.index(), 1);
            ctx.addJ(row, c.b.index(), -1);
            for (int k = 0; k < nc; k++) {
                double d = -alpha * dLdI[j][k] - (k == j ? r : 0);
                ctx.addJ(row, coils.get(k).iIdx, d);
            }
            for (int b = 0; b < nb; b++) {
                if (dLdTh[j][b] != 0) ctx.addJ(row, bodies.get(b).angleIndex(), -alpha * dLdTh[j][b]);
            }
            // KCL at terminals: current i flows into terminal a, through the winding, out of b
            ctx.addF(c.a.index(), i);
            ctx.addF(c.b.index(), -i);
            ctx.addJ(c.a.index(), row, 1);
            ctx.addJ(c.b.index(), row, -1);
            if (c.thermal != null) {
                int t = c.thermal.index();
                ctx.addJ(row, t, -drdT * i);
                ctx.addF(t, -r * i * i);
                ctx.addJ(t, row, -2 * r * i);
                ctx.addJ(t, t, -drdT * i * i);
            }
        }
        for (int b = 0; b < nb; b++) {
            EmBody body = bodies.get(b);
            if (body.isStatic()) continue;
            int n = body.nodeIndex();
            ctx.addF(n, -tau[b]);
            for (int k = 0; k < nc; k++) ctx.addJ(n, coils.get(k).iIdx, -dTdI[b][k]);
            for (int y = 0; y < nb; y++) {
                if (dTdTh[b][y] != 0) ctx.addJ(n, bodies.get(y).angleIndex(), -dTdTh[b][y]);
            }
        }
    }

    @Override
    public double storedEnergy(StateView s) {
        return evaluate(s);
    }

    @Override
    public double untrackedLoss(StateView s) {
        double p = 0;
        for (Coil c : coils) {
            if (c.thermal != null) continue;
            double i = s.x(c.iIdx);
            p += c.resistance(c.fixedTemperature) * i * i;
        }
        return p;
    }

    // ------------------------------------------------------------------ instruments

    public double current(Coil c, StateView s) {
        return s.x(c.iIdx);
    }

    /** Present flux linkage of a coil [Wb-turns]. */
    public double fluxLinkage(Coil c, StateView s) {
        evaluate(s);
        return lambda[coils.indexOf(c)];
    }

    /** Electromagnetic torque presently acting on a body [N·m]. */
    public double torque(EmBody b, StateView s) {
        evaluate(s);
        return tau[bodies.indexOf(b)];
    }
}
