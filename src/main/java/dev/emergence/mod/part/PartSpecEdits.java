package dev.emergence.mod.part;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.materials.MaterialDatabase;
import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.mod.registry.ModRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The part editor's server side: a player sets the material and parameters of the part item in their hand (what they
 * would make it of, and to what dimensions or ratings). Every way into a part's specification goes through
 * {@link #apply}, the player's screen and the demo builder alike, so whatever an exhibit is made of can be made by a
 * player.
 */
public final class PartSpecEdits {
    private PartSpecEdits() {}

    /**
     * Sets the part item in the player's hand to {@code spec} if it is a valid one: a known material, finite
     * parameters, sizes within a block. Returns null if it was applied, else why not.
     */
    public static String apply(ServerPlayer player, InteractionHand hand, PartSpec spec) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof PartItem)) return "not holding a part";
        String why = validate(spec);
        if (why != null) return why;
        stack.set(ModRegistries.PART_SPEC.get(), spec);
        return null;
    }

    public static String validate(PartSpec spec) {
        if (MaterialDatabase.standard().getOrNull(spec.material()) == null) return "unknown material " + spec.material();
        for (var e : spec.params().entrySet()) {
            if (e.getKey().isBlank() || e.getKey().length() > 32) return "bad parameter name " + e.getKey();
            if (!Double.isFinite(e.getValue())) return e.getKey() + " is not a number";
        }
        return null;
    }

    /** A parameter the editor offers for a kind of part: its key, unit and what it means. */
    public record Param(String key, String unit, String meaning) {}

    private static final Map<String, String[]> KNOWN = new LinkedHashMap<>();

    static {
        // key → {unit, meaning}
        String[][] k = {
                {"diameter", "m", "diameter"}, {"wire", "m", "spring wire diameter"}, {"coil", "m", "coil diameter"},
                {"turns", "", "turns"}, {"preload", "m", "fitted shorter than free by"}, {"wire_diameter", "m", "winding wire diameter"},
                {"resistance", "Ω", "resistance"}, {"capacitance", "F", "capacitance"}, {"inductance", "H", "inductance"},
                {"travel", "m or °", "travel (slide stop collars, m; potentiometer electrical angle, °)"},
                {"travel_fwd", "m", "stop collar along the bushing's axis"}, {"travel_back", "m", "stop collar against the bushing's axis"},
                {"wiper", "", "potentiometer wiper's place as fitted (0 at A … 1 at B)"},
                {"fill", "", "fraction of the cell filled (a plate's thickness)"}, {"radius", "m", "radius"},
                {"throw", "m", "crank throw"}, {"phase", "°", "cam phase"}, {"lap", "", "valve lap"},
                {"bore", "m", "bore"}, {"wall", "m", "wall thickness"}, {"pressure", "Pa", "initial pressure"},
                {"fluid", "", "fluid code"}, {"teeth", "", "teeth"}, {"pitch_radius", "m", "pitch radius"},
                {"module", "m", "tooth module"}, {"face", "", "facing (0 down, 1 up, 2 north, 3 south, 4 west, 5 east)"},
                {"lead", "m", "lead per turn"}, {"left", "", "1: left-hand thread"}, {"lubricated", "", "1: lubricated"},
                {"force", "N", "spring force"}, {"pitch", "°", "pitch"}, {"chord", "m", "chord"}, {"thickness", "m", "thickness"},
                {"width", "m", "width"}, {"drag", "", "profile drag coefficient"}, {"blade_angle", "°", "blade angle"},
                {"wavelength", "nm", "wavelength"}, {"beam", "°", "beam angle"}, {"area", "m²", "area"},
                {"type", "", "device type"}, {"function", "", "logic function"}, {"transconductance", "S", "transconductance"},
                {"lead_axis", "", "lead axis (0 x, 1 y, 2 z)"}, {"gbw", "Hz", "gain–bandwidth"}, {"slew", "V/s", "slew rate"},
                {"nuclide", "", "nuclide code"}, {"activity", "Bq", "activity"}, {"relief", "Pa", "relief valve cracking pressure"},
                {"viscosity", "Pa·s", "grease viscosity"}, {"contact_area", "m²", "contact area"}, {"surface_area", "m²", "surface area"},
                {"mass", "kg", "mass"}, {"max_temperature", "K", "temperature limit"}, {"flexible", "", "1: flexible"},
                {"stop_stiffness", "N·m/rad", "end stop stiffness"}, {"weir", "m", "outlet weir height"}, {"gauge_range", "bar", "gauge range"},
        };
        for (String[] e : k) KNOWN.put(e[0], new String[]{e[1], e[2]});
    }

    /**
     * The parameters to offer for a kind of part: those any exhibit or preset uses on it, then whatever the item
     * already carries.
     */
    public static List<Param> params(PartKind kind, PartSpec current) {
        TreeSet<String> keys = new TreeSet<>();
        for (Exhibit e : Exhibits.all()) for (Exhibit.PartDef d : e.parts) if (d.kind() == kind) keys.addAll(d.params().keySet());
        keys.addAll(current.params().keySet());
        List<Param> out = new ArrayList<>();
        for (String key : keys) {
            String[] u = KNOWN.getOrDefault(key, new String[]{"", ""});
            out.add(new Param(key, u[0], u[1]));
        }
        return out;
    }
}
