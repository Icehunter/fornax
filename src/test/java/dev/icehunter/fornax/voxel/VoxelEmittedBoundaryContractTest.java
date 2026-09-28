package dev.icehunter.fornax.voxel;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wiring pins complement the pure capture tests: a helper alone never observes live geometry. */
class VoxelEmittedBoundaryContractTest {
    private static final Path JAVA = Path.of("src/main/java/dev/icehunter/fornax");

    @Test void rendererEmissionIsObservedBeforeNeighbourCulling() throws Exception {
        Path hook = JAVA.resolve("mixin/sodium/BlockRendererBoundaryMixin.java");
        assertTrue(Files.exists(hook), "Live Fabric emissions need an observer; fallback baked parts do not prove wrapped geometry");
        String source = Files.readString(hook);
        assertTrue(source.contains("@WrapOperation"));
        assertTrue(source.contains("BlockStateModel;emitQuads("));
        assertTrue(source.contains("VoxelBoundaryCapture.emit"));
        assertTrue(Files.readString(Path.of("src/main/resources/fornax.mixins.json"))
                .contains("sodium.BlockRendererBoundaryMixin"));
    }

    @Test void sectionPublicationAndHarvestUseCapturedPerPositionFacts() throws Exception {
        String meshing = Files.readString(JAVA.resolve("mixin/sodium/ChunkBuilderMeshingTaskMixin.java"));
        assertTrue(meshing.contains("VoxelBoundaryCapture.beginSection"), "Capture must run inside the renderer-owned mesh scope");
        assertTrue(meshing.contains("VoxelBoundaryCapture.finishSection"));
        assertTrue(meshing.contains("finally"), "Failed and cancelled builds must release thread-local capture state");
        String harvest = Files.readString(JAVA.resolve("voxel/SectionHarvester.java"));
        assertTrue(harvest.contains("VoxelBoundaryCapture.snapshot"));
        assertTrue(harvest.contains("shapeVariants.boundary"));
        assertTrue(Files.readString(JAVA.resolve("voxel/VoxelHarvestLifecycle.java"))
                .contains("VoxelBoundaryCapture.clear"), "Retired atlas references cannot survive in the capture cache");
    }

    @Test void liveCaptureAndShapeRefinementUseTheTestedAtlasIdLookup() throws Exception {
        String capture = Files.readString(JAVA.resolve("voxel/VoxelBoundaryCapture.java"));
        String shape = Files.readString(JAVA.resolve("voxel/VoxelModelShape.java"));
        String lookup = Files.readString(JAVA.resolve("voxel/VoxelAtlasLookup.java"));
        assertTrue(capture.contains("new Capture(VoxelAtlasLookup::sprite, tint, candidateBoxes)"));
        assertTrue(shape.contains("VoxelAtlasLookup::sprite"));
        assertTrue(lookup.contains("getAtlasOrThrow"));
        assertTrue(lookup.contains("lookup.apply(atlas.getId())"));
        assertFalse(capture.contains("getTextureLocation()"));
        assertFalse(shape.contains("getTextureLocation()"));
        assertFalse(lookup.contains("getTextureLocation()"));
        assertTrue(capture.contains("capture.failure()"), "The first rejection must report the retained exception");
        assertTrue(capture.contains("if (!REPORTED.add(key)) return"), "Repeated rejects must not flood the log");
    }
}
