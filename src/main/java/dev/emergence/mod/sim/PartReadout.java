package dev.emergence.mod.sim;

import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Probe;
import dev.emergence.core.units.PhysicalConstants;
import dev.emergence.core.units.Units;
import dev.emergence.mod.part.PlacedPart;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * A part's readings, as instruments take them from the simulated state: its temperature, what flows through it
 * (current, fluid), what it does (power, speed, stress), and what it holds (charge, a chip's state, a wafer's
 * process). What the multimeter reports when clicked and the engineer's goggles show when looked at.
 */
public final class PartReadout {
    private PartReadout() {
    }

    /** One reading: its label and value as shown. */
    public record Line(String label, String value) {
    }

    /** The part's readings, or empty if its machine is not simulated yet. */
    public static List<Line> of(SimulationManager sm, BlockPos pos, PlacedPart part) {
        List<Line> out = new ArrayList<>();
        Probe p = sm.probe(pos, part.id);
        CompiledAssembly c = sm.compiledAt(pos);
        if (p == null || c == null) return out;
        out.add(new Line("Temperature", String.format("%.1f °C (%.1f K)", p.temperature() - PhysicalConstants.T_ZERO_CELSIUS, p.temperature())));
        if (!Double.isNaN(p.voltage())) out.add(new Line("Voltage", Units.formatSI(round(p.voltage()), "V")));
        if (!Double.isNaN(p.current())) out.add(new Line("Current", Units.formatSI(round(p.current()), "A")));
        if (!Double.isNaN(p.power())) out.add(new Line("Power", Units.formatSI(round(p.power()), "W")));
        if (p.bodyIndex() > 0)
            out.add(new Line("Speed", String.format("%.1f rpm (%.2f rad/s)", p.angularVelocity() * 60 / (2 * Math.PI), p.angularVelocity())));
        double soc = c.batteryStateOfCharge(part.id);
        if (!Double.isNaN(soc)) out.add(new Line("Charge", String.format("%.1f %%", 100 * soc)));
        double[] fl = c.fluid(part.id);
        if (fl != null) {
            // pressure gauge and thermometer in the fluid, and the flow through the part
            double pAmb = c.assembly().ambientPressure();
            out.add(new Line("Fluid", c.fluidName(part.id)));
            out.add(new Line("Pressure", Units.formatSI(round(fl[0]), "Pa") + " abs, " + String.format("%+.3f bar gauge", (fl[0] - pAmb) / 1e5)));
            out.add(new Line("Fluid temp.", String.format("%.1f °C", fl[1] - PhysicalConstants.T_ZERO_CELSIUS)));
            out.add(new Line("Mass flow", Units.formatSI(round(fl[3]), "kg/s") + String.format(" (%.3g g held)", fl[2] * 1000)));
        }
        double burst = c.burstUtilisation(part.id);
        if (burst > 0 && Double.isFinite(burst)) out.add(new Line("Wall stress", String.format("%.0f %% of bursting", 100 * burst)));
        // structural stress against the material's strength, and how far the part has sagged
        Double util = c.structure().utilisation().get(part.id);
        if (util != null) {
            var d = c.structure().displacement().get(part.id);
            out.add(new Line("Stress", String.format("%.0f %% of its strength", 100 * util) + (d != null ? String.format(", sag %.2f mm", -1000 * d.y()) : "")));
        }
        double[] fat = c.fatigue(part.id);
        if (fat != null && fat[0] > 0)
            out.add(new Line("Fatigue", String.format("%.2g %% of its life used; ±%s a turn", 100 * fat[1], Units.formatSI(round(fat[0]), "Pa"))));
        double tension = c.linkTension(part.id);
        if (!Double.isNaN(tension)) out.add(new Line("Tension", Units.formatSI(round(tension), "N")));
        double[] br = c.brake(part.id);
        if (br != null) out.add(new Line("Brake", String.format("%.3f N·m, absorbing %s", Math.abs(br[0]), Units.formatSI(round(br[1]), "W"))));
        double wiper = c.wiper(part.id);
        if (!Double.isNaN(wiper)) out.add(new Line("Wiper", String.format("%.1f %% of the track from end A", 100 * wiper)));
        double[] chip = c.chipState(part.id);
        if (chip != null) {
            int v = 0;
            StringBuilder bits = new StringBuilder();
            for (int k = chip.length - 1; k >= 0; k--) {
                bits.append(chip[k] > 0.5 ? '1' : '0');
                if (chip[k] > 0.5) v |= 1 << k;
            }
            out.add(new Line("Chip state", "0b" + bits + " (" + v + ")"));
        }
        double[] w = c.wafer(part.id);
        if (w != null) {
            out.add(new Line("Oxide", String.format("%.1f nm", w[0] * 1e9)));
            var cp = c.chipProcess(part.id);
            if (cp != null) {
                out.add(new Line("Junctions", String.format("%.2f µm deep", w[1] * 1e6)));
                out.add(new Line("Its chips", cp.works() ? String.format("work, %.2f× slower", cp.delayScale()) : "dead"));
            }
        }
        // electrochemistry: concentrations in a jar; dissolution, deposits and gas at an electrode
        for (var e : c.chemistry(part.id).entrySet()) {
            String[] kv = e.getKey().split(" (?=[^ ]+$)");
            double v = e.getValue();
            if (Math.abs(v) < 1e-12) continue;
            out.add(new Line(kv[0], String.format("%.4g %s", v, kv.length > 1 ? kv[1] : "")));
        }
        return out;
    }

    /** 4-significant-digit display. */
    public static double round(double v) {
        if (v == 0 || !Double.isFinite(v)) return v;
        double scale = Math.pow(10, 3 - Math.floor(Math.log10(Math.abs(v))));
        return Math.round(v * scale) / scale;
    }
}
