package dev.emergence.core.assembly;

import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic state of an assembly keyed by part identity, so it survives recompilation when the
 * construction changes (parts added/removed) and can be persisted with the world.
 *
 * @param temperatures      part id → temperature [K]
 * @param bodyMotion        part id → {ω [rad/s], θ [rad]} of the body containing that part
 * @param coilCurrents      coil part id → current [A]
 * @param capacitorVoltages capacitor part id → voltage [V]
 */
public record AssemblyState(Map<Long, Double> temperatures, Map<Long, double[]> bodyMotion,
                            Map<Long, Double> coilCurrents, Map<Long, Double> capacitorVoltages,
                            Map<String, Double> chemistry) {
    public static AssemblyState empty() {
        return new AssemblyState(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    /**
     * Chemistry keys: "S{electrolyteId}:{species}" dissolved moles, "M{electrodeId}:{electrolyteId}" remaining
     * electrode metal, "D{electrodeId}:{metal}" deposited foreign metal.
     */
    public static String speciesKey(long electrolyte, String species) {
        return "S" + electrolyte + ":" + species;
    }
}
