package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.EngineBufferUploadQueue;
import dev.icehunter.fornax.pack.graph.PrecipCoarseClipmapBuffer;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
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

    private PrecipCoarseClipmapUpload() {}

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
            PLAN.clear();
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
            return true;
        }

        EngineBufferUploadQueue.publish(PrecipCoarseClipmapBuffer.TARGET, false,
                fillRows(level, baseCellX, baseCellZ, plan));
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

    private static List<EngineBufferUploadQueue.Range> fillRows(ClientLevel level, int baseCellX, int baseCellZ,
                                                                PrecipCoarseClipmapUploadPlan.UploadPlan plan) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>(ROWS_PER_FRAME);
        for (int row = 0; row < ROWS_PER_FRAME; row++) {
            int cellZ = baseCellZ + ((PLAN.rowCursor() + row) & (GRID - 1));
            int scratchBase = row * ROW_BYTES;
            for (int x = 0; x < GRID; x++) {
                int cellX = baseCellX + x;
                sampleCell(level, pos, cellX, cellZ, false, CELL);
                int cellBase = scratchBase + (cellX & (GRID - 1)) * PrecipCoarseClipmapBuffer.BYTES_PER_CELL;
                for (int w = 0; w < WORDS; w++) {
                    SCRATCH.putInt(cellBase + w * Integer.BYTES, CELL[w]);
                }
            }
            ranges.add(new EngineBufferUploadQueue.Range((long) plan.slotRows()[row] * ROW_BYTES,
                    slice(scratchBase, ROW_BYTES)));
        }
        return ranges;
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
        pos.set(worldX, level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ), worldZ);
        int seaLevel = level.getSeaLevel();
        Holder<Biome> holder = level.getBiome(pos);
        Biome biome = holder.value();

        out[PrecipCoarseClipmapBuffer.WORD_PRECIPITATION] = PrecipCoarseClipmapBuffer.encodeCell(cellX, cellZ,
                typeOf(biome.getPrecipitationAt(pos, seaLevel)));
        out[PrecipCoarseClipmapBuffer.WORD_CLIMATE] = PrecipCoarseClipmapBuffer.encodeClimate(
                biome.getTemperature(pos, seaLevel), biome.climateSettings.downfall(), tagsOf(holder));
        out[PrecipCoarseClipmapBuffer.WORD_BASE] = PrecipCoarseClipmapBuffer.encodeBase(biome.getBaseTemperature());
        out[PrecipCoarseClipmapBuffer.WORD_RESERVED] = 0;
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
