package dev.emergence.core.assembly;

import dev.emergence.core.materials.MaterialDatabase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Batteries and plating from grid parts: electrodes are just conductors touching an electrolyte. */
class ElectrochemicalAssemblyTest {
    long id = 1;
    final Assembly asm = new Assembly();

    Part add(PartKind k, String m, int x0, int y0, int z0, int x1, int y1, int z1, Dir axis, Object... kv) {
        Map<String, Double> pr = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) pr.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        Part p = new Part(id++, k, MaterialDatabase.standard().get(m), x0, y0, z0, x1, y1, z1, axis, pr);
        asm.add(p);
        return p;
    }

    @Test
    void daniellCellFromPartsPowersAResistor() {
        Part znSol = add(PartKind.ELECTROLYTE, "zinc_sulfate_1m", 0, 0, 0, 2, 2, 2, Dir.UP);
        Part cuSol = add(PartKind.ELECTROLYTE, "copper_sulfate_1m", 3, 0, 0, 5, 2, 2, Dir.UP);
        Part zn = add(PartKind.BLOCK, "zinc", -1, 0, 0, -1, 2, 2, Dir.EAST, "fill", 0.3);
        Part cu = add(PartKind.BLOCK, "copper", 6, 0, 0, 6, 2, 2, Dir.EAST, "fill", 0.3);
        add(PartKind.WIRE, "copper", -1, 3, 1, -1, 3, 1, Dir.UP);
        add(PartKind.WIRE, "copper", 6, 3, 1, 6, 3, 1, Dir.UP);
        for (int x = -1; x <= 6; x++) {
            if (x == 2) continue;
            add(PartKind.INSULATED_WIRE, "copper", x, 4, 1, x, 4, 1, Dir.UP);
        }
        Part load = add(PartKind.RESISTOR, "alumina", 2, 4, 1, 2, 4, 1, Dir.EAST, "resistance", 10.0);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        c.step(0.05);
        double vCell = c.probe(cu.id()).voltage() - c.probe(zn.id()).voltage();
        assertTrue(vCell > 0.5 && vCell < 1.11, "copper is the positive terminal of a Daniell cell: " + vCell);
        assertTrue(Math.abs(c.probe(load.id()).current()) > 0.02, "current through the load: " + c.probe(load.id()).current());
        double cu0 = c.chemistry(cuSol.id()).get("[Cu2+] mol/L");
        for (int i = 0; i < 1200; i++) c.step(0.05); // 1 minute
        assertTrue(c.chemistry(zn.id()).get("metal dissolved mol") > 0, "zinc anode dissolves");
        assertTrue(c.chemistry(cuSol.id()).get("[Cu2+] mol/L") < cu0, "copper ions plate out");
        assertTrue(c.chemistry(znSol.id()).get("[Zn2+] mol/L") > 1.0, "zinc ions accumulate");
        AssemblyState st = c.exportState();
        CompiledAssembly c2 = CompiledAssembly.recompile(asm, c);
        assertEquals(c.chemistry(cuSol.id()).get("[Cu2+] mol/L"), c2.chemistry(cuSol.id()).get("[Cu2+] mol/L"), 1e-9, "chemistry state persists");
        assertFalse(st.chemistry().isEmpty());
    }

    @Test
    void zincInCopperSulfateGetsCopperPlated() {
        Part sol = add(PartKind.ELECTROLYTE, "copper_sulfate_1m", 0, 0, 0, 2, 2, 2, Dir.UP);
        Part zn = add(PartKind.BLOCK, "zinc", 3, 0, 0, 3, 2, 2, Dir.EAST, "fill", 0.3);
        CompiledAssembly c = AssemblyCompiler.compile(asm);
        for (int i = 0; i < 1200; i++) c.step(0.05);
        double plated = c.depositMoles(zn.id());
        double dissolved = c.chemistry(zn.id()).get("metal dissolved mol");
        assertTrue(plated > 1e-5, "copper displaces zinc without any circuit: " + plated);
        assertEquals(dissolved, plated, 0.05 * plated, "Zn + Cu2+ -> Zn2+ + Cu (1:1)");
        assertTrue(c.chemistry(sol.id()).get("[Zn2+] mol/L") > 0, "zinc ions appear in the copper sulfate");
    }
}
