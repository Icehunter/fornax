package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GraphRunnerPackOwnsSkyTest {
    @Test
    void packOwnsSkyIsFalseWhenInactive() {
        // Headless JVM: no pack was ever activated, so isActive() is false and the compile
        // map is empty -- packOwnsSky() must be false on both counts, never throw.
        assertFalse(GraphRunner.packOwnsSky());
    }
}
