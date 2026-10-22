package dev.emergence.core.sim;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.demo.Exhibit;
import dev.emergence.core.demo.Exhibits;
import org.junit.jupiter.api.Test;

import java.util.*;

/** Diagnostic census (printed): the unknowns of costly exhibits by kind, and electrical nodes in conductor-only clusters. */
class UnknownCensusTest {
    static List<Node> nodesOf(Element x) {
        List<Node> out = new ArrayList<>();
        for (Class<?> k = x.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (var f : k.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(x);
                    if (v instanceof Node n) out.add(n);
                    else if (v instanceof Node[] ns) for (Node n : ns) if (n != null) out.add(n);
                } catch (Exception ignored) {
                }
            }
        return out;
    }

    @Test
    void census() {
        for (Exhibit e : List.of(dev.emergence.core.demo.AcousticsExhibits.loudspeaker(), dev.emergence.core.demo.ControlExhibits.pwmMotor(),
                dev.emergence.core.demo.ControlExhibits.inverter())) {
            Exhibits.Built b = Exhibits.build(e, 293.15);
            CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
            var net = c.network();
            Map<String, Integer> kinds = new TreeMap<>();
            for (int i = 0; i < net.size(); i++) kinds.merge(net.unknownName(i).replaceAll("[0-9]+", "#"), 1, Integer::sum);
            System.out.println("CENSUS " + e.id + " " + net.size() + " " + kinds);
            // electrical nodes touched only by conductors, in clusters touching nothing else
            Map<Node, Set<Element>> users = new HashMap<>();
            for (Element x : net.elements()) for (Node n : nodesOf(x)) if (n.domain == Domain.ELECTRICAL) users.computeIfAbsent(n, k -> new HashSet<>()).add(x);
            Set<Node> conductorOnly = new HashSet<>();
            for (var en : users.entrySet()) {
                boolean only = true;
                for (Element x : en.getValue()) if (!(x instanceof dev.emergence.core.sim.elements.Conductor || x instanceof dev.emergence.core.sim.elements.ContactConductor)) only = false;
                if (only) conductorOnly.add(en.getKey());
            }
            // clusters: union over conductor elements whose both ends are conductor-only
            Map<Node, Node> parent = new HashMap<>();
            java.util.function.Function<Node, Node>[] find = new java.util.function.Function[1];
            find[0] = n -> { Node p = parent.getOrDefault(n, n); if (p == n) return n; Node r = find[0].apply(p); parent.put(n, r); return r; };
            Set<Node> touchingOther = new HashSet<>();
            for (Element x : net.elements()) {
                if (!(x instanceof dev.emergence.core.sim.elements.Conductor || x instanceof dev.emergence.core.sim.elements.ContactConductor)) continue;
                List<Node> en = new ArrayList<>();
                for (Node n : nodesOf(x)) if (n.domain == Domain.ELECTRICAL) en.add(n);
                for (int k = 1; k < en.size(); k++) parent.put(find[0].apply(en.get(k)), find[0].apply(en.get(0)));
            }
            for (Node n : users.keySet()) if (!conductorOnly.contains(n)) touchingOther.add(find[0].apply(n));
            int floating = 0, two = 0;
            for (Node n : conductorOnly) {
                if (!touchingOther.contains(find[0].apply(n)) && !n.isReference()) floating++;
                else if (users.get(n).size() == 2) two++;
            }
            String watch = System.getProperty("diag.censusNode");
            if (watch != null) for (var en : users.entrySet()) if (en.getKey().toString().equals(watch) || en.getKey().name().equals(watch))
                for (Element x : en.getValue()) System.out.println("CENSUS " + e.id + " " + watch + " ← " + x.label() + " " + nodesOf(x));
            System.out.println("CENSUS " + e.id + " electrical nodes " + users.size() + ", conductor-only " + conductorOnly.size() + ", in floating clusters " + floating + ", series (2 conductors, live) " + two);
        }
    }
}
