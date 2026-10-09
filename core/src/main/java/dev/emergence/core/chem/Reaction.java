package dev.emergence.core.chem;

import dev.emergence.core.units.PhysicalConstants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A chemical reaction among gases: stoichiometry, Arrhenius kinetics r_f = A·T^b·e^{−Ea/RT}·Π c_i^{order_i}, and —
 * if reversible — a reverse rate r_f'/K_c from the equilibrium constant K_c = K_p·(P0/RT)^{Δν}, K_p = e^{−ΔG°/RT},
 * so that the net rate vanishes exactly at thermodynamic equilibrium.
 */
public final class Reaction {
    private static final double R = PhysicalConstants.GAS_CONSTANT;

    private final String id, equation;
    private final Map<Species, Double> reactants, products, orders;
    private final boolean reversible;
    private final double a, b, ea;

    public Reaction(String id, String equation, Map<Species, Double> reactants, Map<Species, Double> products,
                    Map<Species, Double> orders, boolean reversible, double a, double b, double ea) {
        this.id = id;
        this.equation = equation;
        this.reactants = new LinkedHashMap<>(reactants);
        this.products = new LinkedHashMap<>(products);
        this.orders = new LinkedHashMap<>(orders == null || orders.isEmpty() ? reactants : orders);
        this.reversible = reversible;
        this.a = a;
        this.b = b;
        this.ea = ea;
        for (String el : elements()) {
            if (Math.abs(elementBalance(el)) > 1e-9) {
                throw new IllegalArgumentException("Reaction " + id + " does not conserve " + el + ": " + elementBalance(el));
            }
        }
    }

    public String id() {
        return id;
    }

    public String equation() {
        return equation;
    }

    public Map<Species, Double> reactants() {
        return reactants;
    }

    public Map<Species, Double> products() {
        return products;
    }

    public Map<Species, Double> orders() {
        return orders;
    }

    public boolean reversible() {
        return reversible;
    }

    /** Net stoichiometric coefficient of s (products positive). */
    public double nu(Species s) {
        return products.getOrDefault(s, 0.0) - reactants.getOrDefault(s, 0.0);
    }

    public Set<Species> species() {
        Set<Species> all = new java.util.LinkedHashSet<>(reactants.keySet());
        all.addAll(products.keySet());
        return all;
    }

    private Set<String> elements() {
        Set<String> e = new TreeSet<>();
        for (Species s : species()) e.addAll(s.formula().keySet());
        return e;
    }

    /** Atoms of an element created by one turnover (zero for a balanced reaction). */
    public double elementBalance(String element) {
        double d = 0;
        for (Species s : species()) d += nu(s) * s.atoms(element);
        return d;
    }

    /** Δν of the gas-phase species. */
    public double deltaGasMoles() {
        double d = 0;
        for (Species s : species()) if (s.isGas()) d += nu(s);
        return d;
    }

    /** Reaction enthalpy ΔH°(T) [J/mol of turnovers]. */
    public double deltaH(double t) {
        double d = 0;
        for (Species s : species()) d += nu(s) * s.enthalpy(t);
        return d;
    }

    /** Standard reaction Gibbs energy ΔG°(T) [J/mol]. */
    public double deltaG(double t) {
        double d = 0;
        for (Species s : species()) d += nu(s) * s.gibbs(t);
        return d;
    }

    /** Equilibrium constant in pressure units (standard state 1 bar). */
    public double kp(double t) {
        return Math.exp(-deltaG(t) / (R * t));
    }

    /** Equilibrium constant in concentration units (mol/m³)^Δν. */
    public double kc(double t) {
        return kp(t) * Math.pow(Species.P0 / (R * t), deltaGasMoles());
    }

    /** Forward rate coefficient. */
    public double kf(double t) {
        return a * Math.pow(t, b) * Math.exp(-ea / (R * t));
    }

    /** d ln k_f / dT. */
    public double dlnKfdT(double t) {
        return b / t + ea / (R * t * t);
    }

    /** d ln K_c / dT = ΔU°/(RT²) (van 't Hoff). */
    public double dlnKcdT(double t) {
        return (deltaH(t) - deltaGasMoles() * R * t) / (R * t * t);
    }
}
