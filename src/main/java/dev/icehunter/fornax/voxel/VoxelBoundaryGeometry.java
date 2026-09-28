package dev.icehunter.fornax.voxel;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.core.Direction;

/** Proves the palette boxes have exactly the model's axis-aligned closed boundary.
 * Alpha and lighting are not part of this geometric fact. Unsupported models retain no proof. */
final class VoxelBoundaryGeometry {
    // The palette quantizes each coordinate to Minecraft's 1/16-block model grid.
    private static final int GRID = 16;
    // One exterior face and one coincident overlay per face of each ABI box bounds proof work.
    private static final int MAX_QUADS = 12 * VoxelShapeClassifier.MAX_BOXES;

    private VoxelBoundaryGeometry() { }

    static boolean supportsModel(BlockStateModel model, List<BlockStateModelPart> parts) {
        // Custom emitters can replace collectParts geometry at a world position. Their fallback
        // parts cannot certify the final rendered boundary, even when those parts form a box.
        if (model.getClass() != SingleVariant.class && model.getClass() != MultiPartModel.class) return false;
        return parts.stream().allMatch(part -> part.getClass() == SimpleModelWrapper.class);
    }

    static boolean certifies(List<BlockStateModelPart> parts, VoxelShapeKind kind,
                             List<VoxelShapeClassifier.PackedBox> boxes) {
        if (kind != VoxelShapeKind.FULL && kind != VoxelShapeKind.PARTIAL) return false;
        List<BakedQuad> quads = new ArrayList<>();
        boolean hasCoverageMaterial = false;
        for (var part : parts) for (int direction = 0; direction <= 6; direction++) {
            for (var quad : part.getQuads(direction == 6 ? null : Direction.values()[direction])) {
                if (quads.size() >= MAX_QUADS) return false;
                var layer = quad.materialInfo().layer();
                if (layer != ChunkSectionLayer.SOLID && layer != ChunkSectionLayer.CUTOUT
                        && layer != ChunkSectionLayer.TRANSLUCENT) return false;
                hasCoverageMaterial |= layer != ChunkSectionLayer.SOLID;
                quads.add(quad);
            }
        }
        if (quads.isEmpty() || !hasCoverageMaterial) return false;
        var occupied = new BitSet(GRID * GRID * GRID);
        if (kind == VoxelShapeKind.FULL) occupied.set(0, GRID * GRID * GRID);
        else {
            if (boxes.isEmpty() || boxes.size() > VoxelShapeClassifier.MAX_BOXES) return false;
            for (var box : boxes) {
                if (box.minX() < 0 || box.minY() < 0 || box.minZ() < 0
                        || box.maxX() > GRID || box.maxY() > GRID || box.maxZ() > GRID
                        || box.minX() >= box.maxX() || box.minY() >= box.maxY() || box.minZ() >= box.maxZ()) return false;
                for (int y = box.minY(); y < box.maxY(); y++) for (int z = box.minZ(); z < box.maxZ(); z++)
                    occupied.set(index(box.minX(), y, z), index(box.maxX() - 1, y, z) + 1);
            }
        }
        BitSet[] coverage = new BitSet[6];
        for (int i = 0; i < coverage.length; i++) coverage[i] = new BitSet((GRID + 1) * GRID * GRID);
        for (var quad : quads) {
            Rectangle rectangle = rectangle(quad);
            if (rectangle == null) return false;
            int axis = axis(quad.direction());
            boolean positive = quad.direction().getAxisDirection() == Direction.AxisDirection.POSITIVE;
            for (int t = rectangle.t0; t < rectangle.t1; t++) for (int s = rectangle.s0; s < rectangle.s1; s++) {
                int inside = rectangle.plane - (positive ? 1 : 0);
                if (!occupied(occupied, axis, inside, s, t)) return false;
                coverage[quad.direction().get3DDataValue()].set(faceIndex(rectangle.plane, s, t));
            }
        }
        for (int cell = occupied.nextSetBit(0); cell >= 0; cell = occupied.nextSetBit(cell + 1)) {
            int x = cell & 15, z = (cell >> 4) & 15, y = cell >> 8;
            for (Direction face : Direction.values()) {
                int nx = x + face.getStepX(), ny = y + face.getStepY(), nz = z + face.getStepZ();
                if (nx >= 0 && nx < GRID && ny >= 0 && ny < GRID && nz >= 0 && nz < GRID
                        && occupied.get(index(nx, ny, nz))) continue;
                int axis = axis(face);
                int coordinate = axis == 0 ? x : axis == 1 ? y : z;
                int plane = coordinate + (face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0);
                int s = axis == 0 ? y : x, t = axis == 2 ? y : z;
                if (!coverage[face.get3DDataValue()].get(faceIndex(plane, s, t))) return false;
            }
        }
        return true;
    }

    static int axis(Direction face) {
        return switch (face.getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; };
    }

    record Rectangle(int plane, int s0, int t0, int s1, int t1) { }

    static Rectangle rectangle(BakedQuad quad) {
        int axis = axis(quad.direction()), plane = -1;
        int[] ss = new int[4], tt = new int[4];
        int s0 = GRID, t0 = GRID, s1 = 0, t1 = 0;
        for (int v = 0; v < 4; v++) {
            var p = quad.position(v);
            float n = p.get(axis) * GRID, s = (axis == 0 ? p.y() : p.x()) * GRID;
            float t = (axis == 2 ? p.y() : p.z()) * GRID;
            if (!Float.isFinite(n) || !Float.isFinite(s) || !Float.isFinite(t)
                    || n != Math.rint(n) || s != Math.rint(s) || t != Math.rint(t)
                    || n < 0 || n > GRID || s < 0 || s > GRID || t < 0 || t > GRID) return null;
            if (plane < 0) plane = (int)n;
            if (plane != (int)n) return null;
            ss[v] = (int)s; tt[v] = (int)t;
            s0 = Math.min(s0, ss[v]); s1 = Math.max(s1, ss[v]);
            t0 = Math.min(t0, tt[v]); t1 = Math.max(t1, tt[v]);
        }
        if (s0 >= s1 || t0 >= t1) return null;
        int mask = 0;
        int[] corners = new int[4];
        for (int v = 0; v < 4; v++) {
            if ((ss[v] != s0 && ss[v] != s1) || (tt[v] != t0 && tt[v] != t1)) return null;
            corners[v] = (ss[v] == s1 ? 1 : 0) | (tt[v] == t1 ? 2 : 0);
            mask |= 1 << corners[v];
        }
        if (mask != 15 || (corners[0] ^ corners[2]) != 3 || (corners[1] ^ corners[3]) != 3) return null;
        return new Rectangle(plane, s0, t0, s1, t1);
    }

    private static boolean occupied(BitSet cells, int axis, int n, int s, int t) {
        if (n < 0 || n >= GRID) return false;
        return cells.get(axis == 0 ? index(n, s, t) : axis == 1 ? index(s, n, t) : index(s, t, n));
    }

    private static int index(int x, int y, int z) { return (y * GRID + z) * GRID + x; }
    private static int faceIndex(int plane, int s, int t) { return (plane * GRID + t) * GRID + s; }
}
