package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.EngineBufferUploadQueue;
import dev.icehunter.fornax.pack.graph.SurfaceFluidClipmapBuffer;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pipeline.LocalActorFrameState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Publishes loaded surface-fluid columns for generic shader-pack simulations.
 *
 * <p>Every column is searched from its OWN motion-blocking top downward, so elevation is a
 * per-column property and unrelated bodies -- a river above a lake, a canal beside a pond, a
 * waterfall's plunge pool -- all resolve in the same window. A single shared reference level
 * instead would report every column beyond a tier's vertical reach of it as dry, leaving consumers
 * silently dead on all but one body.
 */
public final class SurfaceFluidClipmapUpload {
    private static final int GRID = SurfaceFluidClipmapBuffer.GRID;
    private static final int ROW_BYTES = GRID * SurfaceFluidClipmapBuffer.BYTES_PER_COLUMN;
    private static final int MAX_ROWS_PER_FRAME = 16;

    private static final int[] ROW = new int[GRID * SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
    private static final ByteBuffer SCRATCH = MemoryUtil.memAlloc(MAX_ROWS_PER_FRAME * ROW_BYTES)
            .order(ByteOrder.nativeOrder());

    private static ClientLevel mirrorLevel;
    private static boolean pendingClear;
    private static int rowCursor;

    private SurfaceFluidClipmapUpload() {}

    /** Advances the tier's bounded row sweep before this frame's consuming compute passes. */
    public static void onFrame(TargetRegistry registry, int tier) {
        if (registry == null || registry.getBuffer(SurfaceFluidClipmapBuffer.TARGET) == null) return;
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            resetWorld(null);
            publish(List.of());
            return;
        }
        if (mirrorLevel != level) resetWorld(level);
        if (tier == SurfaceFluidClipmapBuffer.TIER_OFF) return;

        LocalActorFrameState.Snapshot actor = LocalActorFrameState.current();
        int rows = SurfaceFluidClipmapBuffer.rowsPerFrame(tier);
        int searchDepth = SurfaceFluidClipmapBuffer.searchDepth(tier);
        int baseX = SurfaceFluidClipmapBuffer.windowBase((int) Math.floor(actor.x()));
        int baseZ = SurfaceFluidClipmapBuffer.windowBase((int) Math.floor(actor.z()));
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>(rows);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int i = 0; i < rows; i++) {
            int worldZ = baseZ + ((rowCursor + i) & (GRID - 1));
            int toroidalRow = worldZ & (GRID - 1);
            fillRow(level, pos, above, baseX, worldZ, searchDepth, ROW);
            int scratchOffset = i * ROW_BYTES;
            for (int word = 0; word < ROW.length; word++) {
                SCRATCH.putInt(scratchOffset + word * Integer.BYTES, ROW[word]);
            }
            ByteBuffer bytes = SCRATCH.asReadOnlyBuffer();
            bytes.position(scratchOffset).limit(scratchOffset + ROW_BYTES);
            ranges.add(new EngineBufferUploadQueue.Range((long) toroidalRow * ROW_BYTES,
                    bytes.slice().order(ByteOrder.nativeOrder())));
        }
        rowCursor = (rowCursor + rows) & (GRID - 1);
        publish(ranges);
    }

    private static void publish(List<EngineBufferUploadQueue.Range> ranges) {
        if (!pendingClear && ranges.isEmpty()) return;
        EngineBufferUploadQueue.publish(SurfaceFluidClipmapBuffer.TARGET, pendingClear, ranges);
        pendingClear = false;
    }

    private static void resetWorld(ClientLevel level) {
        mirrorLevel = level;
        rowCursor = 0;
        pendingClear = true;
    }

    private static void fillRow(ClientLevel level, BlockPos.MutableBlockPos pos,
                                BlockPos.MutableBlockPos above, int baseX, int worldZ,
                                int searchDepth, int[] row) {
        int chunkZ = SectionPos.blockToSectionCoord(worldZ);
        for (int i = 0; i < GRID; i++) {
            int worldX = baseX + i;
            int rowWord = (worldX & (GRID - 1)) * SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN;

            if (!level.hasChunk(SectionPos.blockToSectionCoord(worldX), chunkZ)) {
                // Fluid topology changes with chunk lifetime; last-known water is unsafe here.
                // Unknown must fail closed so pressure cannot flow into an unloaded column.
                Arrays.fill(row, rowWord, rowWord + SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN, 0);
                continue;
            }

            // NO_LEAVES, not plain MOTION_BLOCKING: a tree overhanging a lake would otherwise start
            // the search at the canopy, and the water below it would never be reached within the
            // tier's search depth.
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
            // A void column's top sits on the world floor, so an unclamped scan would probe below it.
            int reach = Math.min(searchDepth, top - level.getMinY());
            int fluidY = SurfaceFluidClipmapBuffer.findExposedFluidY(top, reach,
                    y -> fluidKindAt(level, above, worldX, y, worldZ));

            // The heightmap counts fluids, so its top is the WATER's top on an open lake. What a
            // wave can wash over is a solid, and the block at top-1 is motion-blocking by
            // definition -- so whether that one block is fluid decides whether this column has a
            // solid top at all.
            boolean fluidOnTop = reach > 0
                    && fluidKindAt(level, above, worldX, top - 1, worldZ)
                            != SurfaceFluidClipmapBuffer.FLUID_DRY;
            float solidTop = fluidOnTop ? SurfaceFluidClipmapBuffer.NO_SOLID_TOP : (float) top;

            if (fluidY == SurfaceFluidClipmapBuffer.NO_SURFACE) {
                // The heightmap value IS the top plane of the highest motion-blocking block: it
                // names the first free Y above it. A partial block -- slab, path, farmland --
                // reports the full block top, so an overtop test against this is conservative by up
                // to one block on those.
                SurfaceFluidClipmapBuffer.writeRecord(row, rowWord, worldX, worldZ, (float) top,
                        solidTop, SurfaceFluidClipmapBuffer.FLUID_DRY, 0.0, 0.0);
            } else {
                pos.set(worldX, fluidY, worldZ);
                FluidState fluid = level.getFluidState(pos);
                // Vanilla's own flow vector, the one it uses to push entities and orient the
                // flowing texture, rather than a gradient re-derived from neighbour levels. Zero
                // for a still source.
                Vec3 flow = fluid.getFlow(level, pos);
                SurfaceFluidClipmapBuffer.writeRecord(row, rowWord, worldX, worldZ,
                        fluidY + fluid.getOwnHeight(), solidTop, fluidKind(fluid),
                        flow.x, flow.z);
            }
        }
    }

    private static int fluidKindAt(ClientLevel level, BlockPos.MutableBlockPos scratch,
                                   int x, int y, int z) {
        scratch.set(x, y, z);
        return fluidKind(level.getFluidState(scratch));
    }

    private static int fluidKind(FluidState fluid) {
        if (fluid.is(FluidTags.WATER)) return SurfaceFluidClipmapBuffer.FLUID_WATER;
        if (fluid.is(FluidTags.LAVA)) return SurfaceFluidClipmapBuffer.FLUID_LAVA;
        return SurfaceFluidClipmapBuffer.FLUID_DRY;
    }
}
