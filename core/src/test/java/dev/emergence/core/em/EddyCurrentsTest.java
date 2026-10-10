package dev.emergence.core.em;

import dev.emergence.core.math.Vec3;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Eddy-current drag against closed-form results. */
class EddyCurrentsTest {
    /**
     * A small magnet (a dipole p) moving along the axis of a thin-walled conducting tube (radius a, wall w) is braked
     * by F = σ w v ∫ B_r² 2πa dz: the azimuthal currents close round the tube, so σ v B_r per unit of wall is exact.
     * With the dipole's B_r = (μ0p/4π)·3az/(a² + z²)^{5/2} and ∫ z²/(a² + z²)⁵ dz = 5π/(128a⁷),
     * F = 45 μ0² p² σ w v / (1024 a⁴) — the falling-magnet problem's drag.
     */
    @Test
    void dipoleInAThinTube() {
        double side = 0.004, br = 1.3, a = 0.02, w = 0.001;
        CuboidMagnet m = CuboidMagnet.axisAligned(Vec3.ZERO, side, side, side, Vec3.Z, br);
        double p = br / PhysicalConstants.MU0 * side * side * side;
        var tube = EddyCurrents.tube(Vec3.ZERO, Vec3.Z, a, w, 0.6, 24, 600);
        double k = EddyCurrents.coefficient(tube, m, q -> Vec3.Z); // per unit conductivity
        double exact = 45 * Math.pow(PhysicalConstants.MU0 * p, 2) * w / (1024 * Math.pow(a, 4));
        System.out.printf("tube: drag %.5g N·s/m per S/m (closed form %.5g)%n", k, exact);
        assertEquals(exact, k, 0.01 * exact);
        // a copper tube (σ = 5.8e7 S/m) and a 4 mm N42 cube of 0.5 g: terminal velocity mg/k
        double vt = 0.0005 * 9.81 / (5.8e7 * k);
        System.out.printf("  terminal velocity of a 0.5 g magnet in 40 mm copper tube, 1 mm wall: %.3f m/s%n", vt);
    }

    /** Motion along the field (normal to the sheet) induces nothing; the drag grows as B², across the sheet. */
    @Test
    void onlyMotionAcrossTheFieldCounts() {
        CuboidMagnet m = CuboidMagnet.axisAligned(new Vec3(0, 0, 0.01), 0.01, 0.01, 0.01, Vec3.Z, 1.2);
        var plate = EddyCurrents.plate(Vec3.ZERO, 2, 0.04, 0.04, 0.001, 8);
        double across = EddyCurrents.coefficient(plate, m, q -> Vec3.X), along = EddyCurrents.coefficient(plate, m, q -> Vec3.Z);
        assertTrue(across > 0);
        assertEquals(0, along, 1e-20, "moving along the plate's normal induces no drag");
        CuboidMagnet strong = CuboidMagnet.axisAligned(new Vec3(0, 0, 0.01), 0.01, 0.01, 0.01, Vec3.Z, 2.4);
        assertEquals(4 * across, EddyCurrents.coefficient(plate, strong, q -> Vec3.X), 1e-9 * across, "∝ B²");
    }
}
