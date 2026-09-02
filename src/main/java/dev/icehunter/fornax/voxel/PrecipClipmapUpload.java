package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.graph.EngineBufferUploadQueue;
import dev.icehunter.fornax.pack.graph.PrecipClipmapBuffer;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fills {@link PrecipClipmapBuffer}'s field from vanilla's own per-column precipitation answer, a few
 * rows per frame.
 *
 * <p>See {@link PrecipClipmapBuffer} for what the field is, how it is addressed, and why the engine
 * has to own it. This class is only the harvest and the upload.
 *
 * <h2>Why the answer comes from {@code ClientLevel.getPrecipitationAt} and nothing else</h2>
 *
 * It is the same call the chunk-mesh precipitation lane already uses ({@code
 * BlockRendererMaterialIdMixin}) and the same one vanilla's own weather renderer consults, so the
 * pack's ground weather and vanilla's sky weather cannot disagree about where the boundary is. Its
 * body, javap-verified against {@code minecraft-merged.jar}:
 *
 * <pre>
 *   chunkSource.hasChunk(blockToSectionCoord(x), blockToSectionCoord(z)) ? ... : Precipitation.NONE
 *   getBiome(pos).value().getPrecipitationAt(pos, getSeaLevel())
 * </pre>
 *
 * <p>THE UNLOADED-CHUNK GUARD IS THE REASON THIS CLASS DOES ITS OWN {@code hasChunk} FIRST. Vanilla
 * folds "no chunk" into {@code NONE}, which is indistinguishable from a desert -- and a desert melts
 * snow. Writing that would make an unloaded column actively destroy accumulated state instead of
 * merely failing to add to it. So an unloaded column is not written at all: the element keeps its
 * last known value if it had one, and otherwise keeps a foreign tag and reports itself unknown to
 * every reader.
 *
 * <h2>Why the surface Y is looked up rather than assumed</h2>
 *
 * {@code Biome.getPrecipitationAt(pos, seaLevel)} is height-dependent, and not marginally:
 * {@code getHeightAdjustedTemperature} (javap-verified) subtracts a Perlin-modulated altitude term
 * for every block above {@code seaLevel + 17}. That is exactly what makes a mountain snow while the
 * valley below it rains, which is the most visible case this whole field exists to get right. The
 * heightmap used is {@code MOTION_BLOCKING}, matching {@code ClientLevel.animateTick}'s own choice
 * for where precipitation lands -- this is Fornax's first {@link Heightmap} use.
 *
 * <h2>Amortisation</h2>
 *
 * {@link #ROWS_PER_FRAME} world rows per frame, cursor-swept, so the whole 16384-column field
 * refreshes every {@code GRID / ROWS_PER_FRAME} frames. Biomes do not change, so the sweep is not
 * chasing a moving quantity -- it is only chasing the player, and the tag means a column the sweep
 * has not reached yet reports "unknown" rather than reporting a stale neighbour. That is the same
 * bargain {@code VoxelWindow.recenterAndResync} makes with its shell, without needing the executor:
 * one row is 128 biome queries against already-resident chunk data, not a section harvest.
 *
 * <h2>Upload</h2>
 *
 * Rows ride {@link EngineBufferUploadQueue}, recorded into the first consuming compute pass's
 * command buffer ahead of its dispatch: no submit, no fence. A level change or a whole-window jump
 * queues a clear ahead of that frame's rows. The tag identifies a column, not a world, and the same
 * X/Z exists in every dimension; clearing the mirror alone leaves the old world's elements on the
 * GPU for a full sweep.
 */
public final class PrecipClipmapUpload {
    /** One row is 128 columns; at 8 per frame the field refreshes every 16 frames. */
    private static final int ROWS_PER_FRAME = 8;
    private static final int GRID = PrecipClipmapBuffer.GRID;
    private static final int ROW_BYTES = GRID * Integer.BYTES;

    /**
     * What was last uploaded, so an unloaded column can keep its last known answer instead of being
     * overwritten with a value that means "desert" to every reader. 64 KiB, allocated once.
     *
     * <p>Cleared, with the GPU buffer, when the level instance changes or the window jumps by a
     * whole grid: the tag identifies a column, not a world, and a whole-window jump is the only
     * way a tag-period alias can enter the window.
     */
    private static final int[] MIRROR = new int[PrecipClipmapBuffer.COLUMNS];

    /**
     * Allocated once; runs every frame. Render-thread only ({@code GraphRunner.prepare} is the
     * sole caller). The queue records from read-only slices, drained before the next frame refills.
     */
    private static final ByteBuffer SCRATCH = MemoryUtil.memAlloc(ROWS_PER_FRAME * ROW_BYTES)
            .order(ByteOrder.nativeOrder());
    private static final int[] ROW = new int[GRID];

    private static ClientLevel mirrorLevel;
    private static int lastBaseX;
    private static int lastBaseZ;
    private static int rowCursor;
    private static boolean pendingClear;

    private PrecipClipmapUpload() {}

    /**
     * Advances the sweep by {@link #ROWS_PER_FRAME} rows and uploads them. No-ops when there is no
     * level, no player, or no allocated buffer -- {@code GraphRunner.prepare} allocates before
     * calling, so a null buffer here means a torn-down registry rather than an ordering mistake.
     */
    public static void onFrame(TargetRegistry registry) {
        if (registry == null || registry.getBuffer(PrecipClipmapBuffer.TARGET) == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            mirrorLevel = null;
            return;
        }

        // The player BODY, matching u_WeatherAnchor exactly (GlobalUniformsWriteMixin's own tail):
        // Entity.getPosition(partialTick) carries no head bob, no walk sway and no view roll. Snapping
        // to ANCHOR_SNAP makes the sub-block difference between this and any consumer's own read of
        // the same lane irrelevant, but taking the same lane costs nothing and removes the question.
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var body = client.player.getPosition(partialTick);
        int baseX = PrecipClipmapBuffer.windowBase((int) Math.floor(body.x));
        int baseZ = PrecipClipmapBuffer.windowBase((int) Math.floor(body.z));
        if (mirrorLevel != level
                || Math.abs(baseX - lastBaseX) >= GRID || Math.abs(baseZ - lastBaseZ) >= GRID) {
            Arrays.fill(MIRROR, 0);
            mirrorLevel = level;
            rowCursor = 0;
            pendingClear = true;
        }
        lastBaseX = baseX;
        lastBaseZ = baseZ;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<EngineBufferUploadQueue.Range> ranges = new ArrayList<>(ROWS_PER_FRAME);
        for (int i = 0; i < ROWS_PER_FRAME; i++) {
            int worldZ = baseZ + ((rowCursor + i) & (GRID - 1));
            int toroidalRow = worldZ & (GRID - 1);
            fillRow(level, pos, baseX, worldZ, toroidalRow, ROW);
            int scratchOffset = i * ROW_BYTES;
            for (int x = 0; x < GRID; x++) {
                SCRATCH.putInt(scratchOffset + x * Integer.BYTES, ROW[x]);
            }
            ByteBuffer bytes = SCRATCH.asReadOnlyBuffer();
            bytes.position(scratchOffset).limit(scratchOffset + ROW_BYTES);
            ranges.add(new EngineBufferUploadQueue.Range((long) toroidalRow * ROW_BYTES,
                    bytes.slice().order(ByteOrder.nativeOrder())));
        }
        rowCursor = (rowCursor + ROWS_PER_FRAME) & (GRID - 1);
        // A pending clear shares this frame's entry, ahead of the rows.
        EngineBufferUploadQueue.publish(PrecipClipmapBuffer.TARGET, pendingClear, ranges);
        pendingClear = false;
    }

    /**
     * One world row's 128 columns, written into {@code row} indexed by TOROIDAL x -- which is not
     * world order. World x runs contiguously across the window, but its toroidal index wraps
     * somewhere inside that run, and writing in world order would upload a rotated row.
     */
    private static void fillRow(ClientLevel level, BlockPos.MutableBlockPos pos,
                                int baseX, int worldZ, int toroidalRow, int[] row) {
        int chunkZ = SectionPos.blockToSectionCoord(worldZ);
        int rowBase = toroidalRow * GRID;
        for (int i = 0; i < GRID; i++) {
            int worldX = baseX + i;
            int toroidalX = worldX & (GRID - 1);
            int slot = rowBase + toroidalX;

            if (!level.hasChunk(SectionPos.blockToSectionCoord(worldX), chunkZ)) {
                // Keep the last known answer for THIS column; anything else is a lie. If the mirror
                // holds a different column's element the tag already says so, and every reader is
                // required to treat a tag mismatch as "do not integrate".
                row[toroidalX] = PrecipClipmapBuffer.describes(MIRROR[slot], worldX, worldZ)
                        ? MIRROR[slot]
                        : 0;
                continue;
            }
            pos.set(worldX, level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ), worldZ);
            int element = PrecipClipmapBuffer.encode(worldX, worldZ, typeOf(level.getPrecipitationAt(pos)));
            row[toroidalX] = element;
            MIRROR[slot] = element;
        }
    }

    /** Vanilla's three-valued enum, in the 0/1/2 encoding the chunk-mesh lane already uses. */
    private static int typeOf(Biome.Precipitation precipitation) {
        return switch (precipitation) {
            case NONE -> PrecipClipmapBuffer.TYPE_NONE;
            case RAIN -> PrecipClipmapBuffer.TYPE_RAIN;
            case SNOW -> PrecipClipmapBuffer.TYPE_SNOW;
        };
    }
}
