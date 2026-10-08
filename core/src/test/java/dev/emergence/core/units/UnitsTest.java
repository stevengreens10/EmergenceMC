package dev.emergence.core.units;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnitsTest {
    @Test
    void parsesCompoundUnits() {
        Quantity rho = Units.parse("8.96 g/cm^3");
        assertEquals(8960.0, rho.si(Dimension.DENSITY), 1e-9);
        Quantity k = Units.parse("401 W/(m*K)");
        assertEquals(401.0, k.si(Dimension.THERMAL_CONDUCTIVITY), 1e-12);
        Quantity r = Units.parse("1.68e-8 ohm*m");
        assertEquals(1.68e-8, r.si(Dimension.RESISTIVITY), 1e-20);
        assertEquals(955e3, Units.parse("955 kA/m").si(Dimension.MAGNETIC_FIELD_STRENGTH), 1e-6);
        assertEquals(1.12 * PhysicalConstants.ELEMENTARY_CHARGE, Units.parse("1.12 eV").si(Dimension.ENERGY), 1e-30);
        assertEquals(298.15, Units.parse("25 degC").si(Dimension.TEMPERATURE), 1e-12);
        assertEquals(2 * Math.PI, Units.parse("60 rpm").si(Dimension.ANGULAR_VELOCITY), 1e-12);
    }

    @Test
    void derivedDimensionsAreConsistent() {
        assertEquals(Dimension.POWER, Dimension.VOLTAGE.mul(Dimension.CURRENT));
        assertEquals(Dimension.POWER, Dimension.TORQUE.mul(Dimension.ANGULAR_VELOCITY));
        assertEquals(Dimension.POWER, Dimension.PRESSURE.mul(Dimension.VOLUMETRIC_FLOW));
        assertEquals(Dimension.POWER, Dimension.FORCE.mul(Dimension.VELOCITY));
        assertEquals(Dimension.ENERGY, Dimension.INDUCTANCE.mul(Dimension.CURRENT.pow(2)));
        assertEquals(Dimension.ENERGY, Dimension.CAPACITANCE.mul(Dimension.VOLTAGE.pow(2)));
        assertEquals(Dimension.MAGNETIC_FLUX_DENSITY, Dimension.PERMEABILITY.mul(Dimension.MAGNETIC_FIELD_STRENGTH));
        // Lorentz force: F = I L × B
        assertEquals(Dimension.FORCE, Dimension.CURRENT.mul(Dimension.LENGTH).mul(Dimension.MAGNETIC_FLUX_DENSITY));
    }

    @Test
    void rejectsDimensionMismatch() {
        Quantity a = Units.parse("1 m");
        Quantity b = Units.parse("1 s");
        assertThrows(DimensionException.class, () -> a.plus(b));
        assertThrows(DimensionException.class, () -> a.si(Dimension.TIME));
    }
}
