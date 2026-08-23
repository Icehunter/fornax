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
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Publishes loaded surface-fluid columns for generic shader-pack simulations. */
public final class SurfaceFluidClipmapUpload {
    private static final int GRID = SurfaceFluidClipmapBuffer.GRID;
    private static final int ROW_BYTES = GRID * SurfaceFluidClipmapBuffer.BYTES_PER_COLUMN;
    private static final int MAX_ROWS_PER_FRAME = 16;
    private static final int[] REFERENCE_OFFSETS = {0, -1, 1, -2, 2, -3, 3, -4, 4};

    /** Four words per toroidal column; zero means unknown. */
    private static final int[] MIRROR = new int[
            SurfaceFluidClipmapBuffer.COLUMNS * SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
    private static final int[] ROW = new int[GRID * SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN];
    private static final ByteBuffer SCRATCH = MemoryUtil.memAlloc(MAX_ROWS_PER_FRAME * ROW_BYTES)
            .order(ByteOrder.nativeOrder());

    private static ClientLevel mirrorLevel;
    private static int referenceBlockY;
    private static int referenceFluidKind;
    private static boolean hasReference;
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
        int actorX = (int) Math.floor(actor.x());
        int actorZ = (int) Math.floor(actor.z());
        if (actor.surfaceContact() > 0.5f && actor.fluidKind() != LocalActorFrameState.FLUID_NONE
                && level.hasChunk(SectionPos.blockToSectionCoord(actorX),
                        SectionPos.blockToSectionCoord(actorZ))) {
            Surface reference = findReference(level, actorX, (int) Math.floor(actor.y()), actorZ,
                    actor.fluidKind());
            if (reference != null && (!hasReference || reference.blockY() != referenceBlockY
                    || reference.kind() != referenceFluidKind)) {
                referenceBlockY = reference.blockY();
                referenceFluidKind = reference.kind();
                hasReference = true;
                invalidateField();
            }
        }

        if (!hasReference) {
            publish(List.of());
            return;
        }

        int rows = SurfaceFluidClipmapBuffer.rowsPerFrame(tier);
        int[] verticalOffsets = SurfaceFluidClipmapBuffer.verticalOffsets(tier);
        int baseX = SurfaceFluidClipmapBuffer.windowBase(actorX);
        int baseZ = SurfaceFluidClipmapBuffer.windowBase(actorZ);
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>(rows);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int i = 0; i < rows; i++) {
            int worldZ = baseZ + ((rowCursor + i) & (GRID - 1));
            int toroidalRow = worldZ & (GRID - 1);
            fillRow(level, pos, above, baseX, worldZ, toroidalRow, verticalOffsets, ROW);
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
        hasReference = false;
        referenceBlockY = 0;
        referenceFluidKind = SurfaceFluidClipmapBuffer.FLUID_DRY;
        invalidateField();
    }

    private static void invalidateField() {
        Arrays.fill(MIRROR, 0);
        rowCursor = 0;
        pendingClear = true;
    }

    private static Surface findReference(ClientLevel level, int x, int actorY, int z, int wantedKind) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int offset : REFERENCE_OFFSETS) {
            Surface surface = exposedSurface(level, pos, above, x, actorY + offset, z);
            if (surface != null && surface.kind() == wantedKind) return surface;
        }
        return null;
    }

    private static void fillRow(ClientLevel level, BlockPos.MutableBlockPos pos,
                                BlockPos.MutableBlockPos above, int baseX, int worldZ,
                                int toroidalRow, int[] verticalOffsets, int[] row) {
        int chunkZ = SectionPos.blockToSectionCoord(worldZ);
        for (int i = 0; i < GRID; i++) {
            int worldX = baseX + i;
            int toroidalX = worldX & (GRID - 1);
            int rowWord = toroidalX * SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN;
            int mirrorWord = (toroidalRow * GRID + toroidalX)
                    * SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN;

            if (!level.hasChunk(SectionPos.blockToSectionCoord(worldX), chunkZ)) {
                // Fluid topology changes with chunk lifetime; last-known water is unsafe here.
                // Unknown must fail closed so pressure cannot flow into an unloaded column.
                Arrays.fill(row, rowWord, rowWord + SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN, 0);
                Arrays.fill(MIRROR, mirrorWord,
                        mirrorWord + SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN, 0);
                continue;
            }

            Surface found = null;
            for (int offset : verticalOffsets) {
                found = exposedSurface(level, pos, above, worldX, referenceBlockY + offset, worldZ);
                if (found != null) break;
            }
            if (found == null) {
                SurfaceFluidClipmapBuffer.writeRecord(row, rowWord, worldX, worldZ, 0.0f,
                        SurfaceFluidClipmapBuffer.FLUID_DRY);
            } else {
                SurfaceFluidClipmapBuffer.writeRecord(row, rowWord, worldX, worldZ,
                        found.surfaceY(), found.kind());
            }
            System.arraycopy(row, rowWord, MIRROR, mirrorWord,
                    SurfaceFluidClipmapBuffer.WORDS_PER_COLUMN);
        }
    }

    private static Surface exposedSurface(ClientLevel level, BlockPos.MutableBlockPos pos,
                                           BlockPos.MutableBlockPos above, int x, int y, int z) {
        pos.set(x, y, z);
        FluidState fluid = level.getFluidState(pos);
        int kind = fluidKind(fluid);
        if (kind == SurfaceFluidClipmapBuffer.FLUID_DRY) return null;
        above.set(x, y + 1, z);
        if (fluidKind(level.getFluidState(above)) == kind) return null;
        return new Surface(y, y + fluid.getOwnHeight(), kind);
    }

    private static int fluidKind(FluidState fluid) {
        if (fluid.is(FluidTags.WATER)) return SurfaceFluidClipmapBuffer.FLUID_WATER;
        if (fluid.is(FluidTags.LAVA)) return SurfaceFluidClipmapBuffer.FLUID_LAVA;
        return SurfaceFluidClipmapBuffer.FLUID_DRY;
    }

    private record Surface(int blockY, float surfaceY, int kind) {}
}
