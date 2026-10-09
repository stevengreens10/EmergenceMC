package dev.emergence.core.demo;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;

import java.util.*;

/**
 * Wire router for exhibit builders: lays insulated copper wire between declared pin cells of each net by
 * breadth-first search through free cells of a box, then colours the insulation so that wires of different
 * nets that touch never share a colour (same-colour insulated wires are spliced; different colours lie side
 * by side without contact). Cells next to any component (leaded part) are kept clear except a net's own pin
 * cells, because a wire touching a component's terminal face connects to it whatever its colour.
 * <p>
 * This is a layout tool, not physics: the circuit that results is compiled from geometry like any build.
 */
public final class Router {
    private final Exhibit e;
    private final int x0, y0, z0, x1, y1, z1;
    private final Set<Long> blocked = new HashSet<>(), nearLeads = new HashSet<>();
    private final Map<Long, String> pinNet = new HashMap<>(), wireNet = new HashMap<>();
    private final Map<String, List<Long>> pins = new LinkedHashMap<>();

    public Router(Exhibit e, int x0, int y0, int z0, int x1, int y1, int z1) {
        this.e = e;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        for (Exhibit.PartDef d : e.parts) {
            for (int x = d.x0(); x <= d.x1(); x++)
                for (int y = d.y0(); y <= d.y1(); y++)
                    for (int z = d.z0(); z <= d.z1(); z++) {
                        blocked.add(key(x, y, z));
                        if (d.kind().leaded && d.kind() != PartKind.WIRE && d.kind() != PartKind.INSULATED_WIRE) {
                            for (Dir dir : Dir.values()) nearLeads.add(key(x + dir.dx, y + dir.dy, z + dir.dz));
                        }
                    }
        }
    }

    static long key(int x, int y, int z) {
        return ((long) (x + 1024) << 22) | ((long) (y + 1024) << 11) | (z + 1024);
    }

    static int[] cell(long k) {
        return new int[]{(int) (k >> 22) - 1024, (int) ((k >> 11) & 0x7FF) - 1024, (int) (k & 0x7FF) - 1024};
    }

    private final Map<String, Double> insulation = new HashMap<>();

    /** Lays this net in wire with high-temperature insulation rated to {@code maxTemperature} [K] (e.g. glass fibre). */
    public Router insulation(String net, double maxTemperature) {
        insulation.put(net, maxTemperature);
        return this;
    }

    /** Keeps a box of cells free of wire (the space a moving part sweeps through). */
    public Router keepClear(int ax, int ay, int az, int bx, int by, int bz) {
        for (int x = ax; x <= bx; x++)
            for (int y = ay; y <= by; y++)
                for (int z = az; z <= bz; z++) blocked.add(key(x, y, z));
        return this;
    }

    /** Declares that net {@code net} must reach the free cell (x, y, z) — normally the cell against a pin face. */
    public Router pin(String net, int x, int y, int z) {
        return pin(net, x, y, z, false);
    }

    /** A pin cell that deliberately touches several components' terminals (a star point joining them). */
    public Router pinShared(String net, int x, int y, int z) {
        return pin(net, x, y, z, true);
    }

    private Router pin(String net, int x, int y, int z, boolean shared) {
        long k = key(x, y, z);
        if (blocked.contains(k)) throw new IllegalStateException("pin cell " + x + "," + y + "," + z + " of " + net + " is occupied");
        String other = pinNet.putIfAbsent(k, net);
        if (other != null && !other.equals(net)) throw new IllegalStateException("pin cell " + x + "," + y + "," + z + " claimed by " + other + " and " + net);
        int faces = 0;
        for (Dir dir : Dir.values()) if (isLeadedPart(x + dir.dx, y + dir.dy, z + dir.dz)) faces++;
        if (faces > 1 && !shared) throw new IllegalStateException("pin cell " + x + "," + y + "," + z + " of " + net + " touches " + faces + " components");
        pins.computeIfAbsent(net, n -> new ArrayList<>()).add(k);
        // keep the cell above a pin for its own net, so later nets cannot wall the pin in
        long up = key(x, y + 1, z);
        if (!blocked.contains(up) && !nearLeads.contains(up) && inBox(x, y + 1, z)) escape.putIfAbsent(up, net);
        return this;
    }

    private final Map<Long, String> escape = new HashMap<>();

    private boolean isLeadedPart(int x, int y, int z) {
        for (Exhibit.PartDef d : e.parts) {
            if (!d.kind().leaded || d.kind() == PartKind.WIRE || d.kind() == PartKind.INSULATED_WIRE) continue;
            if (x >= d.x0() && x <= d.x1() && y >= d.y0() && y <= d.y1() && z >= d.z0() && z <= d.z1()) return true;
        }
        return false;
    }

    private boolean inBox(int x, int y, int z) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
    }

    private boolean free(long k, String net) {
        if (blocked.contains(k)) return false;
        String w = wireNet.get(k);
        if (w != null && !w.equals(net)) return false;
        String p = pinNet.get(k);
        if (p != null && !p.equals(net)) return false;
        String esc = escape.get(k);
        if (esc != null && !esc.equals(net)) return false;
        return !(nearLeads.contains(k) && !net.equals(p));
    }

    /** Routes every declared net, colours the insulation, and adds the wires to the exhibit. */
    public void route() {
        // route net by net; a net that cannot be routed moves to the front and everything is routed again
        List<String> order = new ArrayList<>(pins.keySet());
        String failed = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            wireNet.clear();
            failed = routeInOrder(order);
            if (failed == null) break;
            order.remove(failed);
            order.add(0, failed);
        }
        if (failed != null) throw new IllegalStateException("cannot route " + failed);
        colourAndAdd();
    }

    /** Routes the nets in the given order; returns the first net that could not be routed, or null. */
    private String routeInOrder(List<String> order) {
        for (String net : order) {
            List<Long> ps = pins.get(net);
            Set<Long> tree = new HashSet<>();
            tree.add(ps.get(0));
            wireNet.put(ps.get(0), net);
            for (int i = 1; i < ps.size(); i++) {
                List<Long> path = bfs(ps.get(i), tree, net);
                if (path == null) return net;
                for (long k : path) {
                    tree.add(k);
                    wireNet.put(k, net);
                }
            }
        }
        return null;
    }

    private void colourAndAdd() {
        // colour: nets whose wires touch must differ; greedy, most-connected first
        Map<String, Set<String>> touch = new HashMap<>();
        for (var en : wireNet.entrySet()) {
            int[] c = cell(en.getKey());
            for (Dir dir : Dir.values()) {
                String o = wireNet.get(key(c[0] + dir.dx, c[1] + dir.dy, c[2] + dir.dz));
                if (o != null && !o.equals(en.getValue())) {
                    touch.computeIfAbsent(en.getValue(), n -> new HashSet<>()).add(o);
                    touch.computeIfAbsent(o, n -> new HashSet<>()).add(en.getValue());
                }
            }
        }
        List<String> order = new ArrayList<>(pins.keySet());
        order.sort((a, b) -> touch.getOrDefault(b, Set.of()).size() - touch.getOrDefault(a, Set.of()).size());
        Map<String, Integer> colour = new HashMap<>();
        for (String n : order) {
            Set<Integer> used = new HashSet<>();
            for (String o : touch.getOrDefault(n, Set.of())) if (colour.containsKey(o)) used.add(colour.get(o));
            int c = 0;
            while (used.contains(c)) c++;
            if (c > 15) throw new IllegalStateException("more than 16 insulation colours needed around " + n);
            colour.put(n, c);
        }
        for (var en : wireNet.entrySet()) {
            int[] c = cell(en.getKey());
            Double hot = insulation.get(en.getValue());
            int idx = hot == null ? e.add(PartKind.INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP, "color", colour.get(en.getValue()))
                    : e.add(PartKind.INSULATED_WIRE, "copper", c[0], c[1], c[2], Dir.UP, "color", colour.get(en.getValue()),
                    "max_temperature", hot);
            e.nets.put(idx, en.getValue());
        }
    }

    /** Shortest path of free cells from {@code start} to any cell of {@code tree} (inclusive), or null. */
    private List<Long> bfs(long start, Set<Long> tree, String net) {
        Map<Long, Long> prev = new HashMap<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        q.add(start);
        prev.put(start, start);
        while (!q.isEmpty()) {
            long k = q.poll();
            if (tree.contains(k)) {
                List<Long> path = new ArrayList<>();
                for (long c = k; ; c = prev.get(c)) {
                    path.add(c);
                    if (c == start) break;
                }
                return path;
            }
            int[] c = cell(k);
            for (Dir dir : Dir.values()) {
                int nx = c[0] + dir.dx, ny = c[1] + dir.dy, nz = c[2] + dir.dz;
                long nk = key(nx, ny, nz);
                if (prev.containsKey(nk) || !inBox(nx, ny, nz)) continue;
                if (!tree.contains(nk) && !free(nk, net)) continue;
                prev.put(nk, k);
                q.add(nk);
            }
        }
        return null;
    }
}
