package dev.emergence.core.assembly;

import dev.emergence.core.materials.MaterialDatabase;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A brushed DC motor assembled from primitives: stator magnets, rotor coils, a two-segment copper
 * commutator on an insulating hub and two graphite brushes. Commutation emerges from the brush/segment
 * coverage geometry; nothing knows it is a motor.
 */
class DcMotorEmergenceTest {
    long id = 1;
    final Assembly asm = new Assembly();
    final Set<Long> anchors = new HashSet<>();

    Part add(PartKind k, String m, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis, Object... kv) {
        java.util.Map<String, Double> pr = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) pr.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        Part p = new Part(id++, k, MaterialDatabase.standard().get(m), x0, y0, z0, x1, y1, z1, axis, pr);
        asm.add(p);
        if (y0 == 0 && k.structural && k != PartKind.SHAFT) anchors.add(p.id());
        return p;
    }

    Part add(PartKind k, String m, int x, int y, int z, Dir axis, Object... kv) {
        return add(k, m, x, y, z, x, y, z, axis, kv);
    }

    /** Builds the motor; returns {shaft, capacitor}. */
    Part[] build(double volts) {
        String st = "steel_1045", ny = "nylon_66";
        add(PartKind.BLOCK, st, 9, 0, 8, 9, 1, 8, Dir.UP);
        add(PartKind.BLOCK, st, 7, 0, 8, 7, 1, 8, Dir.UP);
        Part shaft = add(PartKind.SHAFT, st, 8, 1, 8, 8, 12, 8, Dir.UP, "diameter", 0.02);
        add(PartKind.BEARING, st, 8, 1, 8, Dir.UP);
        add(PartKind.BEARING, st, 8, 12, 8, Dir.UP);
        add(PartKind.BLOCK, st, 11, 0, 12, 11, 11, 12, Dir.UP);   // frame columns
        add(PartKind.BLOCK, st, 5, 0, 12, 5, 11, 12, Dir.UP);
        add(PartKind.BLOCK, st, 9, 12, 7, 11, 12, 12, Dir.UP);   // top plates
        add(PartKind.BLOCK, st, 5, 12, 7, 7, 12, 12, Dir.UP);
        add(PartKind.BLOCK, st, 9, 3, 7, 11, 3, 11, Dir.UP);     // stator plates
        add(PartKind.BLOCK, st, 5, 3, 7, 7, 3, 11, Dir.UP);
        add(PartKind.MAGNET, "ndfeb_n42", 10, 4, 8, Dir.UP);
        add(PartKind.MAGNET, "ndfeb_n42", 6, 4, 8, Dir.DOWN);
        // rotor: insulating disk carrying two coils; commutator = nylon hub with two copper segment rows
        add(PartKind.BLOCK, ny, 6, 7, 6, 10, 7, 10, Dir.UP, "fill", 0.3);
        add(PartKind.COIL, "copper", 10, 5, 8, 10, 6, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3);
        add(PartKind.COIL, "copper", 6, 5, 8, 6, 6, 8, Dir.UP, "turns", 300, "wire_diameter", 0.8e-3);
        add(PartKind.BLOCK, ny, 8, 8, 8, Dir.UP);                                   // hub on the shaft
        add(PartKind.BLOCK, "copper", 7, 8, 9, 9, 8, 9, Dir.EAST, "fill", 0.6);    // segment S1
        add(PartKind.BLOCK, "copper", 7, 8, 7, 9, 8, 7, Dir.EAST, "fill", 0.6);    // segment S2
        // rotor wiring: S1 → coil A top, coil A bottom → coil B bottom, coil B top → S2
        add(PartKind.WIRE, "copper", 8, 9, 9, Dir.UP);
        int[][] a = {{8, 9, 10}, {8, 9, 11}, {8, 8, 11}, {8, 7, 11}, {8, 6, 11}, {9, 6, 11}, {10, 6, 11},
                {10, 6, 10}, {11, 6, 10}, {11, 6, 9}, {11, 6, 8}};
        for (int[] c : a) add(PartKind.INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP);
        add(PartKind.WIRE, "copper", 8, 9, 7, Dir.UP);
        int[][] b = {{8, 9, 6}, {8, 9, 5}, {8, 8, 5}, {8, 7, 5}, {8, 6, 5}, {7, 6, 5}, {6, 6, 5}, {6, 6, 6}, {6, 6, 7}};
        for (int[] c : b) add(PartKind.INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP);
        int[][] mid = {{10, 5, 9}, {9, 5, 9}, {8, 5, 9}, {7, 5, 9}, {6, 5, 9}};
        for (int[] c : mid) add(PartKind.INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP);
        // brushes on insulating holders hanging from the top plates; at θ = 0 they face the segment gap
        add(PartKind.BRUSH, "graphite", 10, 8, 8, Dir.WEST);
        add(PartKind.BLOCK, ny, 10, 9, 8, 10, 11, 8, Dir.UP);
        add(PartKind.BRUSH, "graphite", 6, 8, 8, Dir.EAST);
        add(PartKind.BLOCK, ny, 6, 9, 8, 6, 11, 8, Dir.UP);
        // supply: pre-charged capacitor bank between the brushes, routed outside the frame
        int[][] sup = {{11, 8, 8}, {12, 8, 8}, {12, 8, 9}, {12, 8, 10}, {12, 8, 11}, {12, 8, 12}, {12, 8, 13},
                {11, 8, 13}, {10, 8, 13}, {9, 8, 13}, {7, 8, 13}, {6, 8, 13}, {5, 8, 13}, {4, 8, 13},
                {4, 8, 12}, {4, 8, 11}, {4, 8, 10}, {4, 8, 9}, {4, 8, 8}, {5, 8, 8}};
        for (int[] c : sup) add(PartKind.INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP);
        Part cap = add(PartKind.CAPACITOR, "alumina", 8, 8, 13, Dir.EAST, "capacitance", 1.0);
        asm.anchoredWhen(p -> anchors.contains(p.id()));
        AssemblyState init = AssemblyState.empty();
        init.capacitorVoltages().put(cap.id(), volts);
        init.bodyMotion().put(shaft.id(), new double[]{0, 0.3}); // start a little past the dead point
        this.state = init;
        return new Part[]{shaft, cap};
    }

    AssemblyState state;

    @Test
    void commutatedMotorRunsContinuouslyInOneDirection() {
        Part[] p = build(24);
        CompiledAssembly c = AssemblyCompiler.compile(asm, state);
        assertEquals(2, c.bodies().size(), "frame + rotor; " + c.warnings());
        double th0 = 0;
        double minW = Double.MAX_VALUE, maxW = -Double.MAX_VALUE;
        for (int i = 0; i < 100; i++) {
            c.step(0.05);
            double w = c.probe(p[0].id()).angularVelocity();
            if (i == 20) th0 = c.probe(p[0].id()).angle();
            if (i > 20) {
                minW = Math.min(minW, w);
                maxW = Math.max(maxW, w);
            }
        }
        double turned = c.probe(p[0].id()).angle() - th0;
        System.out.printf("DC motor: turned %.2f rad in 2 s, ω range [%.2f, %.2f], cap %.2f V%n",
                turned, minW, maxW, c.probe(p[1].id()).voltage());
        assertTrue(Math.abs(turned) > 2 * Math.PI, "should make more than a full revolution (two commutations), turned " + turned);
        assertTrue(minW * maxW > 0, "never reverses: ω range [" + minW + ", " + maxW + "]");
        assertTrue(Math.abs(c.probe(p[1].id()).voltage()) < 24, "draws energy from the capacitor");
    }
}
