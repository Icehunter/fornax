package dev.icehunter.fornax.pack;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load-time validation that every {@code #moj_import} in a pack's shader sources will actually
 * resolve at shader-compile time. blaze3d's import resolution ({@code ShaderManager$1.applyImport},
 * javap-verified) maps {@code <ns:file>} to the resource {@code ns:shaders/include/file} and, when
 * that resource is missing, splices the ERROR MESSAGE into the composed GLSL and moves on -- there
 * is no cross-namespace fallback and no load-time failure, just a broken shader discovered at
 * pipeline compile deep inside a render frame. Validating here instead surfaces a bad import as a
 * {@link FornaxPackError} at pack load/apply, where the UI can show it.
 */
public final class ShaderImports {
    private static final Pattern MOJ_IMPORT = Pattern.compile("#moj_import\\s*<([a-z0-9_.-]+):([^>]+)>");

    /** The include files the fornax engine jar itself still ships (assets/fornax/shaders/include/). */
    private static final Set<String> ENGINE_INCLUDES = Set.of("globals.glsl", "block_atlas.glsl");

    /** Namespaces served by other jars, out of a pack's (and this check's) hands. */
    private static final Set<String> EXTERNAL_NAMESPACES = Set.of("sodium", "minecraft");

    private ShaderImports() {}

    /**
     * @param sources pack shader sources keyed pack-root-relative ("shaders/post/ssao.fsh"), the
     *                same map {@code PackDiscovery.loadShaderSources} produces and
     *                {@code RuntimeShaderPack} serves
     */
    public static void validate(Map<String, String> sources) {
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Matcher matcher = MOJ_IMPORT.matcher(source.getValue());
            while (matcher.find()) {
                String namespace = matcher.group(1);
                String file = matcher.group(2).trim();
                switch (namespace) {
                    case "fornax_runtime" -> {
                        if (!sources.containsKey("shaders/include/" + file)) {
                            throw new FornaxPackError(source.getKey(), file,
                                    "imports <fornax_runtime:" + file + "> but the pack ships no shaders/include/" + file);
                        }
                    }
                    case "fornax" -> {
                        if (!ENGINE_INCLUDES.contains(file)) {
                            throw new FornaxPackError(source.getKey(), file,
                                    "imports <fornax:" + file + ">, but the engine only ships " + ENGINE_INCLUDES
                                            + " -- pack-local includes must use <fornax_runtime:...>");
                        }
                    }
                    default -> {
                        if (!EXTERNAL_NAMESPACES.contains(namespace)) {
                            throw new FornaxPackError(source.getKey(), file,
                                    "imports unknown shader include namespace <" + namespace + ":" + file + ">");
                        }
                    }
                }
            }
        }
    }
}
