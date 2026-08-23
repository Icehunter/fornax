package dev.icehunter.fornax.pack.option;

import dev.icehunter.fornax.pack.FornaxPackError;

import java.util.LinkedHashMap;
import java.util.Map;

/** Walks pack shader sources, parsing every annotated {@code #define} into a merged option table. */
public final class OptionScanner {
    private OptionScanner() {}

    /**
     * @param shaderSources path -> raw GLSL text, iterated in insertion order (callers pass a
     *                      {@code LinkedHashMap} sorted by path for determinism).
     */
    public static Map<String, PackOption> scan(Map<String, String> shaderSources) {
        Map<String, PackOption> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : shaderSources.entrySet()) {
            String[] lines = file.getValue().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                PackOption opt = OptionAnnotation.parseLine(lines[i], file.getKey(), i + 1).orElse(null);
                if (opt == null) continue;
                PackOption existing = merged.get(opt.name());
                if (existing == null) {
                    merged.put(opt.name(), opt);
                } else if (!existing.equals(opt)) {
                    throw new FornaxPackError(file.getKey(), opt.name(),
                            "line " + (i + 1) + ": conflicting declarations of option '" + opt.name()
                                    + "' across shader files (declarations must be identical to merge)");
                }
            }
        }
        return merged;
    }
}
