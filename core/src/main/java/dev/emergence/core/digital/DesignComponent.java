package dev.emergence.core.digital;

import java.util.List;
import java.util.Map;

/**
 * A chip made from a design in the chip language (see ChipDesign): its outputs follow an edge after a clock-to-output
 * delay (registered ones) or the logic's propagation delay (combinational ones), both from the design's own synthesis
 * estimate; its switching energy per edge and its leakage likewise.
 */
public final class DesignComponent implements DigitalComponent {
    private final ChipDesign design;
    private final Map<String, Double> params;
    private ChipDesign.Instance inst;
    private long[] saved;

    public DesignComponent(ChipDesign design, Map<String, Double> params) {
        this.design = design;
        this.params = params;
        this.inst = design.instantiate(params);
    }

    public ChipDesign design() {
        return design;
    }

    /** The running state (registers, memories) for probes. */
    public ChipDesign.Instance instance() {
        return inst;
    }

    @Override
    public List<String> inputs() {
        return design.inputPins();
    }

    @Override
    public List<String> outputs() {
        return design.outputPins();
    }

    private double delay(int k) {
        var e = design.estimate();
        return design.outputRegistered(k) ? ChipDesign.Estimate.CLOCK_TO_Q : e.propagationDelay();
    }

    @Override
    public void input(int k, boolean level, double t, Ctx ctx) {
        java.util.BitSet ch = inst.setInput(k, level, t);
        if (ch == null) return;
        for (int p = ch.nextSetBit(0); p >= 0; p = ch.nextSetBit(p + 1)) ctx.drive(p, inst.output(p), delay(p));
    }

    @Override
    public void powerUp(double t, Ctx ctx) {
        // a chip's registers start as the design says when power comes (or as saved, when a machine is loaded)
        inst = design.instantiate(params);
        if (saved != null) {
            inst.restore(saved);
            saved = null;
        }
        for (int p = 0; p < design.outputPins().size(); p++) ctx.drive(p, inst.output(p), delay(p));
    }

    @Override
    public boolean output(int k) {
        return inst.output(k);
    }

    @Override
    public double energyPerEdge(double v) {
        return design.estimate().energyPerEdge(v);
    }

    /** An edge its registers run on switches the logic; any other input change, the logic it feeds (a tenth, say). */
    @Override
    public double inputEnergy(int k, boolean level, double v) {
        return design.clocks(k, level) ? energyPerEdge(v) : 0.1 * energyPerEdge(v);
    }

    @Override
    public double leakage(double v) {
        return design.estimate().leakage(v);
    }

    @Override
    public long[] save() {
        return inst.save();
    }

    @Override
    public void restore(long[] state) {
        // applied at power-up (the machine loads unpowered, its supply comes up in the first steps)
        saved = state;
        inst.restore(state);
    }
}
