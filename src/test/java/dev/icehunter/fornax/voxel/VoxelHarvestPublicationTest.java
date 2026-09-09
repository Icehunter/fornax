package dev.icehunter.fornax.voxel;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelHarvestPublicationTest {
    @AfterEach
    void resumeAndDetach() {
        VoxelHarvestLifecycle.onModelsPublished();
        VoxelWindow.attachRegistry(null);
    }

    @Test
    void pausedHarvestDoesNotTouchModelsAndResumeRequestsAStationaryCameraFullScan() {
        VoxelWindow.recenter(3, 4, 5, 1);
        VoxelHarvestLifecycle.onBlockAtlasRetired();
        assertNull(SectionHarvester.harvest(null, null), "paused work must not even read block data");
        assertEquals(0, VoxelWindow.currentState().radius());
        VoxelWindow.recenterAndResync(3, 4, 5, 1, null, 0, 0, 0);
        assertEquals(0, VoxelWindow.currentState().radius(), "paused frames cannot consume the pending full shell");
        VoxelHarvestLifecycle.onModelsPublished();
        var previous = VoxelWindow.currentState();
        int[] positions = {0};
        VoxelWindow.enumerateResyncShell(previous.centerX(), previous.centerY(), previous.centerZ(), previous.radius(),
                3, 4, 5, 1, (x, y, z) -> positions[0]++);
        assertEquals(27, positions[0], "radius one must rescan all 3 cubed sections at the unchanged camera");
    }

    @Test
    void retiredResultsCannotPublishWhenSourceDiagnosticsAreDisabled() {
        VoxelSourceSummary.setEnabled(false);
        var oldResult = new SectionHarvester.Result(new byte[4096], new SectionPalette(List.of()));
        assertTrue(VoxelWindow.hasCurrentSourceSummary(oldResult));
        VoxelHarvestLifecycle.onBlockAtlasRetired();
        assertFalse(VoxelWindow.hasCurrentSourceSummary(oldResult));
        VoxelHarvestLifecycle.onModelsPublished();
        assertFalse(VoxelWindow.hasCurrentSourceSummary(oldResult));
        var currentResult = new SectionHarvester.Result(new byte[4096], new SectionPalette(List.of()));
        assertTrue(VoxelWindow.hasCurrentSourceSummary(currentResult));
    }
}
