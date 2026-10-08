package dev.emergence.core.sim;

import dev.emergence.core.units.Dimension;

/**
 * Physical domains of the lumped multiphysics network. Each domain is described by an
 * "across" variable (measured between two points: potential-like) and a "through" variable
 * (flowing through elements: flux-like) — the standard network/bond-graph formulation.
 * <p>
 * For the electrical, mechanical and hydraulic domains across × through is power, so coupling
 * elements built from these ports are power-conjugate by construction. The thermal domain uses
 * temperature and heat flow (the engineering convention); its product is not power, and coupling
 * into it is always via explicit energy flows (dissipated power becomes heat flow), never via T·Q̇.
 */
public enum Domain {
    ELECTRICAL("electrical", Dimension.VOLTAGE, Dimension.CURRENT, 1e-6, 1e-12),
    ROTATIONAL("rotational", Dimension.ANGULAR_VELOCITY, Dimension.TORQUE, 1e-6, 1e-12),
    TRANSLATIONAL("translational", Dimension.VELOCITY, Dimension.FORCE, 1e-7, 1e-12),
    THERMAL("thermal", Dimension.TEMPERATURE, Dimension.POWER, 1e-6, 1e-12),
    HYDRAULIC("hydraulic", Dimension.PRESSURE, Dimension.VOLUMETRIC_FLOW, 1e-3, 1e-15);

    public final String id;
    public final Dimension across;
    public final Dimension through;
    /** Absolute convergence tolerance for across variables of this domain. */
    public final double acrossAbsTol;
    /**
     * Tiny shunt admittance from every node to the reference (like SPICE's GMIN) that keeps the
     * system non-singular when a sub-network floats. Its effect is far below measurement resolution.
     */
    public final double gmin;

    Domain(String id, Dimension across, Dimension through, double acrossAbsTol, double gmin) {
        this.id = id;
        this.across = across;
        this.through = through;
        this.acrossAbsTol = acrossAbsTol;
        this.gmin = gmin;
    }

    /** True when across × through has the dimension of power. */
    public boolean isPowerConjugate() {
        return across.mul(through).equals(Dimension.POWER);
    }
}
