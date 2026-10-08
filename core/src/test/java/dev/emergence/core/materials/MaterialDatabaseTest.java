package dev.emergence.core.materials;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaterialDatabaseTest {
    @Test
    void loadsStandardDatabase() {
        MaterialDatabase db = MaterialDatabase.standard();
        assertTrue(db.all().size() >= 30, "expected a substantial database");
        Material cu = db.get("copper");
        assertEquals(8940, cu.density(), 1);
        assertEquals(1.71e-8, cu.get(MaterialProperty.ELECTRICAL_RESISTIVITY), 1e-12);
        assertTrue(cu.isConductor());
        assertFalse(db.get("ptfe").isConductor());
        assertEquals(1.30, db.get("ndfeb_n42").get(MaterialProperty.REMANENCE), 1e-9);
    }

    @Test
    void copperResistivityFollowsLinearModel() {
        Material cu = MaterialDatabase.standard().get("copper");
        // ρ(100 °C) = ρ20 (1 + α·80)
        double expected = 1.71e-8 * (1 + 0.00393 * 80);
        assertEquals(expected, cu.resistivity(373.15), 1e-14);
    }

    @Test
    void tungstenHotResistanceRatioMatchesFilamentData() {
        // Measured ρ(2800 K)/ρ(293 K) for tungsten ≈ 15.5–16.5 (Forsythe & Worthing; Desai et al. 1984).
        Material w = MaterialDatabase.standard().get("tungsten");
        double ratio = w.resistivity(2800) / w.resistivity(293.15);
        assertTrue(ratio > 14.5 && ratio < 17.5, "ratio=" + ratio);
    }

    @Test
    void everyMaterialHasCoreThermoMechanicalData() {
        for (Material m : MaterialDatabase.standard().all()) {
            assertTrue(m.has(MaterialProperty.DENSITY), m.id() + " density");
            assertTrue(m.has(MaterialProperty.SPECIFIC_HEAT), m.id() + " cp");
            assertTrue(m.has(MaterialProperty.THERMAL_CONDUCTIVITY), m.id() + " k");
            assertTrue(m.has(MaterialProperty.MELTING_POINT), m.id() + " Tm");
            assertFalse(m.sources().isEmpty(), m.id() + " must cite sources");
        }
    }
}
