package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.EngineBufferUploadQueue;
import dev.icehunter.fornax.pack.graph.PrecipCoarseClipmapBuffer;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import dev.icehunter.fornax.pipeline.BiomeProbe;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBiomeTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Uploads a bounded, coarse field of vanilla climate answers for shader packs that sample weather
 * and air away from the camera.
 *
 * <p>This class supplies raw categorical world data only. It deliberately does not smooth, darken,
 * or otherwise interpret precipitation, temperature or downfall; a pack owns those visual decisions
 * after it samples the self-tagging field.
 *
 * <p>Uploads go through {@link EngineBufferUploadQueue}, so the transfer is recorded into the first
 * consuming compute pass's own command buffer, ahead of its dispatch and behind that pass's own
 * frames-in-flight fence. Nothing here submits a queue or waits on a fence: the ordering a consumer
 * needs is intra-command-buffer, which is stronger than a host wait and costs the render thread
 * nothing. A reset therefore counts as complete the moment its clear and refill are queued, since
 * no dispatch can precede them.
 */
public final class PrecipCoarseClipmapUpload {
    /** Eight 128-cell rows make the 16,384-cell field complete after sixteen frames. */
    private static final int ROWS_PER_FRAME = PrecipCoarseClipmapUploadPlan.ROWS_PER_FRAME;
    private static final int GRID = PrecipCoarseClipmapBuffer.GRID;
    private static final int WORDS = PrecipCoarseClipmapBuffer.WORDS_PER_CELL;
    private static final int ROW_WORDS = GRID * WORDS;
    private static final int ROW_BYTES = GRID * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
    private static final int TYPE_NONE = 0;
    private static final int TYPE_RAIN = 1;
    private static final int TYPE_SNOW = 2;

    /** Every slot's words, so a full reset can explicitly write unknown to every one. */
    private static final int[] MIRROR = new int[PrecipCoarseClipmapBuffer.COLUMNS * WORDS];
    private static final int[] CELL = new int[WORDS];

    /** Holds a whole window for a reset; steady frames use its first eight rows. */
    private static final ByteBuffer SCRATCH = MemoryUtil.memAlloc((int) PrecipCoarseClipmapBuffer.BYTE_SIZE)
            .order(ByteOrder.nativeOrder());
    private static final PrecipCoarseClipmapUploadPlan PLAN = new PrecipCoarseClipmapUploadPlan();
    private static final PrecipCoarseClipmapPendingChunks PENDING_CHUNKS = new PrecipCoarseClipmapPendingChunks();

    private PrecipCoarseClipmapUpload() {}

    /** Drops held IDs and waiting uploads when the pack or its biome list changes. Render thread only. */
    public static void reset() {
        PLAN.clear();
        clearMirror();
        PENDING_CHUNKS.clear();
        EngineBufferUploadQueue.discard(PrecipCoarseClipmapBuffer.TARGET);
    }

    /**
     * Records a chunk CHUNK_LOAD just reported, for the next {@link #onFrame} drain.
     *
     * <p>Marshals to the render thread when called from any other. Vanilla and Fabric both fire
     * CHUNK_LOAD from inside client packet handling (a chunk-data packet populating the
     * just-loaded {@code LevelChunk}), which Minecraft's networking layer confines to the render
     * thread the same way vanilla game logic already is: no separate network thread does this
     * work, only a netty thread handing decoded packets off before any handler runs.
     * {@link #onFrame} runs on that same thread. The guard is for a mod calling {@code
     * replaceWithPacketData} (or otherwise firing this event) off that thread: an unguarded {@link
     * PrecipCoarseClipmapPendingChunks#offer} could then run concurrently with {@link #onFrame}'s
     * drain, which is not thread-safe.
     */
    public static void onChunkLoaded(int chunkX, int chunkZ) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(() -> PENDING_CHUNKS.offer(chunkX, chunkZ));
            return;
        }
        PENDING_CHUNKS.offer(chunkX, chunkZ);
    }

    /**
     * Refreshes eight rows during steady state, or fully clears and refills the current field before
     * returning after a level change or discontinuous recenter. The latter ordering prevents a
     * teleport from exposing a tag-period alias to a consumer dispatch in the same frame.
     */
    public static boolean onFrame(TargetRegistry registry) {
        if (registry == null || registry.getBuffer(PrecipCoarseClipmapBuffer.TARGET) == null) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            reset();
            return false;
        }

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var body = client.player.getPosition(partialTick);
        int baseCellX = PrecipCoarseClipmapBuffer.windowBaseCell((int) Math.floor(body.x));
        int baseCellZ = PrecipCoarseClipmapBuffer.windowBaseCell((int) Math.floor(body.z));
        PrecipCoarseClipmapUploadPlan.UploadPlan plan = PLAN.plan(level, baseCellX, baseCellZ);
        if (plan.fullReset()) {
            clearMirror();
            fillWholeWindow(level, baseCellX, baseCellZ);
            // Clear first, then the whole window, in ranges no larger than the inline-update limit.
            // Both land in the consumer's command buffer ahead of its dispatch, which is the reset
            // invariant: no old word can be read under the new window.
            EngineBufferUploadQueue.publish(PrecipCoarseClipmapBuffer.TARGET, true, wholeWindowRanges());
            PLAN.commit(plan, level);
            // The reset just resampled every cell in the new window from its current chunk state,
            // so anything still pending here is either already covered (inside the new window) or
            // irrelevant (outside it); nothing is lost by dropping it.
            PENDING_CHUNKS.clear();
            return true;
        }

        EngineBufferUploadQueue.publish(PrecipCoarseClipmapBuffer.TARGET, false,
                combinedRanges(level, baseCellX, baseCellZ, plan));
        PLAN.commit(plan, level);
        return PLAN.isReadyFor(level, baseCellX, baseCellZ);
    }

    private static void clearMirror() {
        java.util.Arrays.fill(MIRROR, 0);
    }

    private static void fillWholeWindow(ClientLevel level, int baseCellX, int baseCellZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int cellX = baseCellX + x;
                int cellZ = baseCellZ + z;
                int offset = PrecipCoarseClipmapBuffer.wordOffsetForCell(cellX, cellZ);
                sampleCell(level, pos, cellX, cellZ, true, CELL);
                for (int w = 0; w < WORDS; w++) {
                    MIRROR[offset + w] = CELL[w];
                    SCRATCH.putInt((offset + w) * Integer.BYTES, CELL[w]);
                }
            }
        }
    }

    private static List<EngineBufferUploadQueue.Range> wholeWindowRanges() {
        int total = (int) PrecipCoarseClipmapBuffer.BYTE_SIZE;
        int chunk = EngineBufferUploadQueue.MAX_RANGE_BYTES;
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>((total + chunk - 1) / chunk);
        for (int offset = 0; offset < total; offset += chunk) {
            ranges.add(range(offset, Math.min(chunk, total - offset)));
        }
        return ranges;
    }

    /**
     * The window slide's exposed strip and any chunk that just loaded, alongside the cyclic sweep,
     * in one publication. Four disjoint scratch regions so no fill can overwrite another's bytes
     * before its own range is sliced out.
     */
    private static List<EngineBufferUploadQueue.Range> combinedRanges(ClientLevel level, int baseCellX,
                                                                       int baseCellZ,
                                                                       PrecipCoarseClipmapUploadPlan.UploadPlan plan) {
        int exposedRowsScratchBase = ROWS_PER_FRAME * ROW_BYTES;
        int exposedColumnsScratchBase = exposedRowsScratchBase
                + PrecipCoarseClipmapUploadPlan.NORMAL_STEP_CELLS * ROW_BYTES;
        int chunkScratchBase = exposedColumnsScratchBase
                + PrecipCoarseClipmapUploadPlan.NORMAL_STEP_CELLS * GRID * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
        int chunkScratchStride = (16 / PrecipCoarseClipmapBuffer.CELL_STRIDE)
                * (16 / PrecipCoarseClipmapBuffer.CELL_STRIDE) * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>();
        ranges.addAll(fillRowSlots(level, baseCellX, baseCellZ, plan.slotRows(), 0));
        // A window slide exposes cells the cyclic sweep has not reached yet; those cells stay
        // tag-invalid, and read as lit through a consumer's unknown-is-open fallback, until the
        // sweep's own sixteen-frame lap reaches them. Sampling the exposed strip this same frame
        // closes that gap at once (PrecipCoarseClipmapUploadPlan.plan bounds its size).
        ranges.addAll(fillRowSlots(level, baseCellX, baseCellZ, plan.exposedRows(), exposedRowsScratchBase));
        ranges.addAll(fillColumnSlots(level, baseCellX, baseCellZ, plan.exposedColumns(), exposedColumnsScratchBase));
        // A chunk's own data can arrive well after the window already covers its cells, since
        // chunk loading lags behind render distance at travel speed. Those cells sample as
        // unknown, through no fault of the cyclic sweep or the slide refresh, until either heals
        // them, up to sixteen frames. Draining the moment a chunk loads closes that gap the same
        // frame it can.
        int chunkIndex = 0;
        for (PrecipCoarseClipmapPendingChunks.ChunkKey chunk : PENDING_CHUNKS.drain(baseCellX, baseCellZ)) {
            ranges.addAll(fillChunkSquare(level, chunk.chunkX(), chunk.chunkZ(),
                    chunkScratchBase + chunkIndex * chunkScratchStride));
            chunkIndex++;
        }
        return ranges;
    }

    /** One full row (every x) per given z-slot; the cyclic sweep and the slide's exposed rows both
     * reuse this, since a row publishes the same way whichever list names its slot. */
    private static List<EngineBufferUploadQueue.Range> fillRowSlots(ClientLevel level, int baseCellX, int baseCellZ,
                                                                     int[] rowSlots, int scratchBase) {
        if (rowSlots.length == 0) {
            return List.of();
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>(rowSlots.length);
        for (int row = 0; row < rowSlots.length; row++) {
            int slot = rowSlots[row];
            int cellZ = absoluteCellForSlot(baseCellZ, slot);
            int rowScratchBase = scratchBase + row * ROW_BYTES;
            for (int x = 0; x < GRID; x++) {
                int cellX = baseCellX + x;
                sampleCell(level, pos, cellX, cellZ, false, CELL);
                int cellBase = rowScratchBase + (cellX & (GRID - 1)) * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
                for (int w = 0; w < WORDS; w++) {
                    SCRATCH.putInt(cellBase + w * Integer.BYTES, CELL[w]);
                }
            }
            ranges.add(new EngineBufferUploadQueue.Range((long) slot * ROW_BYTES, slice(rowScratchBase, ROW_BYTES)));
        }
        return ranges;
    }

    /**
     * The slide's exposed x-slots, sampled down the whole window height. Adjacent slot-x cells are
     * contiguous words (slot = z*GRID + x), so most rows publish in one range; a row whose exposed
     * strip wraps the grid seam publishes in two.
     */
    private static List<EngineBufferUploadQueue.Range> fillColumnSlots(ClientLevel level, int baseCellX,
                                                                        int baseCellZ, int[] columnSlots,
                                                                        int scratchBase) {
        if (columnSlots.length == 0) {
            return List.of();
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>();
        int rowSpan = columnSlots.length * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
        for (int z = 0; z < GRID; z++) {
            int cellZ = baseCellZ + z;
            int rowScratchBase = scratchBase + z * rowSpan;
            for (int j = 0; j < columnSlots.length; j++) {
                int cellX = absoluteCellForSlot(baseCellX, columnSlots[j]);
                sampleCell(level, pos, cellX, cellZ, false, CELL);
                int cellScratch = rowScratchBase + j * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
                for (int w = 0; w < WORDS; w++) {
                    SCRATCH.putInt(cellScratch + w * Integer.BYTES, CELL[w]);
                }
            }
            for (ColumnRun run : columnRuns(baseCellX, cellZ, columnSlots)) {
                ranges.add(new EngineBufferUploadQueue.Range(run.byteOffset(),
                        slice(rowScratchBase + run.startIndex() * PrecipCoarseClipmapBuffer.BYTES_PER_CELL,
                                run.lengthBytes())));
            }
        }
        return ranges;
    }

    /**
     * One CHUNK_LOAD-triggered refresh: exactly the loaded chunk's own footprint (16 blocks is 4x4
     * cells), healing it the instant the chunk arrives instead of waiting for the cyclic sweep or a
     * window slide to reach it. {@code chunkX}/{@code chunkZ} are chunk coordinates, already
     * confirmed inside the window by {@link PrecipCoarseClipmapPendingChunks#drain}.
     */
    private static List<EngineBufferUploadQueue.Range> fillChunkSquare(ClientLevel level, int chunkX, int chunkZ,
                                                                        int scratchBase) {
        int cellsPerChunk = 16 / PrecipCoarseClipmapBuffer.CELL_STRIDE;
        int firstCellX = chunkX * cellsPerChunk;
        int firstCellZ = chunkZ * cellsPerChunk;
        int[] columnSlots = new int[cellsPerChunk];
        for (int k = 0; k < cellsPerChunk; k++) {
            columnSlots[k] = (firstCellX + k) & (GRID - 1);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>();
        int rowSpan = cellsPerChunk * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
        for (int zi = 0; zi < cellsPerChunk; zi++) {
            int cellZ = firstCellZ + zi;
            int rowScratchBase = scratchBase + zi * rowSpan;
            for (int j = 0; j < cellsPerChunk; j++) {
                int cellX = firstCellX + j;
                sampleCell(level, pos, cellX, cellZ, false, CELL);
                int cellScratch = rowScratchBase + j * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
                for (int w = 0; w < WORDS; w++) {
                    SCRATCH.putInt(cellScratch + w * Integer.BYTES, CELL[w]);
                }
            }
            // firstCellX is itself a valid toroidal base for these four slots (they are its own
            // first four cells), so columnRuns reconstructs cellX correctly the same way it does
            // for the window's own baseCellX.
            for (ColumnRun run : columnRuns(firstCellX, cellZ, columnSlots)) {
                ranges.add(new EngineBufferUploadQueue.Range(run.byteOffset(),
                        slice(rowScratchBase + run.startIndex() * PrecipCoarseClipmapBuffer.BYTES_PER_CELL,
                                run.lengthBytes())));
            }
        }
        return ranges;
    }

    /** One published range: {@code startIndex} into the caller's slot array, the target buffer
     * byte offset, and the run's byte length. */
    record ColumnRun(int startIndex, long byteOffset, int lengthBytes) {}

    /**
     * Splits {@code columnSlots} into maximal ascending runs. 127 then 0 is not a continuation:
     * word 127 and word 0 of the same row sit BYTES_PER_CELL * 127 apart, not adjacent, so a
     * strip that wraps the grid seam publishes as two ranges. Pure, with no world or buffer
     * access, so it is tested directly.
     */
    static List<ColumnRun> columnRuns(int baseCellX, int cellZ, int[] columnSlots) {
        List<ColumnRun> runs = new ArrayList<>();
        int runStart = 0;
        for (int j = 1; j <= columnSlots.length; j++) {
            boolean boundary = j == columnSlots.length || columnSlots[j] != columnSlots[j - 1] + 1;
            if (!boundary) continue;
            int firstCellX = absoluteCellForSlot(baseCellX, columnSlots[runStart]);
            long byteOffset = (long) PrecipCoarseClipmapBuffer.wordOffsetForCell(firstCellX, cellZ) * Integer.BYTES;
            int lengthBytes = (j - runStart) * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
            runs.add(new ColumnRun(runStart, byteOffset, lengthBytes));
            runStart = j;
        }
        return runs;
    }

    /** The absolute cell, within the current window, whose toroidal slot on this axis is {@code slot}. */
    private static int absoluteCellForSlot(int base, int slot) {
        return base + ((slot - (base & (GRID - 1))) & (GRID - 1));
    }

    private static EngineBufferUploadQueue.Range range(int offset, int length) {
        return new EngineBufferUploadQueue.Range(offset, slice(offset, length));
    }

    private static ByteBuffer slice(int offset, int length) {
        ByteBuffer bytes = SCRATCH.asReadOnlyBuffer();
        bytes.position(offset).limit(offset + length);
        return bytes.slice().order(ByteOrder.nativeOrder());
    }

    /**
     * Fills {@code out} with the four words for one cell, and mirrors them.
     *
     * <p>Every value is read from the biome the game resolves at the column's own surface height:
     * the precipitation class exactly as vanilla classifies it, the height-adjusted temperature that
     * classification thresholded, the biome's nominal temperature and downfall, and the category
     * tags the biome carries. The temperature and downfall reads reach private members through the
     * access widener; see {@code fornax.accesswidener}.
     */
    private static void sampleCell(ClientLevel level, BlockPos.MutableBlockPos pos, int cellX, int cellZ,
                                   boolean resetting, int[] out) {
        int offset = PrecipCoarseClipmapBuffer.wordOffsetForCell(cellX, cellZ);
        int worldX = PrecipCoarseClipmapBuffer.representativeBlock(cellX);
        int worldZ = PrecipCoarseClipmapBuffer.representativeBlock(cellZ);
        if (!level.hasChunk(SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ))) {
            // A reset must make unknown explicit. During a normal sweep, retain only a record that
            // already describes this same cell; any other slot occupant is unknown, never dry.
            boolean retain = !resetting
                    && PrecipCoarseClipmapBuffer.describesCell(MIRROR[offset], cellX, cellZ);
            for (int w = 0; w < WORDS; w++) {
                out[w] = retain ? MIRROR[offset + w] : 0;
            }
            return;
        }
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ);
        pos.set(worldX, surfaceY, worldZ);
        int seaLevel = level.getSeaLevel();
        Holder<Biome> holder = level.getBiome(pos);
        Biome biome = holder.value();

        out[PrecipCoarseClipmapBuffer.WORD_PRECIPITATION] = PrecipCoarseClipmapBuffer.encodeCell(cellX, cellZ,
                typeOf(biome.getPrecipitationAt(pos, seaLevel)));
        out[PrecipCoarseClipmapBuffer.WORD_CLIMATE] = PrecipCoarseClipmapBuffer.encodeClimate(
                biome.getTemperature(pos, seaLevel), biome.climateSettings.downfall(), tagsOf(holder));
        out[PrecipCoarseClipmapBuffer.WORD_BASE] = PrecipCoarseClipmapBuffer.encodeBase(biome.getBaseTemperature(), surfaceY);
        out[PrecipCoarseClipmapBuffer.WORD_BIOME_ID] = BiomeProbe.id(holder);
        System.arraycopy(out, 0, MIRROR, offset, WORDS);
    }

    private static int tagsOf(Holder<Biome> biome) {
        int tags = 0;
        if (biome.is(ConventionalBiomeTags.IS_HOT)) tags |= PrecipCoarseClipmapBuffer.TAG_HOT;
        if (biome.is(ConventionalBiomeTags.IS_COLD)) tags |= PrecipCoarseClipmapBuffer.TAG_COLD;
        if (biome.is(ConventionalBiomeTags.IS_WET)) tags |= PrecipCoarseClipmapBuffer.TAG_WET;
        if (biome.is(ConventionalBiomeTags.IS_DRY)) tags |= PrecipCoarseClipmapBuffer.TAG_DRY;
        if (biome.is(BiomeTags.IS_OCEAN)) tags |= PrecipCoarseClipmapBuffer.TAG_OCEAN;
        if (biome.is(BiomeTags.IS_JUNGLE)) tags |= PrecipCoarseClipmapBuffer.TAG_JUNGLE;
        if (biome.is(BiomeTags.IS_BADLANDS)) tags |= PrecipCoarseClipmapBuffer.TAG_BADLANDS;
        if (biome.is(BiomeTags.IS_MOUNTAIN)) tags |= PrecipCoarseClipmapBuffer.TAG_MOUNTAIN;
        return tags;
    }

    private static int typeOf(Biome.Precipitation precipitation) {
        return switch (precipitation) {
            case NONE -> TYPE_NONE;
            case RAIN -> TYPE_RAIN;
            case SNOW -> TYPE_SNOW;
        };
    }
}
