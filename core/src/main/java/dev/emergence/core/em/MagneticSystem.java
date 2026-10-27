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
 * coils fixed relative to them (a {@link ReluctanceLattice} over their cells), saturating (see addIronInductance); iron moving
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

        public CoilGeometry geom() {
            return geom;
        }

        public double wireDiameter() {
            return wireDiameter;
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

    private enum Kind { FLUX, MUTUAL, MAGNET, SELF }

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
    /** Ferromagnetic cells: {body, int[]{x, y, z}, relative permeability, saturation flux density}; and the cell size [m]. */
    private final List<Object[]> iron = new ArrayList<>();
    private double cell = Double.NaN;

    /**
     * A saturating mode of the iron's inductance: coils {@code idx}, unit current pattern u, linear inductance mu [H],
     * saturation flux linkage psiSat [Wb]: psi = psiSat * tanh(mu * m / psiSat), m = u . i.
     */
    record SatMode(int[] idx, double[] u, double mu, double psiSat, double w, Node thermal) {}

    private final List<SatMode> modes = new ArrayList<>();
    /** Hysteresis: each mode's lagging magnetisation state p (unknown index, history slot; −1 without coercivity). */
    private int[] hIdx = new int[0], hSlot = new int[0];
    /** Relaxation time of the magnetisation state towards the play band [s] (see addIronInductance). */
    static final double TAU_H = 1e-6;

    /** A ferromagnetic cell (relative permeability μr) of a part on {@code body}, at integer cell (x, y, z). */
    public void addIronCell(EmBody body, int x, int y, int z, double muR, double cellSize) {
        addIronCell(body, x, y, z, muR, cellSize, Double.POSITIVE_INFINITY);
    }

    /** As {@link #addIronCell(EmBody, int, int, int, double, double)}, saturating at mean flux density bSat [T]. */
    public void addIronCell(EmBody body, int x, int y, int z, double muR, double cellSize, double bSat) {
        addIronCell(body, x, y, z, muR, cellSize, bSat, 0, null);
    }

    /**
     * As {@link #addIronCell(EmBody, int, int, int, double, double, double)}, with the material's coercive field hc
     * [A/m] (0: no hysteresis) and the part's thermal node, where its hysteresis loss is dissipated (null: lost).
     */
    public void addIronCell(EmBody body, int x, int y, int z, double muR, double cellSize, double bSat, double hc, Node thermal) {
        iron.add(new Object[]{body, new int[]{x, y, z}, muR, bSat, hc, thermal});
        cell = cellSize;
        if (!body.isStatic()) registerBody(body); // moving iron (an armature) is pulled
    }
    private int samples = 256;

    // work arrays
    private double[] lambda, tau, th, cur;
    private double[][] dLdI, dLdTh, dTdI, dTdTh, dLdP;
    private double[] mDrive, pState, lastLoss = new double[0];

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
     * lattice with the iron and with air, the difference ΔL added to the analytic air inductances.
     * <p>
     * <b>Saturation.</b> ΔL is split into its eigenmodes, ΔL = Σ μ_k u_k u_kᵀ — current patterns u_k that drive the
     * iron's flux paths (for a transformer core, essentially one: the core flux). Each mode's flux linkage saturates,
     * ψ_k = ψ_s·tanh(μ_k m_k/ψ_s) with m_k = u_k·i: linear (inductance μ_k) at small current, approaching ψ_s where
     * the iron's most loaded cell reaches its saturation flux density — ψ_s = μ_k/r_k, r_k the peak |B|/B_sat per
     * ampere of the pattern, from the lattice. Saturated iron's incremental permeability is ~μ0, so its added
     * inductance vanishes, as tanh's slope does. The coils' linkage is the gradient of a co-energy (Σ ∫ψ_k dm), so
     * dλ/di stays symmetric and the field energy, Σ (m_k ψ_k − (ψ_s²/μ_k) ln cosh(μ_k m_k/ψ_s)), is exact.
     * <p>
     * <b>Hysteresis</b> (iron with a coercive field H_c; the dominant mode — the core's flux — only: the minor modes
     * correct the same iron's linkage, and a loop on each would count its loss again): its flux follows not its magnetising current m but a
     * state p that lags it by at most w — a play operator, the simplest model of domain walls pinned until the field
     * moves them by H_c: p stays put while |m − p| < w and is dragged along at m ∓ w beyond. In current terms w is the
     * iron's coercive mmf per turn, ∮H_c dl / N = Σ H_c·V·|B| / μ_k over the lattice's iron cells (B, μ_k per ampere of
     * the pattern; an air gap takes no mmf at the coercive point, where the flux is zero). So ψ_k = ψ_s tanh(μ_k p/ψ_s): switched off, the core keeps the flux of p = ±w — B_r ≈ μ0μr·H_c in a
     * closed core, the sheared μ0·H_c·l/g across a gap g; driven round a cycle it traces a loop of area 2w·Δψ, at most
     * 4·H_c·B_sat·V. The electrical work m·dψ splits exactly into the stored energy's change (the
     * same ln cosh expression in p) and (m − p)·ψ'(p)·ṗ ≥ 0 — the hysteresis loss, the loop's area per cycle — which
     * heats the iron. The play's corners are rounded (quadratic joins over ±2 % of w; flat inside, or the remanence would
     * creep away) and p relaxes to it with τ = 1 µs — short enough that even in saturation, where p must race the
     * current, its lag beyond w stays small (at 0.1 ms a saturating transformer lost several times its loop area). Not modelled: the loop's shape beyond a parallelogram
     * (no rectangularity, no minor-loop accommodation), modes saturating each other, the material's B–H knee.
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
            int n = idx.size();
            double[][] dl = new double[n][n];
            for (int a = 0; a < n; a++)
                for (int b = 0; b < n; b++) dl[a][b] = withIron[a][b] - air[a][b];
            // saturation flux densities of this body's iron cells, keyed as in the lattice
            java.util.Map<Long, Double> bSat = new java.util.HashMap<>();
            for (Object[] f : iron) {
                if (f[0] != body || !Double.isFinite((Double) f[3])) continue;
                int[] w = (int[]) f[1];
                long key = ReluctanceLattice.key(w[0] - o[0], w[1] - o[1], w[2] - o[2]);
                if (mu.containsKey(key)) bSat.merge(key, (Double) f[3], Math::min);
            }
            // the iron's coercivity cell by cell, and the thermal node of the most permeable iron (the core), where the
            // loss goes
            double muCore = 0;
            java.util.Map<Long, Double> hcCells = new java.util.HashMap<>();
            Node coreThermal = null;
            for (Object[] f : iron) {
                if (f[0] != body) continue;
                int[] w = (int[]) f[1];
                long key = ReluctanceLattice.key(w[0] - o[0], w[1] - o[1], w[2] - o[2]);
                if (!mu.containsKey(key)) continue;
                double m = (Double) f[2], hcCell = f.length > 4 ? (Double) f[4] : 0;
                if (hcCell > 0) hcCells.merge(key, hcCell, Math::max);
                if (m > muCore) {
                    muCore = m;
                    coreThermal = f.length > 5 ? (Node) f[5] : null;
                }
            }
            double[][] vec = new double[n][n];
            double[] val = symmetricEigen(dl, vec);
            double top = 0;
            for (double v : val) top = Math.max(top, Math.abs(v));
            int[] ids = idx.stream().mapToInt(Integer::intValue).toArray();
            for (int k = 0; k < n; k++) {
                double[] u = new double[n];
                for (int a = 0; a < n; a++) u[a] = vec[a][k];
                double ratio = val[k] > 1e-9 * top && !bSat.isEmpty() ? lattice.peakSaturation(cuts, u, bSat) : 0;
                if (ratio > 0) {
                    // the coercive width in this pattern's current: the iron's own coercive mmf ∮H_c dl — at the coercive
                    // point the flux is zero, so an air gap in the path takes none of it — per turn of the pattern:
                    // w = Σ H_c·V·|B| / μ_k, B and μ_k per ampere of the pattern (∫|B|dV = Φ·l, μ_k = N·Φ). For the
                    // body's dominant mode only (the core's flux path): the minor modes are corrections to the same
                    // iron's linkage, and a loop on each counted that iron's loss again (tenfold, in Faraday's ring).
                    // (First forms — H_c over the iron's field per ampere at the most loaded cell; Σ H_c·B_sat·V/ψ_s —
                    // made an open relay core's coercive current as large as its working current, or assumed one cross-
                    // section along the path.)
                    boolean dominant = val[k] >= top * (1 - 1e-9) && !Boolean.getBoolean("diag.noHysteresis");
                    double w = dominant && !hcCells.isEmpty() ? lattice.weightedFluxVolume(cuts, u, hcCells) / val[k] : 0;
                    modes.add(new SatMode(ids, u, val[k], val[k] / ratio, w, coreThermal));
                } else {
                    for (int a = 0; a < n; a++)
                        for (int b = 0; b < n; b++) lConst[ids[a]][ids[b]] += val[k] * u[a] * u[b];
                }
            }
        }
    }

    /**
     * Armatures: iron on a slide moving along a fixed coil's axis. The lattice gives the coil's inductance with the
     * armature against it (L_closed) and without it (L_open); the slide's travel sets the air gap g between them —
     * zero at the end of its travel towards the coil — which adds the reluctance g/(μ0A) of a gap as wide as the coil's
     * bore in series with the iron path: ΔL(g) = N²/(N²/(L_closed − L_open) + g/(μ0A)). The pull on the armature,
     * ½i²·dΔL/dx, and the back EMF i·dΔL/dx·v follow from that one co-energy.
     */
    private void addArmatures() {
        for (int j = 0; j < coils.size(); j++) {
            Coil cj = coils.get(j);
            if (!cj.body.isStatic()) continue;
            Vec3 ax = cj.geom.axis();
            ReluctanceLattice.Cut w = cut(cj.geom);
            int[] o = {w.x(), w.y(), w.z()};
            List<ReluctanceLattice.Cut> cuts = List.of(new ReluctanceLattice.Cut(0, 0, 0, w.axis(), w.sign(), w.turns()));
            for (EmBody s : bodies) {
                if (s == cj.body || !s.slider || Math.abs(Math.abs(s.axis.dot(ax)) - 1) > 1e-6) continue;
                java.util.Map<Long, Double> fixed = ReluctanceLattice.cells(), withArm = ReluctanceLattice.cells();
                double side = 0;
                int count = 0;
                for (Object[] f : iron) {
                    int[] q = (int[]) f[1], c = {q[0] - o[0], q[1] - o[1], q[2] - o[2]};
                    if (Math.abs(c[0]) > IRON_REACH || Math.abs(c[1]) > IRON_REACH || Math.abs(c[2]) > IRON_REACH) continue;
                    long key = ReluctanceLattice.key(c[0], c[1], c[2]);
                    if (f[0] == cj.body) {
                        fixed.merge(key, (Double) f[2], Math::max);
                        withArm.merge(key, (Double) f[2], Math::max);
                    } else if (f[0] == s) {
                        withArm.merge(key, (Double) f[2], Math::max);
                        Vec3 p = new Vec3((q[0] + 0.5) * cell, (q[1] + 0.5) * cell, (q[2] + 0.5) * cell);
                        side += p.sub(cj.geom.center()).dot(s.axis);
                        count++;
                    }
                }
                if (count == 0) continue;
                double lClosed = new ReluctanceLattice(withArm, cuts, cell, AIR_MARGIN).inductances(cuts, false)[0][0];
                double lOpen = fixed.isEmpty() ? new ReluctanceLattice(withArm, cuts, cell, AIR_MARGIN).inductances(cuts, true)[0][0]
                        : new ReluctanceLattice(fixed, cuts, cell, AIR_MARGIN).inductances(cuts, false)[0][0];
                double dl = lClosed - lOpen;
                if (Boolean.getBoolean("diag.armature")) System.out.println("armature on coil " + j + ": L closed " + lClosed + " open " + lOpen
                        + " iron cells " + count + " side " + side + " travel " + s.lo + ".." + s.hi);
                if (!(dl > 1e-12)) continue;
                double n2 = (double) cj.geom.turns * cj.geom.turns, rPrime = n2 / dl;
                double rIn = Math.max(cj.geom.meanRadius() - 0.5 * cj.geom.thickness(), 1e-4);
                double area = Math.PI * rIn * rIn, mu0 = dev.emergence.core.units.PhysicalConstants.MU0;
                // the armature on the +axis side of the coil closes by moving −axis: its gap is the travel left that way
                boolean plusSide = side > 0;
                double lo = s.lo, hi = s.hi;
                Term t = relativeTerm(cj.body, s);
                if (t == null) continue;
                t.kind = Kind.SELF;
                t.j = j;
                t.spline = table(cj.body, s, phi -> {
                    double gap = Math.max(0, plusSide ? phi - lo : hi - phi);
                    return n2 / (rPrime + gap / (mu0 * area));
                });
                terms.add(t);
            }
        }
    }

    /**
     * Permanent magnets and iron: a magnet and iron on a slide moving along the magnet's axis (the iron moving, or the
     * magnet) pull together. By the method of images, iron of permeability μr facing a magnet acts on it as a mirror
     * image of the magnet behind the iron's face, of strength k = (μr − 1)/(μr + 1) (≈ 1 for steel): the system's energy
     * is half the magnet's interaction with its image, U(g) = ½·k·c·U_int(magnet, image at 2g + l), and the pull equals
     * the force between the magnet and its image. Exact for a magnetisation normal to a wide, thick, unsaturated iron
     * face; the face is taken where the nearest iron cells begin, its coverage c of the magnet's face scales the energy
     * (a narrow armature catches less of the field); saturation is not modelled (a pull from closer than a few
     * millimetres is overstated). Coils on the same path do not add to the magnet's field here.
     */
    private void addMagnetArmatures() {
        for (Magnet mg : magnets) {
            CuboidMagnet m = mg.magnet;
            Vec3 dir = m.magnetization();
            double mag = dir.length();
            if (mag <= 0) continue;
            dir = dir.mul(1 / mag);
            int a = Math.abs(dir.x()) > 0.9 ? 0 : Math.abs(dir.y()) > 0.9 ? 1 : Math.abs(dir.z()) > 0.9 ? 2 : -1;
            if (a < 0) continue; // only magnets magnetised along a world axis
            int t1 = (a + 1) % 3, t2 = (a + 2) % 3;
            double[] mc = {m.center.x(), m.center.y(), m.center.z()};
            for (EmBody other : bodies) {
                if (other == mg.body) continue;
                // the slide: the magnet's body or the other's, moving along the magnet's axis; the other held still
                EmBody slide = mg.body.slider && other.isStatic() ? mg.body : other.slider && mg.body.isStatic() ? other : null;
                if (slide == null || Math.abs(Math.abs(slide.axis.dot(dir)) - 1) > 1e-6) continue;
                // the other's iron in front of either pole, within the magnet's footprint: its nearest face, how much of the
                // magnet's face it covers, and its permeability
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    double pole = mc[a] + sgn * m.half[a], face = Double.NaN, covered = 0, muR = 0;
                    java.util.Set<Long> cols = new java.util.HashSet<>();
                    for (Object[] f : iron) {
                        if (f[0] != other) continue;
                        int[] q = (int[]) f[1];
                        double lo = q[a] * cell, hi = (q[a] + 1) * cell;
                        double near = sgn > 0 ? lo : hi;
                        if (sgn * (near - pole) < -1e-9 || sgn * (near - pole) > IRON_REACH * cell) continue;
                        double o1 = Math.min((q[t1] + 1) * cell, mc[t1] + m.half[t1]) - Math.max(q[t1] * cell, mc[t1] - m.half[t1]);
                        double o2 = Math.min((q[t2] + 1) * cell, mc[t2] + m.half[t2]) - Math.max(q[t2] * cell, mc[t2] - m.half[t2]);
                        if (o1 <= 0 || o2 <= 0) continue;
                        if (Double.isNaN(face) || sgn * (near - face) < 0) face = near;
                        if (cols.add(ReluctanceLattice.key(q[t1], q[t2], 0))) covered += o1 * o2;
                        muR = Math.max(muR, (Double) f[2]);
                    }
                    if (Double.isNaN(face)) continue;
                    // only the cells at that nearest face count towards the coverage
                    covered = 0;
                    cols.clear();
                    for (Object[] f : iron) {
                        if (f[0] != other) continue;
                        int[] q = (int[]) f[1];
                        double near = sgn > 0 ? q[a] * cell : (q[a] + 1) * cell;
                        if (Math.abs(near - face) > 1e-9) continue;
                        double o1 = Math.min((q[t1] + 1) * cell, mc[t1] + m.half[t1]) - Math.max(q[t1] * cell, mc[t1] - m.half[t1]);
                        double o2 = Math.min((q[t2] + 1) * cell, mc[t2] + m.half[t2]) - Math.max(q[t2] * cell, mc[t2] - m.half[t2]);
                        if (o1 > 0 && o2 > 0 && cols.add(ReluctanceLattice.key(q[t1], q[t2], 0))) covered += o1 * o2;
                    }
                    double coverage = Math.min(1, covered / (4 * m.half[t1] * m.half[t2]));
                    double k = (muR - 1) / (muR + 1) * coverage, g0 = sgn * (face - pole);
                    // the gap widens as the slide moves the two apart: the iron moving away along sgn·axis, or the magnet
                    // moving the other way
                    double away = (slide == other ? 1 : -1) * sgn * Math.signum(slide.axis.dot(dir));
                    Vec3 dirF = dir, sideV = dir.mul(sgn);
                    double half = m.half[a];
                    EmBody fixed = slide == other ? mg.body : other;
                    Term t = relativeTerm(fixed, slide);
                    if (t == null) continue;
                    t.kind = Kind.MAGNET;
                    List<CuboidMagnet> self = List.of(m);
                    t.spline = table(fixed, slide, phi -> {
                        double gap = Math.max(1e-4, g0 + away * phi);
                        CuboidMagnet image = m.translated(sideV.mul(2 * (half + gap)));
                        return 0.5 * k * interactionEnergy(self, image);
                    });
                    if (Boolean.getBoolean("diag.armature")) System.out.println("magnet image: pole " + sgn + " gap " + g0 + " coverage "
                            + coverage + " k " + k + " direction " + dirF);
                    terms.add(t);
                }
            }
        }
    }

    /** A coil's cut face: across its winding, through the face nearest its centre along its axis. */
    private ReluctanceLattice.Cut cut(CoilGeometry g) {
        return cutAt(g.center(), g.axis(), g.turns());
    }

    /** The cut face of a winding of {@code turns} about {@code ax} centred at {@code c} (see {@link #cut(CoilGeometry)}). */
    private ReluctanceLattice.Cut cutAt(Vec3 c, Vec3 ax, int turns) {
        int axis = Math.abs(ax.x()) > 0.5 ? 0 : Math.abs(ax.y()) > 0.5 ? 1 : 2;
        double[] u = {c.x() / cell, c.y() / cell, c.z() / cell};
        int[] i = {(int) Math.floor(u[0]), (int) Math.floor(u[1]), (int) Math.floor(u[2])};
        double frac = u[axis] - Math.floor(u[axis]);
        // centre on a cell boundary (an even number of cells long): the face there; else the cell's +axis face
        if (frac < 0.25 || frac > 0.75) i[axis] = (int) Math.round(u[axis]) - 1;
        double sign = (axis == 0 ? ax.x() : axis == 1 ? ax.y() : ax.z()) > 0 ? 1 : -1;
        return new ReluctanceLattice.Cut(i[0], i[1], i[2], axis, sign, turns);
    }

    /** Eigenvalues of symmetric a0 (cyclic Jacobi); eigenvectors into the columns of v. */
    static double[] symmetricEigen(double[][] a0, double[][] v) {
        int n = a0.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = a0[i].clone();
            java.util.Arrays.fill(v[i], 0);
            v[i][i] = 1;
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0, scale = 0;
            for (int p = 0; p < n; p++)
                for (int q = 0; q < n; q++) {
                    if (p != q) off += a[p][q] * a[p][q];
                    scale += a[p][q] * a[p][q];
                }
            if (off <= 1e-30 * scale) break;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) {
                    if (a[p][q] == 0) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = theta == 0 ? 1 : Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1), sn = t * c;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - sn * akq;
                        a[k][q] = sn * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - sn * aqk;
                        a[q][k] = sn * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - sn * vkq;
                        v[k][q] = sn * vkp + c * vkq;
                    }
                }
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = a[i][i];
        return out;
    }

    /**
     * The inductance matrix of the coils with no relative motion [H] (a copy), in the order of {@link #coils()}: at zero
     * current (the saturating iron at its small-signal inductance).
     */
    public double[][] constantInductances() {
        build();
        double[][] l = new double[lConst.length][];
        for (int i = 0; i < l.length; i++) l[i] = lConst[i].clone();
        for (SatMode md : modes)
            for (int a = 0; a < md.idx.length; a++)
                for (int b = 0; b < md.idx.length; b++) l[md.idx[a]][md.idx[b]] += md.mu * md.u[a] * md.u[b];
        return l;
    }

    /**
     * The saturating iron modes: each {ψ_s [Wb], μ [H], u_0 … u_{n−1}} — the flux linkage it levels off at, its
     * small-signal inductance, and its current pattern over {@link #coils()} (unit length).
     */
    public List<double[]> saturationModes() {
        build();
        List<double[]> out = new ArrayList<>();
        for (SatMode md : modes) {
            double[] r = new double[2 + coils.size()];
            r[0] = md.psiSat;
            r[1] = md.mu;
            for (int a = 0; a < md.idx.length; a++) r[2 + md.idx[a]] = md.u[a];
            out.add(r);
        }
        return out;
    }

    public double selfInductance(Coil c) {
        build();
        int j = coils.indexOf(c);
        return constantInductances()[j][j];
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
        if (!iron.isEmpty()) {
            addIronInductance();
            addArmatures();
            // no hysteresis in a core that pulls an armature: the pull here is ½i²·dL/dx, from the current, and a core
            // whose flux lags the current (holding remanence as the current dies) would have it let go early — a relay
            // whose residual flux should make it hold longer dropped out sooner. (Flux-based armature force: NEXT_STEPS.)
            java.util.Set<Integer> pulling = new java.util.HashSet<>();
            for (Term t : terms) if (t.kind == Kind.SELF) pulling.add(t.j);
            for (int k = 0; k < modes.size(); k++) {
                SatMode md = modes.get(k);
                if (md.w <= 0) continue;
                for (int j : md.idx) {
                    if (pulling.contains(j)) {
                        modes.set(k, new SatMode(md.idx, md.u, md.mu, md.psiSat, 0, md.thermal));
                        break;
                    }
                }
            }
            addMagnetArmatures();
        }
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
        dLdP = new double[nc][modes.size()];
        mDrive = new double[modes.size()];
        pState = new double[modes.size()];
        lastLoss = new double[modes.size()];
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
        double[] fluxTol = new double[coils.size()];
        for (Coil c : coils) {
            int j = coils.indexOf(c);
            c.iIdx = ctx.allocUnknown("I(" + c.name + ")", 1e-9);
            double l = lConst[j][j];
            // Truncation-error tolerance on the flux linkage scaled to the signal: 1e-4 of the largest
            // permanent-magnet flux swing this coil sees, or L·1 µA, whichever is larger. (An absolute
            // tolerance far below the signal forces needlessly tiny steps whenever λ crosses zero.)
            double swing = 0;
            for (Term t : terms) if (t.kind == Kind.FLUX && t.j == j) swing = Math.max(swing, t.spline.range());
            fluxTol[j] = Math.max(Math.max(l * 1e-6, 1e-4 * swing), 1e-12) * Double.parseDouble(System.getProperty("diag.fluxTolScale", "1"));
            c.slot = ctx.allocState(fluxTol[j]);
        }
        hIdx = new int[modes.size()];
        hSlot = new int[modes.size()];
        for (int k = 0; k < modes.size(); k++) {
            SatMode md = modes.get(k);
            if (md.w <= 0 || Boolean.getBoolean("diag.noHyst")) {
                hIdx[k] = -1;
                continue;
            }
            hIdx[k] = ctx.allocUnknown("magnetisation" + k, 1e-6 * md.w + 1e-9);
            // The magnetisation counts only through the flux it gives its coils (ψ = ψ_s tanh(μ p/ψ_s)): its error is
            // held to what theirs is, the coils' flux tolerance over μ — not to 1e-4 of the play's width alone, which
            // (a generator's steel beside its coils) held it to a fortieth of their flux tolerance, and its every
            // reversal as a magnet passed to steps that size.
            double tol = 1e-4 * md.w + 1e-9;
            if (md.mu > 0) {
                double flux = Double.POSITIVE_INFINITY;
                for (int j : md.idx) flux = Math.min(flux, fluxTol[j]);
                if (Double.isFinite(flux)) tol = Math.max(tol, flux / md.mu);
            }
            hSlot[k] = ctx.allocState(tol);
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
        double energy = 0; // stored field energy W = ½ i·L·i + U_mm (+ the saturating iron's)
        for (int k = 0; k < modes.size(); k++) {
            SatMode md = modes.get(k);
            double m = 0;
            for (int a = 0; a < md.idx.length; a++) m += md.u[a] * cur[md.idx[a]];
            for (int j = 0; j < nc; j++) dLdP[j][k] = 0;
            boolean hyst = hIdx.length > k && hIdx[k] >= 0;
            // a hysteretic mode's flux follows its lagging state p; otherwise the current itself
            double q = hyst ? s.x(hIdx[k]) : m;
            mDrive[k] = m;
            pState[k] = q;
            double x = md.mu * q / md.psiSat, t = Math.tanh(x), psi = md.psiSat * t, slope = md.mu * (1 - t * t);
            for (int a = 0; a < md.idx.length; a++) {
                lambda[md.idx[a]] += psi * md.u[a];
                if (hyst) dLdP[md.idx[a]][k] += slope * md.u[a];
                else for (int b = 0; b < md.idx.length; b++) dLdI[md.idx[a]][md.idx[b]] += slope * md.u[a] * md.u[b];
            }
            double ax = Math.abs(x), lnCosh = ax + Math.log1p(Math.exp(-2 * ax)) - Math.log(2);
            energy += q * psi - md.psiSat * md.psiSat / md.mu * lnCosh;
        }
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
                case SELF -> {
                    int j = t.j;
                    double i = cur[j];
                    lambda[j] += v * i;
                    dLdI[j][j] += v;
                    energy += 0.5 * v * i * i;
                    for (int x = 0; x < 2; x++) {
                        if (cs[x] == 0) continue;
                        int bx = bs[x];
                        dLdTh[j][bx] += d1 * cs[x] * i;
                        tau[bx] += 0.5 * i * i * d1 * cs[x];
                        dTdI[bx][j] += i * d1 * cs[x];
                        for (int y = 0; y < 2; y++) {
                            if (cs[y] != 0) dTdTh[bx][bs[y]] += 0.5 * i * i * d2 * cs[x] * cs[y];
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
        for (int k = 0; k < modes.size(); k++) {
            if (hIdx[k] < 0) continue;
            SatMode md = modes.get(k);
            int ip = hIdx[k];
            for (int a = 0; a < md.idx.length; a++) {
                int j = md.idx[a];
                if (dLdP[j][k] != 0) ctx.addJ(coils.get(j).iIdx, ip, -alpha * dLdP[j][k]);
            }
            // the play: τ·ṗ = g(m − p), g a dead zone of half-width w
            double m = mDrive[k], p = pState[k], d = m - p;
            double[] g = deadZone(d, md.w);
            double pdot = ctx.ddt(hSlot[k], p);
            ctx.addF(ip, TAU_H * pdot - g[0]);
            ctx.addJ(ip, ip, TAU_H * alpha + g[1]);
            for (int a = 0; a < md.idx.length; a++) ctx.addJ(ip, coils.get(md.idx[a]).iIdx, -g[1] * md.u[a]);
            // the loss (m − p)·ψ'(p)·ṗ into the iron, with the integrator's own ṗ (g/τ would multiply Newton's residual
            // in p's equation by 1/τ — a million — and heat the core several times over)
            double x = md.mu * p / md.psiSat, t = Math.tanh(x), dpsi = md.mu * (1 - t * t), d2psi = -2 * t * dpsi * md.mu / md.psiSat;
            double loss = d * dpsi * pdot;
            lastLoss[k] = loss;
            if (md.thermal != null) {
                int it = md.thermal.index();
                double dLp = -dpsi * pdot + d * d2psi * pdot + d * dpsi * alpha, dLm = dpsi * pdot;
                ctx.addF(it, -loss);
                ctx.addJ(it, ip, -dLp);
                for (int a = 0; a < md.idx.length; a++) ctx.addJ(it, coils.get(md.idx[a]).iIdx, -dLm * md.u[a]);
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


    /**
     * A dead zone of half-width w, exactly flat inside |d| < 0.98w (a slope there, however small, lets the state creep
     * towards the current and the remanence leak away) and joined to the lines d ∓ w by quadratics over ±2 % of w (C¹):
     * [g(d), g'(d)].
     */
    /** The play's corners' rounding, a fraction of its half-width either side ({@code diag.playRound}). */
    static final double PLAY_ROUND = Double.parseDouble(System.getProperty("diag.playRound", "0.02"));

    static double[] deadZone(double d, double w) {
        double e = PLAY_ROUND * w, a = Math.abs(d), sg = Math.signum(d);
        if (a <= w - e) return new double[]{0, 0};
        if (a >= w + e) return new double[]{sg * (a - w), 1};
        double x = a - (w - e);
        return new double[]{sg * x * x / (4 * e), x / (2 * e)};
    }

    /** Mode k's hysteresis loss over the last step [W]. */
    public double modeLoss(int k) {
        return lastLoss.length > k ? lastLoss[k] : 0;
    }

    /** Hysteresis loss dissipated in the iron over the last step [W] (as the solver last evaluated it). */
    public double hysteresisLoss(StateView s) {
        double sum = 0;
        for (double l : lastLoss) sum += l;
        return sum;
    }

    /** Mode k's state: [magnetising current m, lagging state p, flux linkage ψ(p)] (p = m without hysteresis). */
    public double[] modeState(int k, StateView s) {
        evaluate(s);
        SatMode md = modes.get(k);
        return new double[]{mDrive[k], pState[k], md.psiSat * Math.tanh(md.mu * pState[k] / md.psiSat)};
    }

    /** Each hysteretic mode's coercive width w in its current pattern [A] (0 without hysteresis). */
    public double[] coerciveWidths() {
        build();
        double[] w = new double[modes.size()];
        for (int k = 0; k < w.length; k++) w[k] = modes.get(k).w;
        return w;
    }

    @Override
    public double untrackedLoss(StateView s) {
        double p = 0;
        for (int k = 0; k < modes.size(); k++) if (modes.get(k).thermal == null) p += lastLoss[k];
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
