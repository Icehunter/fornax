package dev.icehunter.fornax.voxel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code uploadSlot}/{@code uploadBatchLocked} need a live GPU device and a real {@code
 * TargetRegistry} to exercise directly, the same constraint that makes {@code
 * BuiltinResolutionContractTest} a source-level test. Pins that each buffer's out-of-bounds check
 * logs its own target name rather than every check in the OR-chain reporting {@code
 * OCCUPANCY_TARGET}, which would misattribute a payload/faceSeal/palette/summary overflow to the
 * wrong buffer in the diagnostic.
 */
class BrickGridUploadOobAttributionContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/voxel/BrickGridUpload.java");

    @Test
    void uploadSlotAttributesEachBuffersOobDropToItsOwnTarget() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static void uploadSlot(");
        assertTrue(methodStart >= 0, "uploadSlot must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("logOobDrop(OCCUPANCY_TARGET,"), "occupancy check must log its own target");
        assertTrue(method.contains("logOobDrop(PAYLOAD_TARGET,"), "payload check must log its own target");
        assertTrue(method.contains("logOobDrop(FACE_SEAL_TARGET,"), "faceSeal check must log its own target");
        assertTrue(method.contains("logOobDrop(PALETTE_TARGET,"), "palette check must log its own target");
        assertTrue(method.contains("logOobDrop(BRICK_SUMMARY_TARGET,"), "summary check must log its own target");
    }

    @Test
    void uploadBatchLockedAttributesEachBuffersOobDropToItsOwnTarget() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("private static void uploadBatchLocked(");
        assertTrue(methodStart >= 0, "uploadBatchLocked must still exist");
        String method = source.substring(methodStart, source.indexOf("\n    }\n", methodStart));

        assertTrue(method.contains("logOobDrop(OCCUPANCY_TARGET,"), "occupancy check must log its own target");
        assertTrue(method.contains("logOobDrop(PAYLOAD_TARGET,"), "payload check must log its own target");
        assertTrue(method.contains("logOobDrop(FACE_SEAL_TARGET,"), "faceSeal check must log its own target");
        assertTrue(method.contains("logOobDrop(BRICK_SUMMARY_TARGET,"), "summary check must log its own target");
    }
}
