package dev.emergence.core.assembly;

import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a world island holds that its exhibit's definition does not, cell by cell: run with
 * {@code -Ddiag.dump=<island-dump file> -Ddiag.dumpExhibit=<exhibit id>}. The dump is lined up with the exhibit by its
 * lowest, westmost, northmost cell; each cell's parts (kind and material) are compared, and the parameters of each
 * part found in both.
 */
class IslandDumpDiffTest {
    @Test
    @EnabledIfSystemProperty(named = "diag.dumpExhibit", matches = ".+")
    void diff() throws Exception {
        java.util.List<Exhibit> known = new java.util.ArrayList<>(Exhibits.all());
        known.add(dev.emergence.core.demo.StationExhibit.station());
        Exhibit e = known.stream().filter(x -> x.id.equals(System.getProperty("diag.dumpExhibit"))).findFirst().orElseThrow();
        Map<String, String> core = new TreeMap<>(), world = new TreeMap<>();
        int[] cmin = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (Exhibit.PartDef d : e.parts) {
            cmin[0] = Math.min(cmin[0], d.x0());
            cmin[1] = Math.min(cmin[1], d.y0());
            cmin[2] = Math.min(cmin[2], d.z0());
        }
        for (Exhibit.PartDef d : e.parts)
            for (int x = d.x0(); x <= d.x1(); x++)
                for (int y = d.y0(); y <= d.y1(); y++)
                    for (int z = d.z0(); z <= d.z1(); z++)
                        core.merge(key(x - cmin[0], y - cmin[1], z - cmin[2]), d.kind() + "/" + d.material() + " " + d.axis() + " " + new TreeMap<>(d.params()), (a, b) -> a + " + " + b);
        var lines = Files.readAllLines(Path.of(System.getProperty("diag.dump")));
        int[] wmin = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (String l : lines) {
            String[] f = l.split(" ");
            if (!f[0].equals("P")) continue;
            for (int k = 0; k < 3; k++) wmin[k] = Math.min(wmin[k], Integer.parseInt(f[4 + k]));
        }
        for (String l : lines) {
            String[] f = l.split(" ");
            if (!f[0].equals("P")) continue;
            Map<String, Double> prm = new TreeMap<>();
            for (int i = 12; i < f.length; i++) {
                int eq = f[i].indexOf('=');
                if (eq > 0) prm.put(f[i].substring(0, eq), Double.parseDouble(f[i].substring(eq + 1)));
            }
            for (int x = Integer.parseInt(f[4]); x <= Integer.parseInt(f[7]); x++)
                for (int y = Integer.parseInt(f[5]); y <= Integer.parseInt(f[8]); y++)
                    for (int z = Integer.parseInt(f[6]); z <= Integer.parseInt(f[9]); z++)
                        world.merge(key(x - wmin[0], y - wmin[1], z - wmin[2]), f[2] + "/" + f[3] + " " + f[10] + " " + prm
                                + (f[11].equals("1") ? " (anchored)" : ""), (a, b) -> a + " + " + b);
        }
        int diffs = 0;
        java.util.Set<String> cells = new java.util.TreeSet<>(core.keySet());
        cells.addAll(world.keySet());
        for (String c : cells) {
            String a = core.get(c), b = world.get(c);
            String bs = b == null ? null : b.replace(" (anchored)", "");
            if (a == null || !a.equals(bs)) {
                System.out.printf("cell %s: core %s | world %s%n", c, a, b);
                diffs++;
            }
        }
        System.out.printf("%d of %d cells differ (core min %d %d %d, world min %d %d %d)%n", diffs, cells.size(), cmin[0], cmin[1], cmin[2],
                wmin[0], wmin[1], wmin[2]);
    }

    private static String key(int x, int y, int z) {
        return String.format("%3d %3d %3d", x, y, z);
    }
}
