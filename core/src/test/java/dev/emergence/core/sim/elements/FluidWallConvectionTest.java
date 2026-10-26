package dev.emergence.core.sim.elements;

import dev.emergence.core.fluids.FluidDatabase;
import dev.emergence.core.fluids.WaterSteam;
import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The wall of a water/steam volume. */
class FluidWallConvectionTest {
    /**
     * A Newton iterate may take a volume below the triple point (here the iterate that failed the power station's start,
     * 238 K under a 330 K wall). The heat must stay finite there, so the step control can reject the step: with the
     * liquid's properties taken at that temperature its c_p was 0, the boiling law 0/0, and the solve failed outright.
     */
    @Test
    void heatStaysFiniteBelowTheTriplePoint() {
        var fluid = FluidDatabase.standard().byCode(7);
        assertInstanceOf(WaterSteam.class, fluid);
        Network net = new Network();
        Node p = net.node(Domain.FLUID, "p", 2e3), t = net.node(Domain.THERMAL, "T", 293.15), wall = net.node(Domain.THERMAL, "w", 293.15);
        FluidVolume v = net.add(new FluidVolume(p, t, fluid, 2.4e-4)).withPhase(0.8);
        FluidWallConvection w = new FluidWallConvection(t, wall, fluid, 0.016, 0.06, 2.8e-3, List.of()).withTwoPhase(v, 0.06);
        double q = w.twoPhaseHeat(330, 238.09, 0.805, 394);
        assertTrue(Double.isFinite(q), "heat into the volume: " + q);
        assertTrue(q > 0, "the warmer wall heats it: " + q);
    }
}
