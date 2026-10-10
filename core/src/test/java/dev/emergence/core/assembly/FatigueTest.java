package dev.emergence.core.assembly;

import dev.emergence.core.materials.MaterialDatabase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The S–N laws: Basquin's line between 0.9·S_ut at 10³ cycles and the fatigue strength at N_e. */
class FatigueTest {
    @Test
    void basquinLinesAndEnduranceLimits() {
        var db = MaterialDatabase.standard();
        var steel = db.get("steel_1045");
        var al = db.get("aluminium_6061");
        assertEquals(1e3, ShaftFatigue.cyclesToFailure(steel, 0.9 * 625e6), 1e-6, "0.9 S_ut at a thousand cycles");
        assertEquals(1e7, ShaftFatigue.cyclesToFailure(steel, 280e6 * (1 + 1e-12)), 1e3, "the fatigue limit at 10⁷");
        assertTrue(Double.isInfinite(ShaftFatigue.cyclesToFailure(steel, 279e6)), "steel below its endurance limit: unlimited");
        assertEquals(5e8, ShaftFatigue.cyclesToFailure(al, 96.5e6), 5e8 * 1e-9, "aluminium's fatigue strength at 5·10⁸");
        double below = ShaftFatigue.cyclesToFailure(al, 80e6);
        assertTrue(below > 5e8 && Double.isFinite(below), "aluminium has no endurance limit: " + below);
        // halfway in log stress between the two anchors, life is halfway in log cycles
        double s = Math.sqrt(0.9 * 625e6 * 280e6);
        assertEquals(Math.sqrt(1e3 * 1e7), ShaftFatigue.cyclesToFailure(steel, s), 1e-3 * Math.sqrt(1e10));
        assertEquals(0.5, ShaftFatigue.cyclesToFailure(al, 400e6), 0, "past the ultimate strength: the first half turn");
    }
}
