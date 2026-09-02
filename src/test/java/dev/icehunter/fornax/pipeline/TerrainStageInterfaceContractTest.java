package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fallback terrain vertex stage's {@code out} set against the fragment stage's {@code in}
 * set, and both against the compile guards each file applies.
 *
 * <p>A varying is one declaration written twice, matched by name and type at link time. Guarding one
 * side with a {@code #define} the other side never tests makes the pair link only while that define
 * is set. The define arrives from the chunk renderer's own constant set, so a dependency change can
 * break the pair, and the failure is a link error at renderer init with no compile-time signal. This
 * pair is the no-pack path, which is what a first-run user sees.
 *
 * <p>Reads source text rather than compiling, like {@code GlobalsLayoutContractTest}: these stages
 * need a live GPU and a bound Sodium bind group to compile.
 */
class TerrainStageInterfaceContractTest {

    private static final Path VERTEX =
            Path.of("src/main/resources/assets/fornax/shaders/blocks/terrain.vsh");
    private static final Path FRAGMENT =
            Path.of("src/main/resources/assets/fornax/shaders/blocks/terrain.fsh");

    /** A varying declaration: optional `flat`, the direction, the type, the name. */
    private static final Pattern VARYING =
            Pattern.compile("^\\s*(?:flat\\s+)?(in|out)\\s+(\\w+)\\s+(v_\\w+)\\s*;", Pattern.MULTILINE);

    /**
     * Maps each varying name to its declared type, skipping anything inside a preprocessor
     * conditional: a guarded varying is invisible to the other stage's unguarded declaration.
     */
    private static Map<String, String> unguardedVaryings(Path path, String direction) throws IOException {
        assertTrue(Files.exists(path), path + " is missing, so this contract cannot be checked");

        Map<String, String> found = new LinkedHashMap<>(); // ordered for readable failures
        int depth = 0;
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#if")) {
                depth++;
                continue;
            }
            if (trimmed.startsWith("#endif")) {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth > 0) {
                continue;
            }
            Matcher m = VARYING.matcher(line);
            if (m.find() && m.group(1).equals(direction)) {
                found.put(m.group(3), m.group(2));
            }
        }
        return found;
    }

    @Test
    void everyFragmentInputIsAnUnguardedVertexOutput() throws IOException {
        Map<String, String> outs = unguardedVaryings(VERTEX, "out");
        Map<String, String> ins = unguardedVaryings(FRAGMENT, "in");

        assertTrue(ins.size() >= 5, "parsed suspiciously few fragment inputs: " + ins.keySet());

        for (Map.Entry<String, String> in : ins.entrySet()) {
            String name = in.getKey();
            assertTrue(outs.containsKey(name),
                    "terrain.fsh declares `in " + in.getValue() + " " + name + ";` with no compile guard,"
                            + " but terrain.vsh has no matching unguarded `out`: either it is missing, or it"
                            + " sits behind a #ifdef the fragment stage does not test, which fails at"
                            + " renderer init. Unguarded vertex outputs: " + outs.keySet());
            assertEquals(in.getValue(), outs.get(name),
                    "terrain.vsh and terrain.fsh disagree on the type of " + name
                            + "; a varying is matched by name AND type at link time");
        }
    }

    @Test
    void theSectionTimeLaneIsFetchedWithoutACompileGuard() throws IOException {
        String vertex = Files.readString(VERTEX);

        int fetch = vertex.indexOf("texelFetch(u_SectionTimeInfo");
        assertTrue(fetch >= 0, "terrain.vsh no longer fetches u_SectionTimeInfo at all");

        // The lane describes when a section became drawable, a world fact, so it must not be
        // conditional on a styling define.
        int depth = 0;
        for (String line : vertex.substring(0, fetch).lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#if")) {
                depth++;
            } else if (trimmed.startsWith("#endif")) {
                depth = Math.max(0, depth - 1);
            }
        }
        assertEquals(0, depth,
                "the u_SectionTimeInfo fetch sits inside a preprocessor conditional. The buffer is bound"
                        + " on the shared terrain bind group for every draw, so guarding the read only makes"
                        + " v_FadeFactor undefined on the path where the guard is off.");
    }
}
