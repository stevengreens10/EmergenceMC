package dev.emergence.core.digital;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The digital side of a machine: its chips' insides and the nets that join only chip pins, run by events — a level
 * changing at a time — instead of by the circuit solver. A thousand gates or a 10 kHz serial bus then cost work in
 * proportion to what switches, not Newton iterations at every edge.
 * <p>
 * It keeps step with the circuits: it is brought up to each time the solver reaches (see DigitalChip), events
 * between chips are handled in time order, each output reaching its net after the chip's own delay. Where a chip pin
 * is on an analog net instead, the chip's bridge element does the physics: an input is a threshold crossing of its
 * voltage (posted here at the crossing's time), an output a pull-up or pull-down transistor whose switching the
 * solver lands a step on. A net's level switching costs ½·C·V² from the supply of the chip that drives it; a chip's
 * inside costs its own switching energy per edge, both paid at the chip's supply pins.
 */
public final class DigitalDomain {
    /** A net joining chip pins only: its level, its readers, its capacitance [F]. */
    public static final class Net {
        final String name;
        boolean level;
        final List<int[]> readers = new ArrayList<>();
        double capacitance;
        long transitions;
        int lastDriver = -1;
        long contentions;
        /** The chips driving it: their slots, the level each drives, and up to when their contention is booked. */
        int[] drivers = new int[0];
        boolean[] driven = new boolean[0];
        double[] booked = new double[0];
        /** Energy dissipated in contention so far [J]. */
        double contentionEnergy;
        /** Instruments watching its level (a logic analyser's probe), told of each change with its time. */
        final List<java.util.function.ObjDoubleConsumer<Boolean>> watchers = new java.util.concurrent.CopyOnWriteArrayList<>();

        /** Tells {@code w} of each change of level, with the time it changed [s]. */
        public void watch(java.util.function.ObjDoubleConsumer<Boolean> w) {
            watchers.add(w);
        }

        public void unwatch(java.util.function.ObjDoubleConsumer<Boolean> w) {
            watchers.remove(w);
        }

        Net(String name) {
            this.name = name;
        }

        public boolean level() {
            return level;
        }

        public String name() {
            return name;
        }

        /** How many times its level has changed. */
        public long transitions() {
            return transitions;
        }

        /** Times two chips drove it opposite ways (each counted when the later of them won). */
        public long contentions() {
            return contentions;
        }

        /** Energy burnt in its drivers while they fought (one pulling up, another down) [J]. */
        public double contentionEnergy() {
            return contentionEnergy;
        }
    }

    /** A pin's connection: a digital net, an analog node (the bridge element's), or nothing. */
    static final int NONE = 0, NET = 1, ANALOG = 2;

    /** One chip: its component, its pins' connections, its pending output drives, its supply, its energy. */
    final class Slot implements DigitalComponent.Ctx {
        final DigitalComponent c;
        final int index;
        final Net[] inNet, outNet;
        final int[] inKind, outKind;
        final int[] outVersion;
        /** An analog-bound output's level at the pin (what the bridge stamps). */
        final boolean[] outLevel;
        /** Each input's level as last posted, powered or not (an analog input already high when the supply comes up). */
        final boolean[] inSeen;
        boolean powered;
        double supply;
        double energy;
        /** Analog inputs' voltages at the bridge's last two accepted steps: {t0, v0, t1, v1} per input pin. */
        final double[][] analog;

        Slot(DigitalComponent c, int index) {
            this.c = c;
            this.index = index;
            int ni = c.inputs().size(), no = c.outputs().size();
            inNet = new Net[ni];
            outNet = new Net[no];
            inKind = new int[ni];
            outKind = new int[no];
            outVersion = new int[no];
            outLevel = new boolean[no];
            inSeen = new boolean[ni];
            analog = new double[ni][];
        }

        @Override
        public void drive(int k, boolean level, double delay) {
            int v = ++outVersion[k];
            double at = now + Math.max(0, delay) * delayFactor(supply);
            if (outKind[k] == NET) push(new Ev(at, seq++, Ev.NET, index, k, level, v));
            else if (outKind[k] == ANALOG) pushAnalog(new Ev(at, seq++, Ev.ANALOG_OUT, index, k, level, v));
        }

        @Override
        public void wakeAt(double t) {
            push(new Ev(Math.max(t, now), seq++, Ev.WAKE, index, -1, false, 0));
        }

        @Override
        public double supply() {
            return supply;
        }

        @Override
        public double analog(int k, double t) {
            double[] a = analog[k];
            if (a == null) return 0;
            if (t <= a[0] || a[2] <= a[0]) return t <= a[0] ? a[1] : a[3];
            if (t >= a[2]) return a[3];
            return a[1] + (a[3] - a[1]) * (t - a[0]) / (a[2] - a[0]);
        }
    }

    record Ev(double time, long seq, int kind, int slot, int pin, boolean level, int version) implements Comparable<Ev> {
        static final int NET = 0, ANALOG_OUT = 1, WAKE = 2, INPUT = 3, POWER = 4;

        public int compareTo(Ev o) {
            int c = Double.compare(time, o.time);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    private final List<Slot> slots = new ArrayList<>();
    private final List<Net> nets = new ArrayList<>();
    private final PriorityQueue<Ev> queue = new PriorityQueue<>(), analogQueue = new PriorityQueue<>();
    private long seq;
    private double now = Double.NEGATIVE_INFINITY;
    /** An analog-bound output has changed since the solver last asked (its circuits restart there). */
    private boolean analogChanged;
    private long events;

    /** Adds a chip; returns its slot. */
    public int add(DigitalComponent c) {
        Slot s = new Slot(c, slots.size());
        slots.add(s);
        return s.index;
    }

    public Net net(String name) {
        Net n = new Net(name);
        nets.add(n);
        return n;
    }

    public List<Net> nets() {
        return java.util.Collections.unmodifiableList(nets);
    }

    public DigitalComponent component(int slot) {
        return slots.get(slot).c;
    }

    /** Input pin k of a chip reads net n (whose capacitance grows by a CMOS input's, 5 pF). */
    public void connectInput(int slot, int k, Net n) {
        Slot s = slots.get(slot);
        s.inNet[k] = n;
        s.inKind[k] = NET;
        n.readers.add(new int[]{slot, k});
        n.capacitance += 5e-12;
    }

    /** Output pin k of a chip drives net n. */
    public void connectOutput(int slot, int k, Net n) {
        Slot s = slots.get(slot);
        s.outNet[k] = n;
        s.outKind[k] = NET;
        n.capacitance += 5e-12;
        int m = n.drivers.length;
        n.drivers = java.util.Arrays.copyOf(n.drivers, m + 1);
        n.driven = java.util.Arrays.copyOf(n.driven, m + 1);
        n.booked = java.util.Arrays.copyOf(n.booked, m + 1);
        n.drivers[m] = slot;
    }

    /** An output's on-resistance [Ω] (a 74HC output, as the bridge's analog outputs). */
    static final double R_OUT = 50;
    /** CMOS threshold [V] and the supply the chips' delays are given at [V]. */
    static final double V_T = 0.75, V_NOMINAL = 5;

    /**
     * How much slower a CMOS chip switches at supply v than at 5 V. Its transistors' current goes as (v − V_T)²
     * (long-channel square law), the charge to swing as v: delay ∝ v/(v − V_T)², about 4× slower at 2 V (74HC data:
     * 4–5×), a third faster at 6 V.
     */
    static double delayFactor(double v) {
        if (!(v > V_T + 0.05)) v = V_T + 0.05;
        double nominal = V_NOMINAL / ((V_NOMINAL - V_T) * (V_NOMINAL - V_T));
        return v / ((v - V_T) * (v - V_T)) / nominal;
    }

    /**
     * Books the contention on net n up to t: while its powered drivers disagree, those pulling up and those pulling
     * down form a divider across the supply, V²/(R_up + R_down) burnt in their output transistors, shared among them.
     */
    private void bookContention(Net n, double t) {
        int up = 0, down = 0;
        double v = 0;
        for (int j = 0; j < n.drivers.length; j++) {
            Slot d = slots.get(n.drivers[j]);
            if (!d.powered) continue;
            if (n.driven[j]) up++;
            else down++;
            v = Math.max(v, d.supply);
        }
        if (up > 0 && down > 0) {
            double p = v * v / (R_OUT / up + R_OUT / down), share = p / (up + down);
            for (int j = 0; j < n.drivers.length; j++) {
                Slot d = slots.get(n.drivers[j]);
                if (!d.powered || t <= n.booked[j]) continue;
                double e = share * (t - n.booked[j]);
                d.energy += e;
                n.contentionEnergy += e;
            }
        }
        for (int j = 0; j < n.drivers.length; j++) n.booked[j] = Math.max(n.booked[j], t);
    }

    /** Pin k (input or output) is on an analog net: the bridge element does its physics. */
    void bindAnalogInput(int slot, int k) {
        slots.get(slot).inKind[k] = ANALOG;
        slots.get(slot).analog[k] = new double[]{0, 0, 0, 0};
    }

    void bindAnalogOutput(int slot, int k) {
        slots.get(slot).outKind[k] = ANALOG;
    }

    /** Adds wiring capacitance to a net [F] (its length of wire). */
    public void addCapacitance(Net n, double c) {
        n.capacitance += c;
    }

    Slot slot(int i) {
        return slots.get(i);
    }

    private void push(Ev e) {
        queue.add(e);
    }

    private void pushAnalog(Ev e) {
        analogQueue.add(e);
    }

    /** From an analog net: input pin k crossed to {@code level} at time t. */
    void postInput(int slot, int k, boolean level, double t) {
        push(new Ev(Math.max(t, now), seq++, Ev.INPUT, slot, k, level, 0));
    }

    /** The supply came up (or went) at t. */
    void postPower(int slot, boolean on, double t) {
        push(new Ev(Math.max(t, now), seq++, Ev.POWER, slot, -1, on, 0));
    }

    /** Handles every event due by t, in time order. */
    public void advanceTo(double t) {
        while (true) {
            Ev a = queue.peek(), b = analogQueue.peek();
            Ev e = a == null ? b : b == null ? a : a.compareTo(b) <= 0 ? a : b;
            if (e == null || e.time() > t) break;
            if (e == a) queue.poll();
            else analogQueue.poll();
            now = Math.max(now, e.time());
            handle(e);
        }
        now = Math.max(now, t);
    }

    private void handle(Ev e) {
        events++;
        Slot s = slots.get(e.slot());
        switch (e.kind()) {
            case Ev.NET -> {
                if (e.version() != s.outVersion[e.pin()] || !s.powered) return;
                Net n = s.outNet[e.pin()];
                bookContention(n, e.time());
                for (int j = 0; j < n.drivers.length; j++) if (n.drivers[j] == s.index) n.driven[j] = e.level();
                if (n.lastDriver >= 0 && n.lastDriver != s.index && n.level != e.level()) n.contentions++;
                n.lastDriver = s.index;
                if (n.level == e.level()) return;
                n.level = e.level();
                n.transitions++;
                for (var w : n.watchers) w.accept(n.level, e.time());
                s.energy += 0.5 * n.capacitance * s.supply * s.supply;
                for (int[] r : n.readers) deliver(slots.get(r[0]), r[1], n.level, e.time());
            }
            case Ev.ANALOG_OUT -> {
                if (e.version() != s.outVersion[e.pin()]) return;
                if (s.outLevel[e.pin()] != e.level()) {
                    s.outLevel[e.pin()] = e.level();
                    analogChanged = true;
                }
            }
            case Ev.WAKE -> {
                if (s.powered) s.c.wake(e.time(), s);
            }
            case Ev.INPUT -> deliver(s, e.pin(), e.level(), e.time());
            case Ev.POWER -> {
                if (e.level() == s.powered) return;
                // contention on its nets booked up to now, before it joins or leaves them
                for (int k = 0; k < s.outNet.length; k++) if (s.outKind[k] == NET) bookContention(s.outNet[k], e.time());
                s.powered = e.level();
                if (s.powered) {
                    s.c.powerUp(e.time(), s);
                    // its inputs as they stand now
                    for (int k = 0; k < s.inNet.length; k++) {
                        if (s.inKind[k] == NET ? s.inNet[k].level : s.inSeen[k]) s.c.input(k, true, e.time(), s);
                    }
                } else {
                    for (int k = 0; k < s.outLevel.length; k++) {
                        s.outVersion[k]++;
                        if (s.outLevel[k]) analogChanged = true;
                        s.outLevel[k] = false;
                        if (s.outKind[k] == NET && s.outNet[k].lastDriver == s.index && s.outNet[k].level) {
                            Net n = s.outNet[k];
                            n.level = false;
                            n.transitions++;
                            for (var w : n.watchers) w.accept(false, e.time());
                            for (int[] r : n.readers) deliver(slots.get(r[0]), r[1], false, e.time());
                        }
                    }
                }
            }
            default -> throw new IllegalStateException();
        }
    }

    private void deliver(Slot s, int k, boolean level, double t) {
        s.inSeen[k] = level;
        if (!s.powered) return;
        s.c.input(k, level, t, s);
        s.energy += s.c.inputEnergy(k, level, s.supply);
    }

    /** The time of the next change at an analog-bound output (+∞ if none is pending). */
    double nextAnalogEvent() {
        while (!analogQueue.isEmpty()) {
            Ev e = analogQueue.peek();
            Slot s = slots.get(e.slot());
            if (e.version() == s.outVersion[e.pin()] && s.outLevel[e.pin()] != e.level()) return e.time();
            analogQueue.poll(); // superseded, or no change: nothing for the solver to land on
        }
        return Double.POSITIVE_INFINITY;
    }

    /** Whether an analog-bound output changed since last asked (and clears it). */
    boolean takeAnalogChange() {
        boolean c = analogChanged;
        analogChanged = false;
        return c;
    }

    boolean analogChangePending() {
        return analogChanged;
    }

    /** The energy a chip has switched since last asked [J]. */
    double takeEnergy(int slot) {
        Slot s = slots.get(slot);
        for (int k = 0; k < s.outNet.length; k++) if (s.outKind[k] == NET) bookContention(s.outNet[k], now);
        double e = s.energy;
        s.energy = 0;
        return e;
    }

    /** Events handled so far (diagnostics). */
    public long events() {
        return events;
    }

    public double time() {
        return now;
    }
}
