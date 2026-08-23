package dev.icehunter.fornax.pack.layout;

import dev.icehunter.fornax.pack.option.OptionAnnotation;
import dev.icehunter.fornax.pack.option.OptionType;
import dev.icehunter.fornax.pack.option.PackOption;

import java.util.Map;

/**
 * Rewrites a pack shader's annotated {@code #define}s to the chosen compile-option values, and strips
 * runtime-option defines (their values arrive through the {@code u_PackOptions} uniform block). Non-annotated
 * lines pass through unchanged. Output is the final GLSL source string served via {@link RuntimeShaderPack}.
 */
public final class DefineRewriter {
    private DefineRewriter() {}

    /**
     * @param options the pack's merged option table (by name). Each annotated line is fully
     *                self-describing -- {@link OptionAnnotation#parseLine(String)} re-derives everything
     *                a line needs from its own text -- so this map isn't consulted today; it's kept in the
     *                signature to match the option-plumbing contract the rest of the pipeline depends on
     *                (and as a seam for a future cross-check against the scanned table).
     */
    public static String rewrite(String source, Map<String, PackOption> options, Map<String, String> compileValues) {
        StringBuilder out = new StringBuilder(source.length());
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            out.append(rewriteLine(lines[i], compileValues));
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static String rewriteLine(String line, Map<String, String> compileValues) {
        var parsed = OptionAnnotation.parseLine(line);
        if (parsed.isEmpty()) return line;
        PackOption opt = parsed.get();
        String indent = line.substring(0, line.length() - line.stripLeading().length());
        // Search for the annotation's own "//[" marker, not the first "//": a commented-out boolean's
        // line starts with its OWN leading "// " before "#define", and indexOf("//") would grab that
        // instead of the trailing annotation, corrupting the rewritten line.
        String annotation = line.substring(line.indexOf("//["));

        if (opt.type() == OptionType.RUNTIME) {
            return indent + "// [fornax] runtime option " + opt.name() + " provided by u_PackOptions";
        }

        String value = compileValues.getOrDefault(opt.name(), opt.defaultValue());
        if (opt.isBoolean()) {
            boolean on = !value.equals("0") && !value.equalsIgnoreCase("false");
            return on
                    ? indent + "#define " + opt.name() + " " + annotation
                    : indent + "// #define " + opt.name() + " " + annotation;
        }
        return indent + "#define " + opt.name() + " " + value + " " + annotation;
    }
}
