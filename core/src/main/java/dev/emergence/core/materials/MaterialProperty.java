package dev.emergence.core.materials;

import dev.emergence.core.units.Dimension;

/**
 * Every material property recognised by the database, with its required SI dimension.
 * Data files are validated against these dimensions at load time, so a typo such as giving a
 * resistivity in "ohm" instead of "ohm*m" is rejected rather than silently producing wrong physics.
 */
public enum MaterialProperty {
    DENSITY("density", Dimension.DENSITY),
    YOUNGS_MODULUS("youngs_modulus", Dimension.PRESSURE),
    SHEAR_MODULUS("shear_modulus", Dimension.PRESSURE),
    POISSON_RATIO("poisson_ratio", Dimension.DIMENSIONLESS),
    YIELD_STRENGTH("yield_strength", Dimension.PRESSURE),
    ULTIMATE_TENSILE_STRENGTH("ultimate_tensile_strength", Dimension.PRESSURE),
    /** Fatigue (endurance) limit at ~10^7 cycles, fully reversed loading. */
    FATIGUE_LIMIT("fatigue_limit", Dimension.PRESSURE),
    /** Vickers hardness expressed as a pressure (HV × 9.807 MPa). */
    HARDNESS("hardness", Dimension.PRESSURE),
    /** Plane-strain fracture toughness K_IC. Stored in Pa (the implicit m^0.5 factor is documented). */
    FRACTURE_TOUGHNESS("fracture_toughness_pa_sqrt_m", Dimension.PRESSURE),
    THERMAL_CONDUCTIVITY("thermal_conductivity", Dimension.THERMAL_CONDUCTIVITY),
    SPECIFIC_HEAT("specific_heat", Dimension.SPECIFIC_HEAT),
    /** Linear coefficient of thermal expansion. */
    THERMAL_EXPANSION("thermal_expansion", Dimension.INVERSE_TEMPERATURE),
    MELTING_POINT("melting_point", Dimension.TEMPERATURE),
    BOILING_POINT("boiling_point", Dimension.TEMPERATURE),
    LATENT_HEAT_FUSION("latent_heat_fusion", Dimension.SPECIFIC_ENERGY),
    /** Electrical resistivity at 20 °C. */
    ELECTRICAL_RESISTIVITY("electrical_resistivity", Dimension.RESISTIVITY),
    /** Linear temperature coefficient of resistivity α (ρ = ρ20·(1 + α(T − 293.15 K))). */
    RESISTIVITY_TEMP_COEFF("resistivity_temperature_coefficient", Dimension.INVERSE_TEMPERATURE),
    /**
     * Optional power-law exponent n for metals whose resistivity is strongly non-linear at high
     * temperature: ρ = ρ20 (T / 293.15 K)^n. Takes precedence over the linear coefficient when present.
     */
    RESISTIVITY_EXPONENT("resistivity_exponent", Dimension.DIMENSIONLESS),
    /** Relative permittivity (dielectric constant). */
    RELATIVE_PERMITTIVITY("relative_permittivity", Dimension.DIMENSIONLESS),
    /** Dielectric strength (breakdown field), stored as V/m. */
    DIELECTRIC_STRENGTH("dielectric_strength", Dimension.VOLTAGE.div(Dimension.LENGTH)),
    /** Initial relative magnetic permeability μr (soft magnetic materials). */
    RELATIVE_PERMEABILITY("relative_permeability", Dimension.DIMENSIONLESS),
    /** Saturation flux density B_sat. */
    SATURATION_FLUX_DENSITY("saturation_flux_density", Dimension.MAGNETIC_FLUX_DENSITY),
    /** Remanent flux density B_r (permanent magnets). */
    REMANENCE("remanence", Dimension.MAGNETIC_FLUX_DENSITY),
    /** Intrinsic coercivity H_cJ (permanent magnets). */
    COERCIVITY("coercivity", Dimension.MAGNETIC_FIELD_STRENGTH),
    /** Relative recoil permeability of a permanent magnet's linear demagnetisation curve. */
    RECOIL_PERMEABILITY("recoil_permeability", Dimension.DIMENSIONLESS),
    /** Curie temperature; above it ferromagnetic order is lost. */
    CURIE_TEMPERATURE("curie_temperature", Dimension.TEMPERATURE),
    /** Reversible temperature coefficient of remanence (fraction per K, negative for NdFeB). */
    REMANENCE_TEMP_COEFF("remanence_temperature_coefficient", Dimension.INVERSE_TEMPERATURE),
    /** Total hemispherical emissivity of a typical (oxidised / as-received) surface. */
    EMISSIVITY("emissivity", Dimension.DIMENSIONLESS),
    /** Refractive index n_d at the helium d line (587.6 nm), for transparent materials. */
    REFRACTIVE_INDEX("refractive_index", Dimension.DIMENSIONLESS),
    /** Abbe number V_d = (n_d − 1)/(n_F − n_C): the lower, the more the material disperses colours. */
    ABBE_NUMBER("abbe_number", Dimension.DIMENSIONLESS),
    /**
     * Absorption coefficient of a transparent material [1/m]: uniform (a grey glass), or with an absorption edge (see
     * {@link #ABSORPTION_EDGE}) its value well inside the absorbing side.
     */
    ABSORPTION_COEFFICIENT("absorption_coefficient", Dimension.LENGTH.pow(-1)),
    /** Wavelength at the middle of a coloured glass's absorption edge [m]. */
    ABSORPTION_EDGE("absorption_edge", Dimension.LENGTH),
    /** Width of the edge [m]: positive for a long-pass glass (absorbing below the edge), negative for a short-pass one. */
    ABSORPTION_EDGE_WIDTH("absorption_edge_width", Dimension.LENGTH),
    /** Normal-incidence reflectance of a polished surface at 550 nm (green light). */
    REFLECTANCE("reflectance", Dimension.DIMENSIONLESS),
    /** Normal-incidence reflectance of a polished surface at 1000 nm (near infrared). */
    REFLECTANCE_IR("reflectance_ir", Dimension.DIMENSIONLESS),
    /** Seebeck coefficient relative to platinum, V/K. */
    SEEBECK_COEFFICIENT("seebeck_coefficient", Dimension.VOLTAGE.div(Dimension.TEMPERATURE)),
    /** Semiconductor band gap at 300 K. */
    BAND_GAP("band_gap", Dimension.ENERGY),
    /** Coefficient of kinetic friction against steel, dry. */
    FRICTION_COEFFICIENT("friction_coefficient", Dimension.DIMENSIONLESS),
    /** Standard electrode potential E° vs SHE for the metal's principal half-reaction. */
    STANDARD_ELECTRODE_POTENTIAL("standard_electrode_potential", Dimension.VOLTAGE),
    /** Molar mass of the principal constituent. */
    MOLAR_MASS("molar_mass", Dimension.MOLAR_MASS);

    public final String key;
    public final Dimension dimension;

    MaterialProperty(String key, Dimension dimension) {
        this.key = key;
        this.dimension = dimension;
    }

    public static MaterialProperty byKey(String key) {
        for (MaterialProperty p : values()) if (p.key.equals(key)) return p;
        return null;
    }
}
