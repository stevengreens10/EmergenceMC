package dev.emergence.core.chem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Vapour–liquid equilibrium of water and ethanol against handbook data. */
class VaporLiquidTest {
    static final String[] IDS = {"C2H5OH", "H2O"};

    @Test
    void pureBoilingPoints() {
        assertEquals(373.12, VaporLiquid.bubblePoint(new String[]{"H2O"}, new double[]{1}, 101_325)[0], 0.1, "water");
        assertEquals(351.44, VaporLiquid.bubblePoint(new String[]{"C2H5OH"}, new double[]{1}, 101_325)[0], 0.2, "ethanol");
    }

    @Test
    void latentHeatsAt25C() {
        assertEquals(44.0e3, VaporLiquid.of("H2O").latentHeat(298.15), 0.6e3, "water: 44.0 kJ/mol");
        assertEquals(42.3e3, VaporLiquid.of("C2H5OH").latentHeat(298.15), 1.0e3, "ethanol: 42.3 kJ/mol");
    }

    /**
     * A 10 mol % ethanol solution boils at 86.4 °C with a vapour of 43.7 % ethanol (Gmehling & Onken DECHEMA data at
     * 1 atm, as tabulated in Perry's); a 50 % one at 79.8 °C, vapour 65.6 %.
     */
    @Test
    void ethanolWaterBubblePoints() {
        double[] a = VaporLiquid.bubblePoint(IDS, new double[]{0.10, 0.90}, 101_325);
        double[] b = VaporLiquid.bubblePoint(IDS, new double[]{0.50, 0.50}, 101_325);
        System.out.printf("ethanol–water: x 0.10 boils at %.2f °C, y %.3f; x 0.50 at %.2f °C, y %.3f%n", a[0] - 273.15, a[1], b[0] - 273.15, b[1]);
        assertEquals(86.4, a[0] - 273.15, 1.0);
        assertEquals(0.437, a[1], 0.03);
        assertEquals(79.8, b[0] - 273.15, 0.8);
        assertEquals(0.656, b[1], 0.03);
    }

    /** The azeotrope: vapour and liquid compositions meet near 89 mol % ethanol, boiling just below pure ethanol. */
    @Test
    void azeotrope() {
        double best = 0, bestGap = 1;
        for (double x = 0.80; x <= 0.97; x += 0.001) {
            double y = VaporLiquid.bubblePoint(IDS, new double[]{x, 1 - x}, 101_325)[1];
            if (Math.abs(y - x) < bestGap) {
                bestGap = Math.abs(y - x);
                best = x;
            }
        }
        double t = VaporLiquid.bubblePoint(IDS, new double[]{best, 1 - best}, 101_325)[0];
        System.out.printf("azeotrope at x = %.3f, %.2f °C%n", best, t - 273.15);
        assertEquals(0.89, best, 0.03);
        assertTrue(t < 351.44, "a minimum-boiling azeotrope");
    }
}
