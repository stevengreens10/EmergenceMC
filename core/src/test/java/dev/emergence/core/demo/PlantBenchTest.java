package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The power station's engine on its brake (-Ddiag.plantBench=<brake forces, N, comma-separated>): the plant alone, the
 * brake on from the start at each force in turn; its speed and the power the brake takes once settled, the boiler's
 * pressure. What the engine can give its generator at its governed speed.
 */
class PlantBenchTest {
    @Test
    @EnabledIfSystemProperty(named = "diag.plantBench", matches = ".+")
    void onItsBrake() {
        double settle = Double.parseDouble(System.getProperty("diag.pb.settle", "40")), span = Double.parseDouble(System.getProperty("diag.pb.span", "20"));
        for (String f : System.getProperty("diag.plantBench").split(",")) {
            System.setProperty("diag.brakeForce", f.trim());
            Exhibit e = StationExhibit.station(false);
            Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
            CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
            long cond = b.id(e, "condenser"), shaft = b.id(e, "shaft"), brake = b.id(e, "brake"), boiler = b.id(e, "boiler"), safety = b.id(e, "safety");
            if (Double.parseDouble(f.trim()) > 0) c.toggleSwitch(brake);
            long wall = System.nanoTime();
            while (c.time() < settle) c.step(0.05);
            double w = 0, pw = 0, pb = 0, vent = 0, pc = 0;
            int n = 0;
            while (c.time() < settle + span) {
                c.step(0.05);
                w += Math.abs(c.probe(shaft).angularVelocity());
                double[] br = c.brake(brake);
                pw += br == null ? 0 : br[1];
                pb += c.pressure(boiler) - b.assembly().ambientPressure();
                pc += c.pressure(cond);
                var sb = c.branchesOf(safety);
                if (!sb.isEmpty()) vent += sb.get(0).massFlow(c.solverView());
                n++;
            }
            System.out.printf("plant: brake %s N: %.2f rad/s, the brake taking %.2f W; boiler %.2f bar gauge, safety valve %.3f g/s; condenser %.1f kPa; %.0f s of wall time%n",
                    f.trim(), w / n, pw / n, pb / n / 1e5, 1e3 * vent / n, pc / n / 1e3, (System.nanoTime() - wall) / 1e9);
        }
    }
}
