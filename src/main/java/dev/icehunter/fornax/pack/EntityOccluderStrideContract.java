package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.graph.EntityOccluderBuffer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load-time check that every pack shader reading the entity occluder set matches the shape
 * (header word count, occluder cap, words per record) {@link EntityOccluderBuffer} sends.
 *
 * <p>Sibling to {@link PaletteStrideContract}: same risk, same fix. GLSL cannot see Java's
 * constants, so a pack reading the buffer writes its own copy of {@code
 * ENTITY_OCCLUDER_HEADER_WORDS}/{@code _MAX}/{@code _RECORD_WORDS}, usually inside a shared
 * include a march function pulls in rather than the pass's own fragment shader. A copy left
 * stale, or never written at all, does not fail to compile: the buffer's own size guard reads it
 * as "no occluders" and every entity shadow disappears, with no error and nothing on screen to
 * show why.
 *
 * <p>Unlike {@link PaletteStrideContract}, which scans every shader file because a palette read
 * can live anywhere, this checks only the composed source of each pass that declares {@link
 * EntityOccluderBuffer#TARGET} as an input. Those passes are found by walking the pack's own
 * graph. Which compile option turns a pass on does not matter, so a stale copy behind a
 * switched-off option is still caught before anyone switches it on. {@link
 * LightCellStrideContract} checks both of its tiers for the same reason. A pass that never reads
 * the buffer is never scanned.
 */
public final class EntityOccluderStrideContract {
    private static final Pattern MOJ_IMPORT = Pattern.compile("#moj_import\\s*<([a-z0-9_.-]+):([^>]+)>");

    private static final Pattern HEADER_WORDS_DECL =
            Pattern.compile("const\\s+int\\s+ENTITY_OCCLUDER_HEADER_WORDS\\s*=\\s*(\\d+)\\s*;");
    private static final Pattern MAX_DECL =
            Pattern.compile("const\\s+int\\s+ENTITY_OCCLUDER_MAX\\s*=\\s*(\\d+)\\s*;");
    private static final Pattern RECORD_WORDS_DECL =
            Pattern.compile("const\\s+int\\s+ENTITY_OCCLUDER_RECORD_WORDS\\s*=\\s*(\\d+)\\s*;");

    private EntityOccluderStrideContract() {}

    /**
     * @param graph   the pack's graph, walked for passes declaring {@link
     *                EntityOccluderBuffer#TARGET} as an input
     * @param sources pack shader source text keyed by path relative to the pack root (for example
     *                "shaders/post/celestial_shadow.fsh"), the same map {@link
     *                ShaderImports#validate} and {@link PaletteStrideContract#validate} take
     */
    public static void validate(GraphSpec graph, Map<String, String> sources) {
        for (PassSpec p : graph.passes()) {
            if (p.type() != PassType.FULLSCREEN && p.type() != PassType.PARTICLES) {
                continue;
            }
            if (!p.inputs().contains(EntityOccluderBuffer.TARGET)) {
                continue;
            }
            String shaderKey = p.shader();
            if (shaderKey == null || !sources.containsKey(shaderKey)) {
                // No shader, or a shader naming a file the pack never shipped: that is already an
                // error elsewhere (ShaderImports or the pass runner), not one this check should raise.
                continue;
            }
            String composed = composedSource(shaderKey, sources);
            requireDeclaration(shaderKey, composed, "ENTITY_OCCLUDER_HEADER_WORDS", HEADER_WORDS_DECL,
                    EntityOccluderBuffer.HEADER_FLOATS);
            requireDeclaration(shaderKey, composed, "ENTITY_OCCLUDER_MAX", MAX_DECL,
                    EntityOccluderBuffer.MAX_OCCLUDERS);
            requireDeclaration(shaderKey, composed, "ENTITY_OCCLUDER_RECORD_WORDS", RECORD_WORDS_DECL,
                    EntityOccluderBuffer.FLOATS_PER_OCCLUDER);
        }
    }

    /**
     * The pass's own shader text plus every pack-local ({@code fornax_runtime}) include it pulls
     * in, followed all the way through, joined in the order visited. Engine ({@code fornax}) and
     * outside ({@code sodium}/{@code minecraft}) imports are left alone. The entity-occluder copy
     * only ever lives in a pack's own include, so walking those is enough. Skipping the rest
     * keeps this check from failing over an import it does not check. That is {@link
     * ShaderImports}'s job, already run before this at both call sites.
     */
    private static String composedSource(String shaderKey, Map<String, String> sources) {
        StringBuilder composed = new StringBuilder();
        collectPackIncludes(shaderKey, sources, composed, new HashSet<>());
        return composed.toString();
    }

    private static void collectPackIncludes(String key, Map<String, String> sources, StringBuilder out,
                                             Set<String> visited) {
        if (!visited.add(key)) {
            return; // a real cycle is an error for ShaderImports or the loader to raise, not this one.
        }
        String text = sources.get(key);
        if (text == null) {
            return;
        }
        out.append(text).append('\n');
        Matcher m = MOJ_IMPORT.matcher(text);
        while (m.find()) {
            if (m.group(1).equals("fornax_runtime")) {
                collectPackIncludes("shaders/include/" + m.group(2).trim(), sources, out, visited);
            }
        }
    }

    private static void requireDeclaration(String shaderKey, String composed, String name, Pattern pattern,
                                            int expected) {
        Matcher m = pattern.matcher(composed);
        if (!m.find()) {
            throw new FornaxPackError(shaderKey, name,
                    "reads the entity occluder set but never declares `const int " + name + " = " + expected
                            + ";` anywhere in its source. Every occluder record read would disappear "
                            + "behind the buffer's own size guard. Declare " + name
                            + " = " + expected + " to match this engine.");
        }
        int mirrored = Integer.parseInt(m.group(1));
        if (mirrored != expected) {
            throw new FornaxPackError(shaderKey, name,
                    "declares " + name + " = " + mirrored + ", but this engine sends the entity "
                            + "occluder set at " + name + " = " + expected + ". Every occluder record in "
                            + "this shader would read the wrong word. Update the pack to match this "
                            + "engine version.");
        }
    }
}
