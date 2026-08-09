import io.github.anschnapp.mutflow.ActiveMutation;
import io.github.anschnapp.mutflow.MutationRegistry;
import io.github.anschnapp.mutflow.SessionResult;
import io.github.anschnapp.mutflow.DiscoveredPoint;
import kotlin.Pair;
import sample.Calculator;

import java.util.*;
import java.util.function.Supplier;

/**
 * Binary inspector: runs the MUTATED Calculator bytecode, discovers every mutation
 * point, and for each point runs every variant against a battery of test inputs.
 * A variant is "killed" if any input's result differs from baseline; "survived"
 * otherwise. Emits an HTML dashboard to stdout.
 *
 * Usage: java -cp <stdlib>:<core>:<mutated-classes>:<this> BinaryInspector
 */
public class BinaryInspector {

    static MutationRegistry registry = MutationRegistry.INSTANCE;

    // ---- Test battery: method name -> list of input suppliers ----
    static final Map<String, List<Supplier<Object>>> BATTERY = new LinkedHashMap<>();
    static {
        Calculator c = new Calculator();
        BATTERY.put("add", List.of(() -> c.add(5, 3), () -> c.add(0, 0), () -> c.add(-2, 7)));
        BATTERY.put("isPositive", List.of(() -> c.isPositive(5), () -> c.isPositive(0), () -> c.isPositive(-3)));
        BATTERY.put("isInRange", List.of(() -> c.isInRange(50), () -> c.isInRange(0), () -> c.isInRange(200)));
        BATTERY.put("max", List.of(() -> c.max(3, 1), () -> c.max(1, 3), () -> c.max(4, 4)));
        BATTERY.put("startsWithA", List.of(() -> c.startsWithA("A"), () -> c.startsWithA("B"), () -> c.startsWithA("")));
        BATTERY.put("normalized", List.of(() -> c.normalized(" x "), () -> c.normalized("x"), () -> c.normalized("")));
        BATTERY.put("sameRef", List.of(() -> c.sameRef("a", "a"), () -> c.sameRef("a", "b")));
        BATTERY.put("notSameRef", List.of(() -> c.notSameRef("a", "a"), () -> c.notSameRef("a", "b")));
        BATTERY.put("greet", List.of(() -> c.greet(null), () -> c.greet("bob")));
        BATTERY.put("lengthOf", List.of(() -> c.lengthOf("abc"), () -> c.lengthOf(null)));
        BATTERY.put("emptyListReturn", List.of(() -> c.emptyListReturn()));
        BATTERY.put("doubleThenSet", List.of(() -> c.doubleThenSet(21), () -> c.doubleThenSet(0)));
        BATTERY.put("switchOp", List.of(() -> c.switchOp(1), () -> c.switchOp(2), () -> c.switchOp(9)));
        BATTERY.put("combine", List.of(() -> c.combine(3, 4), () -> c.combine(0, 0)));
        BATTERY.put("matchesRegex", List.of(() -> c.matchesRegex("abc"), () -> c.matchesRegex("xabc"), () -> c.matchesRegex("xyz")));
    }

    public static void main(String[] args) {
        // 1. Baseline: discover all points + record expected results per input.
        Map<String, List<Object>> expected = new LinkedHashMap<>();
        List<DiscoveredPoint> points;
        Pair<Object, SessionResult> base = registry.withSession(null, 0L, () -> {
            for (Map.Entry<String, List<Supplier<Object>>> e : BATTERY.entrySet()) {
                expected.put(e.getKey(), runBattery(e.getValue()));
            }
            return null;
        });
        points = base.getSecond().getDiscoveredPoints();

        // 2. For each point, run each variant against the battery.
        List<PointResult> pointResults = new ArrayList<>();
        for (DiscoveredPoint p : points) {
            for (int v = 0; v < p.getVariantCount(); v++) {
                final int variant = v;
                Map<String, List<Object>> mutantResults = new LinkedHashMap<>();
                Pair<Object, SessionResult> active = registry.withSession(new ActiveMutation(p.getPointId(), variant), 0L, () -> {
                    for (Map.Entry<String, List<Supplier<Object>>> e : BATTERY.entrySet()) {
                        mutantResults.put(e.getKey(), runBattery(e.getValue()));
                    }
                    return null;
                });
                boolean killed = differs(expected, mutantResults);
                pointResults.add(new PointResult(p, variant, killed, mutantResults));
            }
        }

        // 3. Render HTML.
        System.out.println(renderHtml(expected, points, pointResults));
    }

    /** Runs a battery of inputs, recording each result or a CRASH marker on exception. */
    static List<Object> runBattery(List<Supplier<Object>> inputs) {
        List<Object> results = new ArrayList<>();
        for (Supplier<Object> s : inputs) {
            try {
                results.add(s.get());
            } catch (Throwable t) {
                results.add("CRASH:" + t.getClass().getSimpleName());
            }
        }
        return results;
    }

    static boolean differs(Map<String, List<Object>> a, Map<String, List<Object>> b) {
        for (String k : a.keySet()) {
            List<Object> la = a.get(k);
            List<Object> lb = b.get(k);
            if (la == null || lb == null || la.size() != lb.size()) return true;
            for (int i = 0; i < la.size(); i++) {
                if (!Objects.equals(la.get(i), lb.get(i))) return true;
            }
        }
        return false;
    }

    static String esc(Object o) {
        return String.valueOf(o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String renderHtml(Map<String, List<Object>> expected, List<DiscoveredPoint> points, List<PointResult> results) {
        int killed = (int) results.stream().filter(r -> r.killed).count();
        int survived = results.size() - killed;
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
          .append("<title>mutflow binary inspection</title>")
          .append("<style>")
          .append("body{font-family:system-ui,sans-serif;margin:2rem;color:#1a1a1a}")
          .append("h1{font-size:1.4rem}.killed{color:#0a7d33;font-weight:600}.survived{color:#b00020;font-weight:600}")
          .append("table{border-collapse:collapse;margin-top:1rem;width:100%}")
          .append("th,td{border:1px solid #ddd;padding:6px 10px;text-align:left;font-size:.82rem;vertical-align:top}")
          .append("th{background:#f4f4f4}.mono{font-family:ui-monospace,monospace}")
          .append(".detail{color:#555;font-size:.75rem;max-width:520px}")
          .append(".badge{display:inline-block;padding:1px 8px;border-radius:10px;font-size:.75rem;color:#fff}")
          .append(".badge.k{background:#0a7d33}.badge.s{background:#b00020}")
          .append("</style></head><body>")
          .append("<h1>mutflow binary inspection</h1>")
          .append("<p>Mutated classes: <span class='mono'>sample.Calculator</span> (KMP JVM target)</p>")
          .append("<p>Mutation points: <b>").append(points.size()).append("</b> &middot; ")
          .append("variants tested: <b>").append(results.size()).append("</b> &middot; ")
          .append("<span class='killed'>killed: <b>").append(killed).append("</b></span> &middot; ")
          .append("<span class='survived'>survived: <b>").append(survived).append("</b></span></p>")
          .append("<table><tr><th>Point</th><th>Variant</th><th>Operator</th><th>Location</th><th>Status</th><th>Detail (input: baseline &rarr; mutant)</th></tr>");
        for (PointResult r : results) {
            sb.append("<tr><td class='mono'>").append(esc(r.p.getPointId())).append("</td>")
              .append("<td>").append(r.variant).append("</td>")
              .append("<td class='mono'>").append(esc(r.p.getOriginalOperator())).append("</td>")
              .append("<td class='mono'>").append(esc(r.p.getSourceLocation())).append("</td>")
              .append("<td><span class='badge ").append(r.killed ? "k" : "s").append("'>")
              .append(r.killed ? "KILLED" : "SURVIVED").append("</span></td>")
              .append("<td class='detail'>").append(detailHtml(expected, r.results)).append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    /** Renders per-input baseline vs mutant results, marking the differing inputs. */
    static String detailHtml(Map<String, List<Object>> expected, Map<String, List<Object>> mutant) {
        StringBuilder sb = new StringBuilder();
        for (String k : expected.keySet()) {
            List<Object> e = expected.get(k);
            List<Object> m = mutant.get(k);
            if (e == null || m == null || e.size() != m.size()) continue;
            for (int i = 0; i < e.size(); i++) {
                boolean diff = !Objects.equals(e.get(i), m.get(i));
                if (diff) {
                    sb.append("<b>").append(esc(k)).append("</b>[").append(i).append("]: ")
                      .append(esc(e.get(i))).append(" &rarr; <b>").append(esc(m.get(i))).append("</b><br>");
                }
            }
        }
        if (sb.length() == 0) sb.append("no input differed");
        return sb.toString();
    }

    static class PointResult {
        final DiscoveredPoint p;
        final int variant;
        final boolean killed;
        final Map<String, List<Object>> results;
        PointResult(DiscoveredPoint p, int variant, boolean killed, Map<String, List<Object>> results) {
            this.p = p; this.variant = variant; this.killed = killed; this.results = results;
        }
    }
}
