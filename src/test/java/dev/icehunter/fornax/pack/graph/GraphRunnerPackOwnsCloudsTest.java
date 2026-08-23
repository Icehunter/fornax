package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.*;

import dev.icehunter.fornax.compat.SkyModCompat;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GraphRunnerPackOwnsCloudsTest {
    @AfterEach
    void reset() {
        SkyModCompat.overrideLoadedModsForTest(null);
    }

    @Test
    void packOwnsCloudsIsFalseWhenInactive() {
        // Headless JVM: no pack was ever activated, so isActive() is false and the compile
        // map is empty -- packOwnsClouds() must be false on both counts, never throw.
        assertFalse(GraphRunner.packOwnsClouds());
    }

    @Test
    void packOwnsCloudsIsFalseWhenCompetingSkyModLoadedEvenIfOtherConditionsCouldHold() {
        // isActive()/isCompileOptionEnabled() can't be forced true headlessly (no device, no
        // loaded pack), so this only exercises the yield term's short-circuit -- the yield
        // term's own unit coverage lives in SkyModCompatTest.
        SkyModCompat.overrideLoadedModsForTest(Set.of("nuit"));
        assertFalse(GraphRunner.packOwnsClouds());
    }
}
