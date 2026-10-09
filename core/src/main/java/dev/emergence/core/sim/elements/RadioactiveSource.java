package dev.emergence.core.sim.elements;

import dev.emergence.core.nuclear.NuclearData;
import dev.emergence.core.sim.Element;
import dev.emergence.core.sim.LoadContext;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.SetupContext;
import dev.emergence.core.sim.StateView;

import java.util.List;

/**
 * A sealed radioactive source: the numbers of atoms N_i of a nuclide and its descendants obey the Bateman equations
 * dN_i/dt = −λ_i·N_i + Σ_j b_ji·λ_j·N_j, so a parent and a short-lived daughter come to secular equilibrium and a
 * daughter on its own decays away with its half-life. The alpha and beta energy of each decay stays in the source
 * (heat to its thermal node — nuclear energy entering the system, counted as external power); the gamma rays leave
 * and are seen by detectors through {@link #activity}.
 */
public final class RadioactiveSource implements Element {
    private final List<NuclearData.Nuclide> chain;
    private final double[] n0;
    private final int[] idx, slot;
    private final double[][] feed; // feed[i][j]: fraction of j's decays producing i
    private final Node thermal;

    /**
     * @param chain   the nuclide and its descendants ({@link NuclearData#chain})
     * @param atoms   initial number of atoms of each
     * @param thermal the source's thermal node (null: decay heat not modelled)
     */
    public RadioactiveSource(List<NuclearData.Nuclide> chain, double[] atoms, Node thermal) {
        this.chain = List.copyOf(chain);
        this.n0 = atoms.clone();
        this.thermal = thermal;
        int m = chain.size();
        idx = new int[m];
        slot = new int[m];
        feed = new double[m][m];
        for (int j = 0; j < m; j++)
            for (NuclearData.Branch b : chain.get(j).decays())
                for (int i = 0; i < m; i++) if (chain.get(i).id().equals(b.daughter())) feed[i][j] += b.fraction();
    }

    @Override
    public void setup(SetupContext ctx) {
        for (int i = 0; i < chain.size(); i++) {
            double tol = Math.max(1, 1e-7 * Math.abs(n0[i]));
            idx[i] = ctx.allocUnknown("N:" + chain.get(i).id(), tol);
            ctx.setInitial(idx[i], n0[i]);
            slot[i] = ctx.allocState(tol);
        }
    }

    @Override
    public void load(LoadContext ctx) {
        int m = chain.size();
        double heat = 0;
        for (int i = 0; i < m; i++) {
            double ni = ctx.x(idx[i]), li = chain.get(i).lambda();
            ctx.addF(idx[i], ctx.ddt(slot[i], ni) + li * ni);
            ctx.addJ(idx[i], idx[i], ctx.alpha() + li);
            for (int j = 0; j < m; j++) {
                if (feed[i][j] == 0) continue;
                double lj = chain.get(j).lambda();
                ctx.addF(idx[i], -feed[i][j] * lj * ctx.x(idx[j]));
                ctx.addJ(idx[i], idx[j], -feed[i][j] * lj);
            }
        }
        if (thermal != null) {
            for (int i = 0; i < m; i++) {
                double e = localEnergy(i), li = chain.get(i).lambda();
                heat += li * ctx.x(idx[i]) * e;
                ctx.addJ(thermal.index(), idx[i], -li * e);
            }
            ctx.addF(thermal.index(), -heat);
        }
    }

    private double localEnergy(int i) {
        double e = 0;
        for (NuclearData.Branch b : chain.get(i).decays()) e += b.fraction() * b.localEnergy();
        return e;
    }

    public List<NuclearData.Nuclide> chain() {
        return chain;
    }

    /** Activity of nuclide i [Bq]. */
    public double activity(StateView s, int i) {
        return chain.get(i).lambda() * Math.max(0, s.x(idx[i]));
    }

    /** Total activity [Bq]. */
    public double activity(StateView s) {
        double a = 0;
        for (int i = 0; i < chain.size(); i++) a += activity(s, i);
        return a;
    }

    public double atoms(StateView s, int i) {
        return s.x(idx[i]);
    }

    /** Decay heat deposited in the source: nuclear energy entering the system. */
    @Override
    public double externalPower(StateView s) {
        if (thermal == null) return 0;
        double p = 0;
        for (int i = 0; i < chain.size(); i++) p += activity(s, i) * localEnergy(i);
        return p;
    }
}
