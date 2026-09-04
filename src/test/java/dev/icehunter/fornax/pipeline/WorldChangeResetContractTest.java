package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a dimension change or world rejoin resets {@link WetnessState} and
 * {@link DayCrossfadeState}, not just {@code WaterSurfaceTracker}.
 *
 * <p>Both accumulators ease their lane over real time and both document a {@code reset()} for
 * exactly this discontinuity: a fade would misrepresent a genuine jump (new dimension, new day
 * on rejoin) as a gradual change. Neither can be exercised end to end without a live
 * {@code ClientLevel}, so this reads {@code GlobalUniformsWriteMixin}'s source instead, same
 * pattern as {@code GlobalsLayoutContractTest}.
 */
class WorldChangeResetContractTest {

    private static final Path WRITER =
            Path.of("src/main/java/dev/icehunter/fornax/mixin/sodium/GlobalUniformsWriteMixin.java");
    private static final String LEVEL_CHANGE_GUARD = "fornax$smoothedWaterLevel) {";

    @Test
    void levelChangeGuardResetsWetnessAndDayCrossfade() throws IOException {
        String source = Files.readString(WRITER);
        int guardAt = source.indexOf(LEVEL_CHANGE_GUARD);
        assertTrue(guardAt >= 0, "could not find the level-change guard in " + WRITER);
        String guardBody = source.substring(guardAt, source.indexOf("}", source.indexOf("{", guardAt) + 1) + 1);

        assertTrue(guardBody.contains("WetnessState.reset("),
                "the level-change guard does not reset WetnessState: a dimension change or"
                        + " world rejoin will ease into the new rain level instead of snapping to it");
        assertTrue(guardBody.contains("DayCrossfadeState.reset("),
                "the level-change guard does not reset DayCrossfadeState: a dimension change or"
                        + " world rejoin will fade a per-day pack value over 20 real seconds instead"
                        + " of snapping straight to the new day");
    }
}
