package dev.emergence.core.assembly;

import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.core.math.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fatigue of turning shafts in rotating bending (Wöhler's railway axles). A shaft carried in bearings bends under the
 * weight of what it carries; as it turns, every fibre goes from tension to compression and back once a revolution,
 * so a section whose bending stress is σ = 32M/(πd³) sees a fully reversed stress of that amplitude every turn.
 * <p>
 * Each turning body with a shaft along its axis in two or more bearings is a continuous Euler–Bernoulli beam on
 * pinned supports at its bearings, loaded by the weight (across the axis) of every part of the body at its place
 * along the shaft. Life at amplitude σ follows Basquin's line through 0.9·S_ut at 10³ cycles and the fatigue
 * strength S_e at N_e cycles (10⁷ for steels and titanium, which have an endurance limit below which life is
 * unlimited; 5·10⁸ for other metals, which have none and fail eventually at any amplitude). Damage accumulates by
 * Miner's rule, D += n/N_f, with n the revolutions turned; at D ≥ 1 the section cracks through, and whatever is
 * left beyond the break with no bearing to hold it falls.
 * <p>
 * Limits: gravity loads only (not gear-mesh or belt forces), no mean-stress (torque) or size and surface-finish
 * corrections, stress concentration only as "stress_concentration" on the shaft, linear damage accumulation.
 */
final class ShaftFatigue {
    /** One shaft cell: its body, place along the beam, stress amplitude [Pa], S–N law and damage. */
    static final class Cell {
        final Part part;
        final CompiledAssembly.Body body;
        final double s;
        double amplitude, damage;

        Cell(Part part, CompiledAssembly.Body body, double s) {
            this.part = part;
            this.body = body;
            this.s = s;
        }
    }

    /** A shaft beam: its cells in order along the axis, which carry a bearing, every part of its body with its place. */
    record Beam(CompiledAssembly.Body body, List<Cell> cells, boolean[] supported, Map<Long, Double> along) {
    }

    final List<Beam> beams = new ArrayList<>();
    final Map<Long, Cell> byPart = new HashMap<>();
    private final Map<CompiledAssembly.Body, Double> lastAngle = new HashMap<>();

    private ShaftFatigue() {
    }

    static ShaftFatigue build(Assembly a, CompiledAssembly c, AssemblyState prev) {
        ShaftFatigue f = new ShaftFatigue();
        Vec3 g = new Vec3(0, -PartGeometry.gravity(), 0);
        for (CompiledAssembly.Body b : c.bodies) {
            if (b.isStatic || b.slider) continue;
            Vec3 u = b.axis.normalize();
            Vec3 gt = g.sub(u.mul(g.dot(u)));
            if (gt.length() < 1e-6) continue; // an upright shaft does not bend under its own load
            List<Cell> cells = new ArrayList<>();
            for (long id : b.parts) {
                Part p = a.get(id);
                if (p.kind() != PartKind.SHAFT || Math.abs(Math.abs(p.axis().vec().dot(u)) - 1) > 1e-9) continue;
                cells.add(new Cell(p, b, p.center().dot(u)));
            }
            if (cells.size() < 2) continue;
            cells.sort(Comparator.comparingDouble(x -> x.s));
            boolean[] sup = new boolean[cells.size()];
            int nSup = 0;
            for (int i = 0; i < cells.size(); i++) {
                Part p = cells.get(i).part;
                for (Part q : a.at(p.minX(), p.minY(), p.minZ())) {
                    if (q.kind() == PartKind.BEARING || q.kind() == PartKind.POTENTIOMETER) sup[i] = true;
                }
                if (sup[i]) nSup++;
            }
            if (nSup < 2) continue;
            Map<Long, Double> along = new HashMap<>();
            for (long id : b.parts) along.put(id, a.get(id).center().dot(u));
            Beam beam = new Beam(b, cells, sup, along);
            solve(a, beam, gt.length());
            for (Cell cell : cells) {
                cell.damage = prev.damage().getOrDefault(cell.part.id(), 0.0);
                f.byPart.put(cell.part.id(), cell);
            }
            f.beams.add(beam);
        }
        return f;
    }

    /**
     * Bending of the beam under the transverse weight w of the body's parts: two degrees of freedom (deflection,
     * slope) per cell centre, cubic beam elements between neighbours, supports pinned. Each cell's amplitude is the
     * largest moment at its node, as 32M/(πd³) times its stress concentration.
     */
    static void solve(Assembly a, Beam beam, double gTrans) {
        List<Cell> cells = beam.cells();
        int n = cells.size(), dof = 2 * n;
        double[][] k = new double[dof][dof];
        double[] f = new double[dof];
        double[] ei = new double[n - 1], len = new double[n - 1];
        for (int e = 0; e < n - 1; e++) {
            Part p = cells.get(e).part, q = cells.get(e + 1).part;
            double d = Math.min(PartGeometry.shaftDiameter(p), PartGeometry.shaftDiameter(q));
            double modulus = Math.min(p.material().get(MaterialProperty.YOUNGS_MODULUS, 200e9), q.material().get(MaterialProperty.YOUNGS_MODULUS, 200e9));
            ei[e] = modulus * Math.PI * Math.pow(d, 4) / 64;
            len[e] = Math.max(cells.get(e + 1).s - cells.get(e).s, 1e-6);
            double l = len[e], c0 = ei[e] / (l * l * l);
            double[][] ke = {{12, 6 * l, -12, 6 * l}, {6 * l, 4 * l * l, -6 * l, 2 * l * l},
                    {-12, -6 * l, 12, -6 * l}, {6 * l, 2 * l * l, -6 * l, 4 * l * l}};
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) k[2 * e + i][2 * e + j] += c0 * ke[i][j];
        }
        // each part's weight on the cell nearest along the axis
        for (var en : beam.along().entrySet()) {
            Part p = a.get(en.getKey());
            int best = 0;
            for (int i = 1; i < n; i++) if (Math.abs(cells.get(i).s - en.getValue()) < Math.abs(cells.get(best).s - en.getValue())) best = i;
            f[2 * best] -= PartGeometry.mass(p) * gTrans;
        }
        // pinned supports: deflection fixed
        for (int i = 0; i < n; i++) {
            if (!beam.supported()[i]) continue;
            int r = 2 * i;
            for (int j = 0; j < dof; j++) {
                k[r][j] = 0;
                k[j][r] = 0;
            }
            k[r][r] = 1;
            f[r] = 0;
        }
        double[] x = gauss(k, f);
        double[] m = new double[n];
        for (int e = 0; e < n - 1; e++) {
            double l = len[e], c0 = ei[e] / (l * l * l);
            double w1 = x[2 * e], t1 = x[2 * e + 1], w2 = x[2 * e + 2], t2 = x[2 * e + 3];
            // end moments of the element (its own stiffness times its end displacements)
            double m1 = c0 * (6 * l * w1 + 4 * l * l * t1 - 6 * l * w2 + 2 * l * l * t2);
            double m2 = c0 * (6 * l * w1 + 2 * l * l * t1 - 6 * l * w2 + 4 * l * l * t2);
            m[e] = Math.max(m[e], Math.abs(m1));
            m[e + 1] = Math.max(m[e + 1], Math.abs(m2));
        }
        for (int i = 0; i < n; i++) {
            Cell cell = cells.get(i);
            double d = PartGeometry.shaftDiameter(cell.part);
            cell.amplitude = cell.part.param("stress_concentration", 1.0) * 32 * m[i] / (Math.PI * d * d * d);
        }
    }

    private static double[] gauss(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][];
        for (int i = 0; i < n; i++) m[i] = a[i].clone();
        double[] x = b.clone();
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            double[] t = m[col];
            m[col] = m[piv];
            m[piv] = t;
            double tb = x[col];
            x[col] = x[piv];
            x[piv] = tb;
            if (Math.abs(m[col][col]) < 1e-300) continue;
            for (int r = col + 1; r < n; r++) {
                double fct = m[r][col] / m[col][col];
                if (fct == 0) continue;
                for (int cc = col; cc < n; cc++) m[r][cc] -= fct * m[col][cc];
                x[r] -= fct * x[col];
            }
        }
        for (int r = n - 1; r >= 0; r--) {
            double s = x[r];
            for (int cc = r + 1; cc < n; cc++) s -= m[r][cc] * x[cc];
            x[r] = Math.abs(m[r][r]) < 1e-300 ? 0 : s / m[r][r];
        }
        return x;
    }

    /** True for metals with an endurance limit (steels, irons, titanium): below S_e, life is unlimited. */
    static boolean hasEnduranceLimit(Material m) {
        String id = m.id();
        return id.contains("steel") || id.contains("iron") || id.contains("titanium");
    }

    /**
     * Cycles to failure at a fully reversed stress amplitude [Pa]: Basquin's line through (10³, 0.9 S_ut) and
     * (N_e, S_e); unlimited below S_e for metals with an endurance limit. Without fatigue data: S_e = S_ut/2.
     */
    static double cyclesToFailure(Material m, double amplitude) {
        double sut = m.get(MaterialProperty.ULTIMATE_TENSILE_STRENGTH, Double.NaN);
        if (!(amplitude > 0) || Double.isNaN(sut)) return Double.POSITIVE_INFINITY;
        double se = m.get(MaterialProperty.FATIGUE_LIMIT, 0.5 * sut);
        boolean endurance = hasEnduranceLimit(m);
        if (endurance && amplitude <= se) return Double.POSITIVE_INFINITY;
        if (amplitude >= sut) return 0.5;
        double ne = endurance ? 1e7 : 5e8, s1 = 0.9 * sut;
        double b = Math.log10(se / s1) / Math.log10(ne / 1e3);
        return Math.max(0.5, 1e3 * Math.pow(amplitude / s1, 1 / b));
    }

    /** Adds the revolutions turned since the last call; returns the parts that break. */
    List<Long> accumulate(CompiledAssembly c) {
        List<Long> failed = new ArrayList<>();
        for (Beam beam : beams) {
            double th = c.angle(beam.body());
            Double last = lastAngle.put(beam.body(), th);
            if (last == null) continue;
            double cycles = Math.abs(th - last) / (2 * Math.PI);
            if (cycles == 0) continue;
            List<Cell> cells = beam.cells();
            for (int i = 0; i < cells.size(); i++) {
                Cell cell = cells.get(i);
                if (cell.amplitude <= 0 || cell.damage >= 1) continue;
                cell.damage += cycles / cyclesToFailure(cell.part.material(), cell.amplitude);
                if (cell.damage < 1) continue;
                failed.add(cell.part.id());
                // what lies beyond the crack with no bearing to hold it falls
                boolean before = false, after = false;
                for (int j = 0; j < i; j++) before |= beam.supported()[j];
                for (int j = i + 1; j < cells.size(); j++) after |= beam.supported()[j];
                for (var en : beam.along().entrySet()) {
                    if (en.getKey() == cell.part.id()) continue;
                    boolean beyond = en.getValue() > cell.s ? !after : en.getValue() < cell.s && !before;
                    if (beyond) failed.add(en.getKey());
                }
            }
        }
        return failed;
    }
}
