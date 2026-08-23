package dev.icehunter.fornax.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SkyModCompatTest {
    @AfterEach
    void reset() {
        SkyModCompat.overrideLoadedModsForTest(null);
    }

    @Test
    void noCompetingModMeansNoYield() {
        SkyModCompat.overrideLoadedModsForTest(Set.of("sodium", "fabric-api"));
        assertFalse(SkyModCompat.competingSkyModLoaded());
    }

    @Test
    void nuitTriggersYield() {
        SkyModCompat.overrideLoadedModsForTest(Set.of("nuit"));
        assertTrue(SkyModCompat.competingSkyModLoaded());
    }

    @Test
    void fabricskyboxesTriggersYield() {
        SkyModCompat.overrideLoadedModsForTest(Set.of("fabricskyboxes"));
        assertTrue(SkyModCompat.competingSkyModLoaded());
    }
}
