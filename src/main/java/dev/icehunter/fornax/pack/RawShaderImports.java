package dev.icehunter.fornax.pack;

import dev.icehunter.fornax.pack.layout.GlslCommentStripper;

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
        // Stripped before scanning for directives: the IMPORT regex has no notion of a comment, so
        // a `//`-documented usage example inside a source file (e.g. a doc comment that shows
        // "#moj_import <fornax_runtime:x.glsl>" as prose) reads as a real, second import and can
        // fire a false self-referential "cycle" while that file is still being expanded. Fullscreen
        // and geometry passes never hit this: their source is served pre-stripped via
        // RuntimeShaderPack.servedSources, while this raw-shaderc path deliberately keeps sources
        // unstripped for compute compilation. Uses GlslCommentStripper, the same stripper
        // RuntimeShaderPack uses for the served-source path, rather than a second implementation of
        // "what counts as a GLSL comment".
        source = GlslCommentStripper.strip(source);
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
