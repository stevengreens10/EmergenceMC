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
 * @param fluids            fluid part id → {pressure [Pa], fluid temperature [K]}
 */
public record AssemblyState(Map<Long, Double> temperatures, Map<Long, double[]> bodyMotion,
                            Map<Long, Double> coilCurrents, Map<Long, Double> capacitorVoltages,
                            Map<String, Double> chemistry, Map<Long, double[]> fluids) {
    public static AssemblyState empty() {
        return new AssemblyState(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    /**
     * A copy keeping only finite values, and without the coil currents: the state to restart from after a failed
     * step. Stored energy that defines the build's condition (charged capacitors, temperatures, motion, fluids,
     * chemistry) survives; inductor currents, which force the circuit at the first step, start from rest.
     */
    public AssemblyState recovery() {
        AssemblyState s = empty();
        temperatures.forEach((k, v) -> { if (Double.isFinite(v)) s.temperatures.put(k, v); });
        capacitorVoltages.forEach((k, v) -> { if (Double.isFinite(v)) s.capacitorVoltages.put(k, v); });
        chemistry.forEach((k, v) -> { if (Double.isFinite(v)) s.chemistry.put(k, v); });
        bodyMotion.forEach((k, v) -> { if (java.util.Arrays.stream(v).allMatch(Double::isFinite)) s.bodyMotion.put(k, new double[]{0, v[1]}); });
        fluids.forEach((k, v) -> { if (java.util.Arrays.stream(v).allMatch(Double::isFinite)) s.fluids.put(k, v.clone()); });
        return s;
    }

    /**
     * Chemistry keys: "S{electrolyteId}:{species}" dissolved moles, "M{electrodeId}:{electrolyteId}" remaining
     * electrode metal, "D{electrodeId}:{metal}" deposited foreign metal.
     */
    public static String speciesKey(long electrolyte, String species) {
        return "S" + electrolyte + ":" + species;
    }
}
