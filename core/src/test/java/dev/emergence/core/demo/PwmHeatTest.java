package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PWM bridge left running, as the world runs it for as long as its chunks are loaded. Its control circuit once
 * shared the battery with the bridge: each switching edge sagged the supply, the knob's voltage with it, and held the
 * comparator half-way across its threshold for ~12 ms — both transistors of the left pair conducting ~12 A at once,
 * 3.5 W on average in the lower one, which reached 448 K and was destroyed in the world (510 K here in 300 s). With
 * the control circuit on its own RC-filtered supply the edges are fast and the bridge stays cool.
 */
class PwmHeatTest {
    @Test
    void theBridgeStaysCool() {
        Exhibit e = ControlExhibits.pwmMotor();
        Exhibits.Built b = Exhibits.build(e, DemoExhibitsTest.AMBIENT);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        String[] names = {"pA", "nA", "pB", "nB"};
        double[] hot = new double[4];
        for (int i = 0; i < 12_000; i++) {
            c.step(0.01);
            for (int k = 0; k < 4; k++) hot[k] = Math.max(hot[k], c.probe(b.id(e, names[k])).temperature());
        }
        System.out.printf("PWM bridge after %.0f s: rotor %.1f rad/s; hottest pA %.1f K, nA %.1f K, pB %.1f K, nB %.1f K%n", c.time(),
                c.probe(b.id(e, "rotor")).angularVelocity(), hot[0], hot[1], hot[2], hot[3]);
        for (int k = 0; k < 4; k++) assertTrue(hot[k] < 320, names[k] + " ran hot: " + hot[k] + " K (it was 473 K at 120 s, shooting through)");
        assertTrue(Math.abs(c.probe(b.id(e, "rotor")).angularVelocity()) > 5, "the motor runs");
    }
}
