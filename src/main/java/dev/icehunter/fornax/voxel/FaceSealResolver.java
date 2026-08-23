package dev.icehunter.fornax.voxel;

import java.util.List;

/**
 * Resolves which of a voxel shape's six block-boundary faces are completely sealed.
 *
 * <p>Bit order matches Minecraft's direction data values: DOWN, UP, NORTH, SOUTH, WEST, EAST.
 * Coverage is rasterized at the same 1/16-block precision used by {@link VoxelShapeClassifier}.
 * A face is sealed only when the union of all boxes covers every one of its 16x16 samples.
 */
public final class FaceSealResolver {
    public static final int DOWN = 1;
    public static final int UP = 1 << 1;
    public static final int NORTH = 1 << 2;
    public static final int SOUTH = 1 << 3;
    public static final int WEST = 1 << 4;
    public static final int EAST = 1 << 5;
    public static final int ALL = DOWN | UP | NORTH | SOUTH | WEST | EAST;

    private FaceSealResolver() {
    }

    public static int resolve(VoxelShapeKind kind, List<VoxelShapeClassifier.PackedBox> boxes) {
        if (kind == VoxelShapeKind.FULL) {
            return ALL;
        }
        if (kind != VoxelShapeKind.PARTIAL || boxes.isEmpty()) {
            return 0;
        }

        boolean[][] covered = new boolean[6][16 * 16];
        for (VoxelShapeClassifier.PackedBox box : boxes) {
            if (box.minY() == 0) {
                cover(covered[0], box.minX(), box.maxX(), box.minZ(), box.maxZ());
            }
            if (box.maxY() == 16) {
                cover(covered[1], box.minX(), box.maxX(), box.minZ(), box.maxZ());
            }
            if (box.minZ() == 0) {
                cover(covered[2], box.minX(), box.maxX(), box.minY(), box.maxY());
            }
            if (box.maxZ() == 16) {
                cover(covered[3], box.minX(), box.maxX(), box.minY(), box.maxY());
            }
            if (box.minX() == 0) {
                cover(covered[4], box.minZ(), box.maxZ(), box.minY(), box.maxY());
            }
            if (box.maxX() == 16) {
                cover(covered[5], box.minZ(), box.maxZ(), box.minY(), box.maxY());
            }
        }

        int mask = 0;
        for (int face = 0; face < covered.length; face++) {
            boolean sealed = true;
            for (boolean sample : covered[face]) {
                sealed &= sample;
            }
            if (sealed) {
                mask |= 1 << face;
            }
        }
        return mask;
    }

    private static void cover(boolean[] face, int minA, int maxA, int minB, int maxB) {
        int loA = Math.max(0, Math.min(16, minA));
        int hiA = Math.max(0, Math.min(16, maxA));
        int loB = Math.max(0, Math.min(16, minB));
        int hiB = Math.max(0, Math.min(16, maxB));
        for (int b = loB; b < hiB; b++) {
            for (int a = loA; a < hiA; a++) {
                face[b * 16 + a] = true;
            }
        }
    }
}
