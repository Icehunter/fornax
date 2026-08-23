package dev.icehunter.fornax.voxel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FaceSealResolverTest {
    @Test
    void fullCubeSealsAllFaces() {
        assertEquals(FaceSealResolver.ALL, FaceSealResolver.resolve(VoxelShapeKind.FULL, List.of()));
    }

    @Test
    void emptyAndCrossSealNoFaces() {
        assertEquals(0, FaceSealResolver.resolve(VoxelShapeKind.EMPTY, List.of()));
        assertEquals(0, FaceSealResolver.resolve(VoxelShapeKind.CROSS, List.of()));
    }

    @Test
    void bottomSlabSealsOnlyDownFace() {
        var slab = new VoxelShapeClassifier.PackedBox(0, 0, 0, 16, 8, 16);
        assertEquals(FaceSealResolver.DOWN,
                FaceSealResolver.resolve(VoxelShapeKind.PARTIAL, List.of(slab)));
    }

    @Test
    void unionCoverageCanSealFace() {
        var left = new VoxelShapeClassifier.PackedBox(0, 0, 0, 8, 8, 16);
        var right = new VoxelShapeClassifier.PackedBox(8, 0, 0, 16, 8, 16);
        assertEquals(FaceSealResolver.DOWN,
                FaceSealResolver.resolve(VoxelShapeKind.PARTIAL, List.of(left, right)));
    }

    @Test
    void fencePostDoesNotSealAWholeFace() {
        var post = new VoxelShapeClassifier.PackedBox(6, 0, 6, 10, 16, 10);
        assertEquals(0, FaceSealResolver.resolve(VoxelShapeKind.PARTIAL, List.of(post)));
    }
}
