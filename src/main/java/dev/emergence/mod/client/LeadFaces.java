package dev.emergence.mod.client;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.Part;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.part.PlacedPart;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where a two-terminal component's leads come out: its ends along its axis, and — a part one cell in size — its sides
 * along its lead axis too. A wire against any of those faces joins that lead, so a wire of another net laid beside a
 * component's side joins it as surely as one at its end.
 */
public final class LeadFaces {
    private LeadFaces() {}

    /** The parts an empty hand asks this of (a switch's click toggles it instead). */
    public static boolean shows(PartKind k) {
        return switch (k) {
            case RESISTOR, CAPACITOR, BATTERY, DIODE, LED, PHOTODIODE, GM_TUBE, COIL -> true;
            default -> false;
        };
    }

    /** "Resistor — + lead: east end, south side · − lead: west end, north side". */
    static String describe(BlockPos pos, int resolution, PlacedPart part) {
        Part p = part.toCore(pos, resolution);
        List<String> plus = new ArrayList<>(), minus = new ArrayList<>();
        for (int x = p.minX(); x <= p.maxX(); x++)
            for (int y = p.minY(); y <= p.maxY(); y++)
                for (int z = p.minZ(); z <= p.maxZ(); z++)
                    for (Dir d : Dir.values()) {
                        if (p.containsCell(x + d.dx, y + d.dy, z + d.dz)) continue;
                        String lead = AssemblyCompiler.leadAt(p, x, y, z, d);
                        if (lead == null) continue;
                        String where = d.name().toLowerCase(Locale.ROOT) + (d.axis() == p.axis().axis() ? " end" : " side");
                        List<String> list = lead.equals("+") ? plus : minus;
                        if (!list.contains(where)) list.add(where);
                    }
        String name = part.kind.name().charAt(0) + part.kind.name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        return name + " — + lead: " + (plus.isEmpty() ? "none" : String.join(", ", plus)) + " · − lead: "
                + (minus.isEmpty() ? "none" : String.join(", ", minus)) + " (a wire against any of these joins it)";
    }
}
