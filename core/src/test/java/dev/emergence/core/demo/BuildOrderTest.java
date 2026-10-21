package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a machine is must not depend on the order its parts were placed in: a player builds in any order, and the
 * world holds parts in that order with ids of its own. Every exhibit, compiled from shuffled parts, has the same
 * bodies, slides and warnings as compiled in its definition's order.
 */
class BuildOrderTest {
    static String summary(CompiledAssembly c) {
        long slides = c.bodies().stream().filter(CompiledAssembly.Body::slider).count();
        List<String> w = new ArrayList<>();
        for (String s : c.warnings()) w.add(s.replaceAll("#-?[0-9]+", "#"));
        w.sort(null);
        return c.bodies().size() + " bodies, " + slides + " slides, warnings " + w;
    }

    @Test
    void everyExhibitIsTheSameMachineWhateverOrderItsPartsWerePlaced() {
        StringBuilder bad = new StringBuilder();
        for (Exhibit e : Exhibits.all()) {
            var b0 = Exhibits.build(e, 293.15);
            String ref = summary(AssemblyCompiler.compile(b0.assembly(), b0.state()));
            for (long seed = 1; seed <= 3; seed++) {
                var b = Exhibits.build(e, 293.15, new Random(seed));
                String got = summary(AssemblyCompiler.compile(b.assembly(), b.state()));
                if (!got.equals(ref)) {
                    bad.append(String.format("%n  %s (order %d): %s; in order: %s", e.id, seed, got, ref));
                    break;
                }
            }
        }
        System.out.println("build order:" + (bad.isEmpty() ? " every exhibit the same machine in any order" : bad));
        assertTrue(bad.isEmpty(), "machines that change with the order their parts were placed in:" + bad);
    }
}
