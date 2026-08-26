package dev.icehunter.fornax.atlas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The countdown itself needs no live GPU device: {@link AtlasGenerationSchedule#tick}'s terminal
 * rebuild call chain (through {@code LabPbrAtlasPair.rebuild}/{@code BlockAtlasOverflow.rebuild}
 * into each listener's {@code build()}) checks {@code RenderSystem.tryGetDevice()} first, a plain
 * static-field read that returns {@code null} safely in a headless test JVM with no window ever
 * created -- so the full countdown, including its terminal tick, can be exercised directly rather
 * than as a source-level contract.
 */
class AtlasGenerationScheduleTest {
    // No GPU device exists in this test JVM, so build()/rebuild() are safe device==null no-ops;
    // regions() is never touched with an empty map, so a null ResourceManager is fine too.
    private static SpriteLoader.Preparations preparations() {
        return new SpriteLoader.Preparations(1, 1, 0, null, Map.of(), CompletableFuture.completedFuture(null));
    }

    @Test
    void hasPendingIsTrueImmediatelyAfterSchedulingAndFalseBeforeScheduling() {
        var location = TextureAtlas.LOCATION_BLOCKS;
        assertFalse(AtlasGenerationSchedule.hasPending(location));

        AtlasGenerationSchedule.scheduleRelease(location, preparations(), null, null,
                AtlasGenerationSchedule.RebuildScope.BLOCK_FULL);

        assertTrue(AtlasGenerationSchedule.hasPending(location));

        // Drain the countdown so this test doesn't leak pending state into the next one -- the
        // static map is process-wide, same as every other atlas registry in this package.
        for (int i = 0; i < 10; i++) {
            AtlasGenerationSchedule.tick(location);
        }
    }

    @Test
    void tickDoesNotClearPendingUntilTheCountdownReachesZero() {
        // A different location per test avoids cross-test interference through the shared static map.
        var location = Identifier.fromNamespaceAndPath("fornax_test", "second_atlas_location");
        AtlasGenerationSchedule.scheduleRelease(location, preparations(), null, null,
                AtlasGenerationSchedule.RebuildScope.SIDECARS_ONLY);

        // RETIRE_POLLS is 3 (see AtlasGenerationSchedule's own doc): two ticks must still leave the
        // rebuild pending, matching the destroy ring's own two-submit reclaim requirement plus one
        // frame of margin.
        AtlasGenerationSchedule.tick(location);
        assertTrue(AtlasGenerationSchedule.hasPending(location), "still pending after 1 of 3 ticks");
        AtlasGenerationSchedule.tick(location);
        assertTrue(AtlasGenerationSchedule.hasPending(location), "still pending after 2 of 3 ticks");
        AtlasGenerationSchedule.tick(location);
        assertFalse(AtlasGenerationSchedule.hasPending(location), "rebuilt and cleared after 3 of 3 ticks");
    }

    @Test
    void aSecondScheduleBeforeTheFirstCompletesResetsTheCountdownRatherThanKeepingItsOwn() {
        var location = TextureAtlas.LOCATION_BLOCKS;
        AtlasGenerationSchedule.scheduleRelease(location, preparations(), null, null,
                AtlasGenerationSchedule.RebuildScope.BLOCK_FULL);
        AtlasGenerationSchedule.tick(location);
        AtlasGenerationSchedule.tick(location);
        // Two ticks in, one away from completing -- a second switch arrives before that happens.
        AtlasGenerationSchedule.scheduleRelease(location, preparations(), null, null,
                AtlasGenerationSchedule.RebuildScope.BLOCK_OVERFLOW_ONLY);

        // If the countdown had NOT reset, a third tick here would complete the (stale) first
        // reload's rebuild instead of waiting out the fresh one.
        AtlasGenerationSchedule.tick(location);
        assertTrue(AtlasGenerationSchedule.hasPending(location),
                "a reschedule must reset the countdown, not let the superseded one finish early");

        AtlasGenerationSchedule.tick(location);
        AtlasGenerationSchedule.tick(location);
        assertFalse(AtlasGenerationSchedule.hasPending(location));
    }

    @Test
    void tickIsANoOpWhenNothingIsPendingForThisLocation() {
        // Must not throw when called every frame for a location that was never scheduled -- the
        // animation-poll hook calls this for every mirrored atlas location unconditionally.
        AtlasGenerationSchedule.tick(TextureAtlas.LOCATION_BLOCKS);
        assertFalse(AtlasGenerationSchedule.hasPending(TextureAtlas.LOCATION_BLOCKS));
    }
}
