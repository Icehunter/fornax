package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the Vulkan write boundary without a live device. The ledger tests exercise token behavior;
 * these checks cannot establish driver visibility or an in-client result. */
class VoxelSectionStateUploadContractTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/dev/icehunter/fornax/voxel/" + name + ".java"));
    }

    @Test void sectionStateIsPublishedInsideThePayloadSubmissionAfterAllPayloadWrites() throws Exception {
        String upload = source("BrickGridUpload");
        assertTrue(upload.contains("isEnabledBufferTarget(VoxelSectionState.TARGET)"),
                "Section-state storage must be optional");
        int batch = upload.indexOf("private static void uploadBatchLocked");
        int payload = upload.indexOf("vkCmdUpdateBuffer(cmd, payloadBuffer, payloadOffset, payloadScratch)", batch);
        int committed = upload.indexOf("vkCmdUpdateBuffer(cmd, sectionStateBuffer, sectionStateOffset, sectionStateScratch)", batch);
        int barrier = upload.indexOf("recordUploadToComputeReadBarrier(cmd, stack)", batch);
        assertTrue(payload >= batch && committed > payload && barrier > committed,
                "The state may certify only the payload written in this transfer");
    }

    @Test void queuedSnapshotIsRejectedBeforeAnyPayloadRangeIsWritten() throws Exception {
        String upload = source("BrickGridUpload");
        int batch = upload.indexOf("private static void uploadBatchLocked");
        int guard = upload.indexOf("VoxelWindow.isCurrentSectionState(slot, item.sectionState())", batch);
        int payload = upload.indexOf("vkCmdUpdateBuffer(cmd, occupancyBuffer, occupancyOffset, occupancyScratch)", batch);
        assertTrue(guard > batch && payload > guard,
                "An older queued batch may not overwrite a replacement owner or newer harvest");
    }

    @Test void clearAndStorageResetInvalidateGpuAndQueuedSectionState() throws Exception {
        String upload = source("BrickGridUpload");
        int clear = upload.indexOf("private static void clearOccupancySlotsLocked");
        assertTrue(upload.indexOf("vkCmdFillBuffer(cmd, sectionStateBuffer, sectionStateOffset,", clear) > clear,
                "Pending summary and section state must be written in the same clear transfer");
        String window = source("VoxelWindow");
        assertTrue(window.contains("sectionStates.reset(storageGeneration)"));
        assertTrue(window.contains("sectionStates.invalidate(exposed)"));
        assertTrue(window.contains("BrickGridUpload.invalidateSectionStates(newRegistry)"),
                "A same-size storage reset must not retain committed metadata");
    }
    @Test void sourceSummaryPublishesBeforeStateAndTotalsFollowTransferCompletion() throws Exception {
        String upload = source("BrickGridUpload");
        int batch = upload.indexOf("private static void uploadBatchLocked");
        int source = upload.indexOf("vkCmdUpdateBuffer(cmd, sourceSummaryBuffer, sourceSummaryOffset, sourceSummaryScratch)", batch);
        int state = upload.indexOf("vkCmdUpdateBuffer(cmd, sectionStateBuffer, sectionStateOffset, sectionStateScratch)", batch);
        int barrier = upload.indexOf("recordUploadToComputeReadBarrier(cmd, stack)", batch);
        int totals = upload.indexOf("VoxelWindow.onSectionUploadCommitted(item)", batch);
        assertTrue(source > batch && state > source && totals > barrier,
                "Source inventory and state share one transfer; CPU totals may change only after its completion wait");
        int clear = upload.indexOf("private static void clearOccupancySlotsLocked");
        assertTrue(upload.indexOf("vkCmdFillBuffer(cmd, sourceSummaryBuffer, sourceSummaryOffset,", clear) > clear);
    }

    @Test void queuedOwnerReplacementPreservesLightClearAndResetFailureCannotStaySilent() throws Exception {
        String window = source("VoxelWindow");
        assertTrue(window.contains("sectionStates.needsLightClear"));
        assertTrue(window.contains("sectionStates.commit(item.slot(), item.sectionState())"));
        int synchronize = window.indexOf("public static boolean synchronizeSourceGeneration");
        assertTrue(window.indexOf("BrickGridUpload.invalidateSectionStates(newRegistry, atlasGeneration)", synchronize)
                < window.indexOf("sourceAtlasGeneration = atlasGeneration", synchronize));
        String upload = source("BrickGridUpload");
        assertTrue(upload.contains("Cannot invalidate allocated voxel section metadata without an upload backend"));
    }

}
