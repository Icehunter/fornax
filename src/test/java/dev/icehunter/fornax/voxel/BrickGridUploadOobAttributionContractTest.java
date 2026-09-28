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

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "Missing upload method " + signature);
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, "Missing method end for " + signature);
        return source.substring(start, end);
    }

    @Test
    void singleSlotUploadsReachTheSameAttributedBoundsChecksAsBatches() throws IOException {
        String source = Files.readString(SOURCE).replace("\r\n", "\n");
        assertTrue(method(source, "public static void uploadSlot(")
                .contains("uploadSlots(registry, List.of(new SlotUpload(slot, result, false)))"));
        assertTrue(method(source, "public static void uploadSlots(").contains("queue(registry).publish("));
        assertTrue(method(source, "static void drainQueuedUploads(").contains("uploadSlotsNow(registry, uploads)"));
        assertTrue(method(source, "private static void uploadSlotsNow(").contains("uploadBatchLocked(resources,"));
    }

    @Test
    void uploadBatchLockedAttributesEachBuffersOobDropToItsOwnTarget() throws IOException {
        String source = Files.readString(SOURCE).replace("\r\n", "\n");
        String method = method(source, "private static void uploadBatchLocked(");

        assertTrue(method.contains("logOobDrop(OCCUPANCY_TARGET,"), "occupancy check must log its own target");
        assertTrue(method.contains("logOobDrop(PAYLOAD_TARGET,"), "payload check must log its own target");
        assertTrue(method.contains("logOobDrop(FACE_SEAL_TARGET,"), "faceSeal check must log its own target");
        assertTrue(method.contains("logOobDrop(PALETTE_TARGET,"), "palette check must log its own target");
        assertTrue(method.contains("logOobDrop(BRICK_SUMMARY_TARGET,"), "summary check must log its own target");
        assertTrue(method.contains("logOobDrop(VoxelLightmap.TARGET,"), "lightmap check must log its own target");
        assertTrue(method.contains("logOobDrop(VoxelFaceTexture.TARGET,"), "face texture check must log its own target");
    }
}
