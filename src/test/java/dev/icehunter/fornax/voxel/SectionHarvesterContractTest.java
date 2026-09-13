package dev.icehunter.fornax.voxel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the source text: {@code SectionHarvester.buildEntry} needs a live Minecraft client for its
 * model-set lookups ({@code Minecraft.getInstance().getModelManager()}), so this checks the source
 * instead of calling the method. {@code SectionHarvesterTest}'s own
 * {@code harvestNeverConstructsTheLiveModelShapeResolver} test does this for the same reason.
 */
class SectionHarvesterContractTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax/voxel/SectionHarvester.java");

    @Test void publishesRebuiltPartialGeometryThroughVoxelModelShapeReconstruct() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("VoxelModelShape.reconstruct("),
                "SectionHarvester must call VoxelModelShape.reconstruct to get a PARTIAL cell's rendered boxes");
    }

    /** The RT supplement is reached by the same background harvest, even when RT is disabled.
     * This pins that helper too; checking only the entrypoint cannot detect an indirect callback. */
    @Test void rtSupplementCannotInvokeLiveModelEmissionFromBackgroundHarvest() throws IOException {
        String source = Files.readString(SOURCE.resolveSibling("RtSectionGeometry.java"));
        assertFalse(source.contains(".emitQuads("),
                "RT metadata collection must not invoke live model emission on the harvest worker");
        assertFalse(source.contains("Renderer.get("),
                "Background harvest must not borrow a live renderer to collect RT geometry");
    }

    @Test void neverReintroducesTheLiveModelEmissionPath() throws IOException {
        String source = Files.readString(SOURCE);
        assertFalse(source.contains("VoxelModelShape.Resolver"),
                "SectionHarvester must not build shapes through the live emitQuads() Resolver: that needs "
                        + "a real world and position, and the harvest runs on a background thread");
        assertFalse(source.contains("emitQuads"),
                "SectionHarvester must never call the Fabric emitQuads hook itself");
        assertFalse(source.contains("shapeVariants.refine(index, null)"),
                "buildEntry rebuilds once for each distinct state, so there is no null-refine branch for each voxel");
    }
}
