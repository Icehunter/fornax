package dev.icehunter.fornax.pack;

import com.electronwill.nightconfig.core.Config;
import dev.icehunter.fornax.metalfx.rt.RayQueryAbi;
import dev.icehunter.fornax.pack.graph.BufferSize;
import dev.icehunter.fornax.pack.graph.GraphValidator;
import dev.icehunter.fornax.pack.graph.TextureSize;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/** Compile-time integer sizing; renderers only receive resolved, ordinary numeric graph specs. */
public final class GraphNumericExpressions {
    private static final String FILE = "graph.toml";
    // The existing enabled_if validator uses the same finite domain ceiling.
    private static final int MAX_VARIANTS = 4096;
    private GraphNumericExpressions() {}

    /** Save expressions before the typed loader runs. Ones are parse-only placeholders, never sizes. */
    static Map<String, String> capture(Config root, String file) {
        Map<String, String> expressions = new LinkedHashMap<>();
        Object targets = root.get("targets");
        if (targets instanceof Config table) for (Config.Entry entry : table.entrySet()) {
            if (!(entry.getValue() instanceof Config target)) continue;
            String prefix = "targets." + entry.getKey() + ".";
            for (String field : List.of("width", "height", "count"))
                captureField(target, field, prefix + field, expressions, file, field.equals("count"));
        }
        Object passes = root.get("pass");
        if (passes instanceof List<?> list) for (Object item : list) {
            if (!(item instanceof Config pass)) continue;
            String name = TomlSupport.requireString(pass, "name", file);
            String prefix = "pass." + name + ".";
            Object dispatch = pass.get("dispatch");
            if (dispatch instanceof List<?> raw) {
                List<Object> values = new ArrayList<>(raw);
                for (int i = 0; i < values.size(); i++) if (values.get(i) instanceof String expression) {
                    String key = prefix + "dispatch." + i;
                    parse(expression, key, file);
                    expressions.put(key, expression);
                    values.set(i, 1);
                }
                pass.set("dispatch", values);
            }
            Object query = pass.get("ray_query");
            if (query instanceof Config spec)
                captureField(spec, "rays", prefix + "ray_query.rays", expressions, file, true);
        }
        return Collections.unmodifiableMap(expressions);
    }

    private static void captureField(Config table, String field, String key,
                                     Map<String, String> expressions, String file, boolean renderAllowed) {
        Object raw = table.get(field);
        if (!(raw instanceof String expression) || (renderAllowed && expression.equals("render"))) return;
        parse(expression, key, file);
        expressions.put(key, expression);
        table.set(field, 1);
    }

    /** Resolve again from retained formulas, including when the input is an earlier resolved graph. */
    public static GraphSpec resolve(GraphSpec graph, Map<String, PackOption> options,
                                    Map<String, Integer> selected) {
        if (graph.numericExpressions().isEmpty()) return graph;
        Map<String, List<Integer>> domains = domains(graph, options);
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String name : domains.keySet()) {
            int value = selected.containsKey(name) ? selected.get(name)
                    : integer(options.get(name).defaultValue(), "option " + name);
            if (!domains.get(name).contains(value))
                throw error("option " + name, "selected value " + value + " is outside its declared domain");
            values.put(name, value);
        }
        return resolveValues(graph, values);
    }

    /** Called before replacing a live graph; invalid saved settings leave the old graph intact. */
    public static GraphSpec resolveValidated(GraphSpec graph, Map<String, PackOption> options,
                                             Map<String, Integer> selected) {
        GraphSpec resolved = resolve(graph, options, selected);
        if (!graph.numericExpressions().isEmpty()) {
            // Only fixed dimensions/counts are expressions. Render-relative extents still size in prepare().
            GraphValidator.validate(withoutExpressions(resolved), options, 1, 1);
        }
        return resolved;
    }

    public static GraphSpec withoutExpressions(GraphSpec graph) {
        return new GraphSpec(graph.targets(), graph.textures(), graph.passes(), graph.rayTracedShadows());
    }

    /** Validate every declared sizing combination, including currently disabled feature arms. */
    public static void forEachVariant(GraphSpec graph, Map<String, PackOption> options,
                                      Consumer<GraphSpec> consumer) {
        Map<String, List<Integer>> domains = domains(graph, options);
        visit(graph, new ArrayList<>(domains.keySet()), domains, 0, new LinkedHashMap<>(), consumer);
    }

    private static void visit(GraphSpec graph, List<String> names, Map<String, List<Integer>> domains,
                              int next, Map<String, Integer> values, Consumer<GraphSpec> consumer) {
        if (next == names.size()) { consumer.accept(withoutExpressions(resolveValues(graph, values))); return; }
        String name = names.get(next);
        for (int value : domains.get(name)) {
            values.put(name, value);
            visit(graph, names, domains, next + 1, values, consumer);
        }
    }

    private static Map<String, List<Integer>> domains(GraphSpec graph, Map<String, PackOption> options) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        long variants = 1;
        for (var expression : graph.numericExpressions().entrySet()) {
            for (String name : parse(expression.getValue(), expression.getKey(), FILE).names()) {
                if (result.containsKey(name)) continue;
                PackOption option = options.get(name);
                if (option == null) throw error(expression.getKey(), "unknown sizing option '" + name + "'");
                if (option.type() != OptionType.COMPILE)
                    throw error(expression.getKey(), "sizing option '" + name + "' must be compile-time");
                List<Integer> values = new ArrayList<>();
                if (option.isBoolean()) values.addAll(List.of(0, 1));
                else if (!option.allowedValues().isEmpty()) {
                    for (String value : option.allowedValues()) values.add(integer(value, "option " + name));
                } else if (option.range() != null) {
                    var range = option.range();
                    int lo = exactInteger(range.min(), name), hi = exactInteger(range.max(), name);
                    int step = exactInteger(range.step(), name);
                    if (step <= 0 || hi < lo || (long) (hi - (long) lo) / step + 1 > MAX_VARIANTS)
                        throw error(expression.getKey(), "invalid or excessive sizing domain for '" + name + "'");
                    for (long value = lo; value <= hi; value += step) values.add((int) value);
                } else values.add(integer(option.defaultValue(), "option " + name));
                values = new ArrayList<>(new LinkedHashSet<>(values));
                variants *= values.size();
                if (variants > MAX_VARIANTS)
                    throw error(expression.getKey(), "sizing domains exceed " + MAX_VARIANTS + " combinations");
                result.put(name, List.copyOf(values));
            }
        }
        return result;
    }

    private static int exactInteger(double value, String name) {
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw error("option " + name, "sizing option must have an integer domain");
        return (int) value;
    }

    private static int integer(String value, String key) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw error(key, "expected an integer option value, got '" + value + "'"); }
    }

    private static GraphSpec resolveValues(GraphSpec graph, Map<String, Integer> values) {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        for (var expression : graph.numericExpressions().entrySet()) {
            String key = expression.getKey();
            try {
                long value = parse(expression.getValue(), key, FILE).node().value(name -> values.get(name));
                if (value <= 0 || value > Integer.MAX_VALUE) throw new ArithmeticException("result must be a positive 32-bit integer");
                resolved.put(key, (int) value);
            } catch (ArithmeticException ex) {
                throw error(key, "expression '" + expression.getValue() + "': " + ex.getMessage());
            }
        }
        Map<String, TargetSpec> targets = new LinkedHashMap<>();
        for (TargetSpec t : graph.targets().values()) {
            String key = "targets." + t.name() + ".";
            TextureSize fixed = t.fixedSize();
            if (fixed != null) fixed = new TextureSize(resolved.getOrDefault(key + "width", fixed.width()),
                    resolved.getOrDefault(key + "height", fixed.height()));
            BufferSize buffer = t.bufferSize();
            if (buffer != null && !buffer.perRenderPixel()) {
                buffer = new BufferSize(buffer.strideBytes(), resolved.getOrDefault(key + "count", buffer.count()));
                if (buffer.sizeBytes() > BufferSize.MAX_SIZE_BYTES)
                    throw error(key + "count", "resolved buffer exceeds " + BufferSize.MAX_SIZE_BYTES + " bytes");
            }
            targets.put(t.name(), new TargetSpec(t.name(), t.format(), t.scale(), t.history(), t.enabledIf(),
                    t.basis(), t.kind(), t.filter(), buffer, fixed, t.storage()));
        }
        List<PassSpec> passes = new ArrayList<>();
        for (PassSpec p : graph.passes()) {
            String key = "pass." + p.name() + ".";
            List<Integer> dispatch = new ArrayList<>(p.dispatch());
            for (int i = 0; i < dispatch.size(); i++)
                dispatch.set(i, resolved.getOrDefault(key + "dispatch." + i, dispatch.get(i)));
            RayQuerySpec query = p.rayQuery();
            if (query != null && !query.perRenderPixel()) {
                int rays = resolved.getOrDefault(key + "ray_query.rays", query.rayCount());
                if (rays > RayQueryAbi.MAX_RAYS) throw error(key + "ray_query.rays", "resolved rays exceed " + RayQueryAbi.MAX_RAYS);
                query = new RayQuerySpec(query.kind(), rays, query.minTier(), query.atlasUvEncoding());
            }
            passes.add(new PassSpec(p.name(), p.type(), p.slot(), p.program(), p.shader(), p.inputs(), p.outputs(),
                    p.target(), p.enabledIf(), List.copyOf(dispatch), p.localSize(), p.blend(), p.particles(),
                    p.runtimeEnabledIf(), p.reuseWhenUnchanged(), query));
        }
        return new GraphSpec(Collections.unmodifiableMap(targets), graph.textures(), List.copyOf(passes),
                graph.rayTracedShadows(), graph.numericExpressions());
    }

    @FunctionalInterface private interface Node { long value(ToLongFunction<String> values); }
    private record Parsed(Node node, Set<String> names) {}
    private static Parsed parse(String text, String key, String file) {
        return new Parser(text, key, file).parse();
    }
    private static FornaxPackError error(String key, String message) { return new FornaxPackError(FILE, key, message); }

    /** Deliberately no scripting, implicit conversions, functions, or graph-to-graph references. */
    private static final class Parser {
        final String text, key, file;
        final Set<String> names = new LinkedHashSet<>();
        int at;
        Parser(String text, String key, String file) { this.text = text; this.key = key; this.file = file; }
        Parsed parse() {
            Node node = sum(); space();
            if (at != text.length()) fail("unexpected token at column " + (at + 1));
            return new Parsed(node, names);
        }
        Node sum() {
            Node left = product();
            while (take('+')) { Node a = left, b = product(); left = values -> Math.addExact(a.value(values), b.value(values)); }
            return left;
        }
        Node product() {
            Node left = atom();
            while (true) {
                if (take('*')) { Node a = left, b = atom(); left = values -> Math.multiplyExact(a.value(values), b.value(values)); }
                else if (take('/')) {
                    Node a = left, b = atom();
                    left = values -> {
                        long numerator = a.value(values), denominator = b.value(values);
                        if (denominator == 0 || numerator % denominator != 0)
                            throw new ArithmeticException("division must have a nonzero divisor and an exact integer result");
                        return numerator / denominator;
                    };
                } else return left;
            }
        }
        Node atom() {
            space();
            if (take('(')) { Node n = sum(); if (!take(')')) fail("missing ')' "); return n; }
            int start = at;
            if (at < text.length() && Character.isDigit(text.charAt(at))) {
                while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
                try { long value = Long.parseLong(text.substring(start, at)); return values -> value; }
                catch (NumberFormatException ex) { fail("integer literal overflow"); }
            }
            if (at < text.length() && (Character.isLetter(text.charAt(at)) || text.charAt(at) == '_')) {
                while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) at++;
                String name = text.substring(start, at); names.add(name); return values -> values.applyAsLong(name);
            }
            fail("expected integer, compile option, or '(' at column " + (at + 1));
            throw new AssertionError();
        }
        boolean take(char c) { space(); if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        void space() { while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++; }
        void fail(String message) { throw new FornaxPackError(file, key, "integer expression '" + text + "': " + message); }
    }
}
