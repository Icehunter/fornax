package dev.icehunter.fornax.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands Mojang-style imports for shader stages compiled directly with shaderc. */
public final class RawShaderImports {
    private static final Pattern IMPORT = Pattern.compile(
            "#moj_import\\s*<([a-z0-9_.-]+):([^>]+)>");

    private RawShaderImports() {
    }

    public static String expand(String source, Map<String, String> packSources, String debugName) {
        return expand(source, packSources, debugName, new HashSet<>());
    }

    private static String expand(String source, Map<String, String> packSources, String debugName,
                                 Set<String> active) {
        Matcher matcher = IMPORT.matcher(source);
        StringBuilder expanded = new StringBuilder(source.length());
        while (matcher.find()) {
            String namespace = matcher.group(1);
            String file = matcher.group(2).trim();
            String key = namespace + ":" + file;
            if (!active.add(key)) {
                throw new IllegalStateException("raw shader import cycle in " + debugName + " at <" + key + ">");
            }
            String include = switch (namespace) {
                case "fornax_runtime" -> packSources.get("shaders/include/" + file);
                case "fornax" -> engineInclude(file);
                default -> throw new IllegalStateException("raw shader '" + debugName
                        + "' imports <" + key + ">, but direct shaderc compilation can only expand"
                        + " fornax and fornax_runtime includes");
            };
            if (include == null) {
                throw new IllegalStateException("raw shader '" + debugName
                        + "' cannot resolve import <" + key + ">");
            }
            String nested = expand(include, packSources, debugName, active);
            active.remove(key);
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(nested));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static String engineInclude(String file) {
        String path = "/assets/fornax/shaders/include/" + file;
        try (InputStream stream = RawShaderImports.class.getResourceAsStream(path)) {
            if (stream == null) return null;
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed reading engine shader include " + path, e);
        }
    }
}
