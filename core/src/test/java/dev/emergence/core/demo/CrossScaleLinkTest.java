package dev.emergence.core.demo;

import dev.emergence.core.assembly.Assembly;
import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Links across scales (Assembly.Link): parts that touch in the world but lie in separate grids — here two regions of
 * one assembly, 40 cells apart, as a joined island holds its miniature and (shifted) full-size parts.
 */
class CrossScaleLinkTest {
    static final int FAR = 40;

    @Test
    void aCoupledShaftTurnsWithTheOneCranked() {
        Exhibit e = new Exhibit("shafts", "Shafts", List.of(), "");
        String st = "steel_1045";
        int a = e.fill(SHAFT, st, 4, 0, 8, 4, 5, 8, Dir.UP, "diameter", 0.015);
        e.add(BEARING, st, 4, 0, 8, Dir.UP);
        e.name("crank", e.add(CRANK, st, 4, 6, 8, Dir.UP, "radius", 0.12));
        int b = e.fill(SHAFT, st, 4 + FAR, 0, 8, 4 + FAR, 5, 8, Dir.UP, "diameter", 0.015);
        e.add(BEARING, st, 4 + FAR, 0, 8, Dir.UP);
        e.add(BLOCK, st, 4 + FAR, 3, 8, Dir.UP, "fill", 0.5); // a disc on it: inertia to drive
        e.name("a", a);
        e.name("b", b);
        Exhibits.Built bt = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        bt.assembly().link(new Assembly.Link(Assembly.LinkKind.SHAFT, bt.id(e, "a"), new int[]{4, 5, 8}, Dir.UP,
                bt.id(e, "b"), new int[]{4 + FAR, 5, 8}, Dir.UP, 1));
        CompiledAssembly c = AssemblyCompiler.compile(bt.assembly(), bt.state());
        assertEquals(1, c.shaftCouplings(), "the coupling was made: " + c.warnings());
        for (int i = 0; i < 40; i++) {
            c.setCrankTorque(bt.id(e, "crank"), 0.5);
            c.step(0.05);
        }
        double wa = c.probe(bt.id(e, "a")).angularVelocity(), wb = c.probe(bt.id(e, "b")).angularVelocity();
        assertTrue(Math.abs(wa) > 1, "the cranked shaft turns: " + wa);
        assertEquals(wa, wb, 0.02 * Math.abs(wa), "the coupled one turns with it: " + wa + " vs " + wb);
    }

    @Test
    void aLinkedPipeCarriesAirAcrossAndNeitherEndVents() {
        Exhibit e = new Exhibit("pipes", "Pipes", List.of(), "");
        String st = "steel_1045";
        e.name("hi", e.add(TANK, st, 2, 0, 2, Dir.UP, "pressure", 3e5));
        e.name("pa", e.add(PIPE, "copper", 3, 0, 2, Dir.EAST));
        e.name("pb", e.add(PIPE, "copper", 3 + FAR, 0, 2, Dir.EAST));
        e.name("lo", e.add(TANK, st, 4 + FAR, 0, 2, Dir.UP, "pressure", 1e5));
        Exhibits.Built bt = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        // pa's east end against pb's west end, across the face
        bt.assembly().link(new Assembly.Link(Assembly.LinkKind.FLUID, bt.id(e, "pa"), new int[]{3, 0, 2}, Dir.EAST,
                bt.id(e, "pb"), new int[]{3 + FAR, 0, 2}, Dir.WEST, 1));
        CompiledAssembly c = AssemblyCompiler.compile(bt.assembly(), bt.state());
        assertTrue(c.vents().isEmpty(), "no open ends: " + c.vents().size());
        java.util.function.DoubleSupplier mass = () -> {
            double m = 0;
            for (String n : new String[]{"hi", "pa", "pb", "lo"}) {
                double[] f = c.fluid(bt.id(e, n));
                if (f != null) m += f[2];
            }
            return m;
        };
        double before = mass.getAsDouble();
        for (int i = 0; i < 100; i++) c.step(0.05);
        double ph = c.fluid(bt.id(e, "hi"))[0], pl = c.fluid(bt.id(e, "lo"))[0];
        System.out.printf("linked pipe: tanks %.0f / %.0f Pa (%.1f / %.1f K); air %.6g -> %.6g kg; pipes %s / %s%n", ph, pl,
                c.fluid(bt.id(e, "hi"))[1], c.fluid(bt.id(e, "lo"))[1], before, mass.getAsDouble(),
                java.util.Arrays.toString(c.fluid(bt.id(e, "pa"))), java.util.Arrays.toString(c.fluid(bt.id(e, "pb"))));
        assertTrue(Math.abs(ph - pl) < 0.05e5, "the tanks' pressures came together: " + ph + ", " + pl);
        // (not the pressures' sum: the full tank's air cools as it expands and the other's warms as it is filled)
        assertEquals(before, mass.getAsDouble(), 1e-6 * before, "no air lost on the way");
    }

    @Test
    void heatCrossesALinkedContact() {
        Exhibit e = new Exhibit("heat", "Heat", List.of(), "");
        e.name("hot", e.add(BLOCK, "copper", 2, 0, 10, Dir.UP));
        e.init(e.named.get("hot"), new Exhibit.Initial(400, 0, 0));
        e.name("cold", e.add(BLOCK, "copper", 2 + FAR, 0, 10, Dir.UP));
        Exhibits.Built bt = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        Exhibits.Built bare = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        bt.assembly().link(new Assembly.Link(Assembly.LinkKind.THERMAL, bt.id(e, "hot"), new int[]{2, 0, 10}, Dir.EAST,
                bt.id(e, "cold"), new int[]{2 + FAR, 0, 10}, Dir.WEST, 1));
        CompiledAssembly c = AssemblyCompiler.compile(bt.assembly(), bt.state()), u = AssemblyCompiler.compile(bare.assembly(), bare.state());
        // one cell of copper each (842 J/K) through a cell's face of contact (~11 W/K): τ = C/2G ≈ 38 s
        for (int i = 0; i < 3000; i++) {
            c.step(0.05);
            u.step(0.05);
        }
        double cold = c.probe(bt.id(e, "cold")).temperature(), coldAlone = u.probe(bare.id(e, "cold")).temperature();
        double hot = c.probe(bt.id(e, "hot")).temperature();
        assertTrue(cold > coldAlone + 20, "the linked cold block warmed: " + cold + " K (alone " + coldAlone + " K)");
        assertTrue(hot - cold < 10, "in 150 s (four time constants) the two came together: " + hot + " and " + cold);
    }
}
