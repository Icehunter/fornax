package dev.icehunter.fornax;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Default keys for the diagnostics, and the two ways a diagnostic becomes unreachable.
 *
 * <p>Unbound: it exists, it works, and nobody finds it. The frame-profile dump shipped that way and
 * three separate attempts to read a profile produced an empty log with no error. Double-bound: two
 * diagnostics on one key, where pressing it runs whichever the tick loop reaches first and the
 * other looks broken.
 */
class KeybindDefaultsTest {

    private static final Pattern BINDING = Pattern.compile(
            "\"(key\\.fornax\\.[a-z_]+)\", InputConstants\\.Type\\.KEYSYM, InputConstants\\.(KEY_[A-Z0-9]+|UNKNOWN)");

    private static List<String[]> bindings() throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String file : new String[]{"FornaxKeybind.java", "debug/FornaxDebugKeys.java"}) {
            Matcher matcher = BINDING.matcher(
                    Files.readString(Path.of("src/main/java/dev/icehunter/fornax/").resolve(file)));
            while (matcher.find()) {
                out.add(new String[]{matcher.group(1), matcher.group(2)});
            }
        }
        return out;
    }

    /**
     * The frame profile has no key of its own: F7 is vanilla's cinematic camera and F8-F10 are this
     * mod's own. It rides the readback key instead, so it stays reachable without a bind. An
     * unbound diagnostic with no other route produces an empty log and no error, which is how three
     * attempts to read a profile came back with nothing.
     */
    @Test
    void theFrameProfileIsReachableWithoutABindOfItsOwn() throws IOException {
        String key = bindings().stream()
                .filter(b -> b[0].equals("key.fornax.dump_profiler"))
                .map(b -> b[1])
                .findFirst()
                .orElseThrow(() -> new AssertionError("the dump keybind must be registered"));
        assertEquals("UNKNOWN", key, "no free function key remains to claim");

        String debugKeys = Files.readString(
                Path.of("src/main/java/dev/icehunter/fornax/debug/FornaxDebugKeys.java"));
        assertTrue(debugKeys.contains("ProfilerOverlay.dumpToLog();"),
                "so the readback key must dump the profile too, or it is unreachable");
    }

    /** Two diagnostics on one key means pressing it runs one and the other reads as broken. */
    @Test
    void noTwoBoundDiagnosticsShareAKey() throws IOException {
        List<String[]> bound = bindings().stream().filter(b -> !b[1].equals("UNKNOWN")).toList();
        for (int i = 0; i < bound.size(); i++) {
            for (int j = i + 1; j < bound.size(); j++) {
                assertTrue(!bound.get(i)[1].equals(bound.get(j)[1]),
                        bound.get(i)[0] + " and " + bound.get(j)[0] + " both default to "
                                + bound.get(i)[1]);
            }
        }
    }
}
