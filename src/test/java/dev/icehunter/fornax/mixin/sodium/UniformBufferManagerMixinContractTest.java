package dev.icehunter.fornax.mixin.sodium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of the PBR settings upload skip. Vanilla's {@code MappableRingBuffer} turns
 * through exactly 3 GPU buffers, read from the decompiled class. Skipping an upload before a
 * changed value has reached all 3 leaves one buffer holding an old value that comes back the next
 * time the ring turns around to it. A mixin cannot be built in a unit test without a live client
 * (see {@code .claude/rules/mixins.md}), so this reads the source instead, the same shape as
 * {@code ChunkBuilderMeshingTaskMixinContractTest}.
 */
final class UniformBufferManagerMixinContractTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax/mixin/sodium/"
            + "UniformBufferManagerMixin.java");

    @Test
    void mixinIsRegistered() throws IOException {
        String mixins = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(mixins.contains("sodium.UniformBufferManagerMixin"),
                "UniformBufferManagerMixin must be listed in fornax.mixins.json or it never applies");
    }

    @Test
    void ringSizeConstantMatchesMappableRingBuffersThreeSlots() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("fornax$PBR_RING_SIZE = 3"),
                "the ring size must match MappableRingBuffer's own BUFFER_COUNT of 3; a smaller "
                        + "value lets an old buffer come back once the ring turns around to it");
    }

    @Test
    void valueComparisonRunsBeforeTheDirtyCountGateWhichRunsBeforeRotate() throws IOException {
        String source = Files.readString(SOURCE);
        int compareAt = source.indexOf("Arrays.equals(this.fornax$lastPbrValues, current)");
        int gateAt = source.indexOf("this.fornax$pbrSettingsDirtyCount <= 0");
        int rotateAt = source.indexOf("this.fornax$pbrSettingsData.rotate()");
        assertTrue(compareAt >= 0, "expected a comparison against the last written values");
        assertTrue(gateAt >= 0, "expected a count check before the upload");
        assertTrue(rotateAt >= 0, "expected the ring rotation call");
        assertTrue(compareAt < gateAt && gateAt < rotateAt,
                "compare the value, then check the count, then rotate the ring only if the upload "
                        + "is not skipped; any other order skips a write the ring still needs, or "
                        + "rotates without comparing the new value");
    }
}
