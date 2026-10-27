package dev.emergence.core.digital;

import dev.emergence.core.sim.Domain;
import dev.emergence.core.sim.Network;
import dev.emergence.core.sim.Node;
import dev.emergence.core.sim.TransientSolver;
import dev.emergence.core.sim.elements.Led;
import dev.emergence.core.sim.elements.Resistor;
import dev.emergence.core.sim.elements.VoltageSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The event-driven digital domain coupled to the circuit solver. */
class DigitalDomainTest {
    static final String MULTIPLIER = """
            chip w
            in clk
            out [31:0] q
            on rise clk q <= q * 3 + 1
            """;

    /**
     * A 1 kHz crystal clock drives a blinker chip over a net of chip pins only; the blinker's output, on an analog net,
     * lights an LED through 330 ohms. The clock's 2000 edges a second are the domain's events: the solver's steps
     * land only on the LED's 10 switchings.
     */
    @Test
    void clockedChipBlinksAnLedWithoutTheSolverSteppingEveryEdge() throws Exception {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL), vdd = n.node(Domain.ELECTRICAL, "vdd"), led = n.node(Domain.ELECTRICAL, "led"),
                k = n.node(Domain.ELECTRICAL, "k");
        n.add(new VoltageSource(vdd, gnd, 5));
        DigitalDomain dom = new DigitalDomain();
        ClockOscillator osc = new ClockOscillator(1000, 0);
        int so = dom.add(osc);
        DesignComponent blinker = new DesignComponent(ChipDesign.compile(ChipDesignTest.BLINKER), Map.of("half", 100.0));
        int sb = dom.add(blinker);
        DigitalDomain.Net clk = dom.net("clk");
        dom.connectOutput(so, 0, clk);
        dom.connectInput(sb, 0, clk);
        // rst (input 1) is left unconnected: low
        n.add(new DigitalChip(dom, so, vdd, gnd, new Node[0], new Node[]{null}, null, "clock"));
        DigitalChip chip = n.add(new DigitalChip(dom, sb, vdd, gnd, new Node[]{null, null}, new Node[]{led}, null, "blinker"));
        n.add(new Resistor(led, k, 330));
        n.add(new Led(k, gnd, 630, 0.1, null));
        TransientSolver s = new TransientSolver(n);
        int switches = 0;
        boolean was = false;
        double lit = 0;
        for (int i = 0; i < 1000; i++) {
            s.advance(1e-3);
            boolean on = s.x(led) > 2.5;
            if (on != was) switches++;
            was = on;
            if (on) lit += 1e-3;
        }
        assertTrue(chip.powered());
        // the clock starts 1 ms after power; the light changes every 100 edges = 0.1 s
        assertTrue(osc.cycles() >= 995 && osc.cycles() <= 1000, "cycles " + osc.cycles());
        assertTrue(switches >= 9 && switches <= 10, "switchings " + switches);
        assertEquals(0.5, lit, 0.02, "on half the time");
        assertTrue(s.acceptedSteps() < 2000, "the solver did not step every clock edge: " + s.acceptedSteps() + " steps");
        assertTrue(dom.events() > 3000, "the domain handled the edges: " + dom.events());
        // a lit LED through 330 ohms from a 50 ohm output: (5 - V_f)/380 ohms, about 7 mA
        s.advance(0.05);
        if (s.x(led) > 2.5) assertEquals(0.007, (s.x(led) - s.x(k)) / 330, 0.002);
    }

    /** An input on an analog net: a slowly rising voltage clocks a counter once, at half the supply. */
    @Test
    void analogInputSwitchesAtHalfTheSupplyWithHysteresis() throws Exception {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL), vdd = n.node(Domain.ELECTRICAL, "vdd"), in = n.node(Domain.ELECTRICAL, "in");
        n.add(new VoltageSource(vdd, gnd, 5));
        // a ramp 0 to 5 V over 1 s, then back down, with a 0.1 V ripple at 50 Hz on it (hysteresis keeps one edge)
        n.add(new VoltageSource(in, gnd, t -> (t < 1 ? 5 * t : Math.max(0, 5 - 5 * (t - 1))) + 0.1 * Math.sin(2 * Math.PI * 50 * t)));
        DigitalDomain dom = new DigitalDomain();
        DesignComponent ctr = new DesignComponent(ChipDesign.compile("chip c\nin clk\nout [3:0] q\non rise clk q <= q + 1"), null);
        int sc = dom.add(ctr);
        n.add(new DigitalChip(dom, sc, vdd, gnd, new Node[]{in}, new Node[4], null, "counter"));
        TransientSolver s = new TransientSolver(n);
        s.advance(0.6);
        assertEquals(1, ctr.instance().value("q"), "one rising edge as the ramp passed 2.75 V");
        s.advance(1.4);
        assertEquals(1, ctr.instance().value("q"), "falling back, no rising edge");
    }

    /**
     * The supply pays for the switching: clocking a 32-bit multiplier 1 MHz faster draws its switching energy per edge
     * times a million more (over the oscillator module's fixed milliampere).
     */
    @Test
    void supplyCurrentRisesWithTheClock() throws Exception {
        double slow = supplyCurrent(1000), fast = supplyCurrent(1_000_000);
        double perEdge = ChipDesign.compile(MULTIPLIER).estimate().energyPerEdge(5);
        // its inside on each rising edge (a falling one feeds no logic: a tenth), and the clock net (two pins, 10 pF)
        // charged and discharged once a cycle: C·V²
        double want = (1.1 * perEdge + 10e-12 * 25) * 999_000 / 5;
        assertEquals(want, fast - slow, 0.3 * want, slow + " A at 1 kHz, " + fast + " A at 1 MHz");
    }

    static double supplyCurrent(double f) throws Exception {
        Network n = new Network();
        Node gnd = n.reference(Domain.ELECTRICAL), vdd = n.node(Domain.ELECTRICAL, "vdd"), rail = n.node(Domain.ELECTRICAL, "rail");
        n.add(new VoltageSource(rail, gnd, 5));
        n.add(new Resistor(rail, vdd, 1)); // a 1 ohm sense resistor in the supply
        DigitalDomain dom = new DigitalDomain();
        int so = dom.add(new ClockOscillator(f, 0));
        int sc = dom.add(new DesignComponent(ChipDesign.compile("chip w\nin clk\nout [31:0] q\non rise clk q <= q * 3 + 1"), null));
        DigitalDomain.Net clk = dom.net("clk");
        dom.connectOutput(so, 0, clk);
        dom.connectInput(sc, 0, clk);
        n.add(new DigitalChip(dom, so, vdd, gnd, new Node[0], new Node[1], null, "clock"));
        n.add(new DigitalChip(dom, sc, vdd, gnd, new Node[1], new Node[32], null, "mul"));
        TransientSolver s = new TransientSolver(n);
        s.advance(0.05);
        return s.x(rail) - s.x(vdd);
    }
}
