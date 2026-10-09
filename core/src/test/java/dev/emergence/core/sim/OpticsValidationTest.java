package dev.emergence.core.sim;

import dev.emergence.core.assembly.*;
import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.core.demo.Router;
import dev.emergence.core.math.Vec3;
import dev.emergence.core.sim.elements.*;
import dev.emergence.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Photodiodes and optical links: responsivity, photocurrent, photovoltaic voltage, beam geometry and occlusion. */
class OpticsValidationTest {
    @Test
    void siliconResponsivity() {
        assertEquals(0.8 * 850 / 1239.84, Photodiode.responsivity(850), 1e-9);
        assertEquals(0.41, Photodiode.responsivity(630), 0.01);
        assertEquals(0, Photodiode.responsivity(1150), 0, "beyond the band edge");
        assertTrue(Photodiode.responsivity(450) < Photodiode.responsivity(650), "blue is absorbed near the surface");
    }

    @Test
    void ledLightBecomesPhotocurrentAndTheLedgerCloses() {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL), a = n.node(Domain.ELECTRICAL, "led+");
        n.add(new FlowSource(gnd, a, 0.02)); // drive the LED with 20 mA
        Led led = n.add(new Led(a, gnd, 850, 0.3, null));
        Node pa = n.node(Domain.ELECTRICAL, "pd anode"), pk = n.node(Domain.ELECTRICAL, "pd cathode");
        Photodiode pd = n.add(new Photodiode(pa, pk, 7.5e-6, null));
        double k = 1e-3;
        pd.addSource(led, th -> k, null);
        n.add(new VoltageSource(pk, pa, 0)); // short circuit
        TransientSolver s = new TransientSolver(n);
        s.advance(1e-3);
        double popt = led.opticalPower(s);
        assertEquals(0.3 * (1239.84 / 850) * 0.02, popt, 0.02 * popt, "LED optical power η·E·I");
        assertEquals(-Photodiode.responsivity(850) * k * popt, pd.current(s), 1e-3 * Photodiode.responsivity(850) * k * popt,
                "short-circuit photocurrent R·k·P");
    }

    @Test
    void solarCellOpenCircuitVoltageFollowsTheDiodeLaw() {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL), an = n.node(Domain.ELECTRICAL, "cell+");
        double area = 1e-2;
        Photodiode cell = n.add(new Photodiode(an, gnd, area, null));
        cell.setAmbientPower(1000 * area); // full sun on 100 cm²
        n.add(new Resistor(an, gnd, 1e9, null)); // open circuit (voltmeter)
        TransientSolver s = new TransientSolver(n);
        s.advance(1e-3);
        double iph = 0.5 * 1000 * area, is = 1e-14 * area / 1e-6, vt = PhysicalConstants.K_OVER_Q * 300;
        assertEquals(vt * Math.log(iph / is + 1), s.x(an), 2e-3, "V_oc = V_T ln(I_ph/I_s + 1)");
        assertTrue(s.x(an) > 0.5 && s.x(an) < 0.8, "a silicon cell's open-circuit voltage: " + s.x(an));
    }

    /** An LED facing up at y = 1 and a photodiode facing down at y = 5, with an optional part in between. */
    static Exhibit beam(String between) {
        Exhibit e = new Exhibit("beam", "beam", java.util.List.of("x"), "x");
        e.name("led", e.add(LED, "silicon", 4, 1, 4, Dir.EAST, "wavelength", 850, "efficiency", 0.3, "beam", 20));
        e.name("pd", e.add(PHOTODIODE, "silicon", 4, 5, 4, Dir.EAST, "face", 0));
        if (between != null) e.add(BLOCK, between, 4, 3, 4, Dir.UP, "fill", 0.1);
        int bat = e.add(CAPACITOR, "alumina", 8, 0, 4, Dir.EAST, "capacitance", 10);
        e.init(bat, new Exhibit.Initial(Double.NaN, 0, 5));
        e.add(RESISTOR, "alumina", 8, 1, 7, Dir.EAST, "resistance", 150);
        e.add(RESISTOR, "alumina", 8, 4, 7, Dir.EAST, "resistance", 1e6);
        Router r = new Router(e, 0, 0, 0, 12, 7, 10);
        r.pin("v", 9, 0, 4).pin("v", 7, 1, 7).pin("v", 4, 5, 5);   // supply → LED resistor; PD cathode (+)
        r.pin("ledA", 9, 1, 7).pin("ledA", 3, 1, 4);                 // resistor → LED anode (−)
        r.pin("g", 7, 0, 4).pin("g", 5, 1, 4).pin("g", 9, 4, 7);     // LED cathode, load to ground
        r.pin("sig", 3, 5, 4).pin("sig", 7, 4, 7);                   // PD anode → load
        r.route();
        return e;
    }

    @Test
    void beamCouplingFollowsTheGeometryAndIsBlockedByOpaqueParts() {
        double[] seen = new double[3];
        String[] between = {null, "steel_1045", "glass"};
        for (int k = 0; k < 3; k++) {
            Exhibit e = beam(between[k]);
            Exhibits.Built b = Exhibits.build(e, 293.15);
            CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
            assertTrue(c.warnings().isEmpty(), c.warnings().toString());
            c.step(0.01);
            seen[k] = c.lightInput(b.id(e, "pd")) / c.lightOutput(b.id(e, "led"));
        }
        double d = (4 - 0.6) * Part.CELL, m = 20;
        double geo = (m + 1) / (2 * Math.PI) * 7.5e-6 / (d * d);
        assertEquals(geo, seen[0], 1e-6 * geo, "intensity (m+1)/2π · A/d² on the axis");
        assertEquals(0, seen[1], 1e-15, "a steel plate blocks the beam");
        assertEquals(geo, seen[2], 1e-6 * geo, "glass lets it through");
    }
}
