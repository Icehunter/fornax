package dev.icehunter.fornax.rt.mesh;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import dev.icehunter.fornax.metalfx.rt.TerrainMeshSelection;
import dev.icehunter.fornax.mixin.sodium.RenderSectionManagerAccessor;
import dev.icehunter.fornax.pass.shadow.ShadowCasterLists;
import dev.icehunter.fornax.pipeline.TerrainMeshRevision;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the renderer's uploaded terrain into a list of {@link CasterSource}s, selected by a
 * camera-relative box test. Backend-neutral: a Metal tier and a Vulkan tier both start from this.
 *
 * <p>Must run on the render thread at the point the shadow pass hands the manager over: metadata
 * pointers and arena handles are only consistent together there. Returns {@code null}, not a
 * partial list, when any section cannot be read safely (a buffer that is not copyable, a local
 * index layout, a range that overflows): a caster missing from the trace is a hole in the shadow,
 * and a whole frame of raster is the cheaper mistake.
 */
public final class TerrainCasterSnapshot {

    /**
     * How far a packed vertex may sit outside its section, in blocks. {@code FornaxChunkVertex}
     * encodes positions over [-8, 24), so a 16-block section can hold model geometry that overhangs
     * by up to 8 blocks on every side; a bounds test that ignored that would drop casters whose
     * only geometry is the overhang.
     */
    public static final int OVERHANG_MIN_BLOCKS = -8;
    public static final int OVERHANG_MAX_BLOCKS = 24;

    /**
     * How far, in blocks, from the camera in every direction a ray-query caller's structure must
     * reach: past this the pack's longest ray comes back as a miss anyway.
     */
    public static final float QUERY_REACH_BLOCKS = 96f;

    /** A camera-relative axis-aligned box test. */
    @FunctionalInterface
    public interface RegionFilter {
        boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
    }

    private TerrainCasterSnapshot() {
    }

    /** Why the last snapshot accepted what it did; the tier's activation log line reports it. */
    public static final class Stats {
        public int regionsLoaded, regionsInFilter, regionsNoResources, sectionsInFilter, sectionsNoPointer,
                sectionsNoVertices, sectionsAccepted;

        @Override
        public String toString() {
            return "regions loaded " + regionsLoaded + ", in filter " + regionsInFilter + " (no resources " + regionsNoResources
                    + "); sections in filter " + sectionsInFilter + ", no data pointer " + sectionsNoPointer
                    + ", no vertices " + sectionsNoVertices + ", accepted " + sectionsAccepted;
        }
    }

    private static final Stats LAST = new Stats();

    /** The counts from the most recent {@link #snapshot} call. */
    public static Stats lastStats() {
        return LAST;
    }

    /** The unwarped light volume: what a celestial trace must contain to certify a miss. */
    public static RegionFilter lightVolume(Matrix4f lightViewProj) {
        Vector4f scratch = new Vector4f();
        return (minX, minY, minZ, maxX, maxY, maxZ) ->
                ShadowCasterLists.aabbIntersectsShadowVolume(lightViewProj, minX, minY, minZ, maxX, maxY, maxZ, scratch);
    }

    /** A cube of {@code radius} blocks around the camera: what a buffer-form query can reach. */
    public static RegionFilter cameraWindow(float radius) {
        return (minX, minY, minZ, maxX, maxY, maxZ) ->
                minX <= radius && maxX >= -radius && minY <= radius && maxY >= -radius
                        && minZ <= radius && maxZ >= -radius;
    }

    public static RegionFilter union(RegionFilter a, RegionFilter b) {
        return (minX, minY, minZ, maxX, maxY, maxZ) ->
                a.intersects(minX, minY, minZ, maxX, maxY, maxZ) || b.intersects(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * The camera-relative bounds of one section including its overhang, as
     * {@code [minX, minY, minZ, maxX, maxY, maxZ]}.
     */
    public static double[] sectionBounds(int sx, int sy, int sz, double cameraX, double cameraY, double cameraZ) {
        return new double[] {
                (long) sx * 16 + OVERHANG_MIN_BLOCKS - cameraX,
                (long) sy * 16 + OVERHANG_MIN_BLOCKS - cameraY,
                (long) sz * 16 + OVERHANG_MIN_BLOCKS - cameraZ,
                (long) sx * 16 + OVERHANG_MAX_BLOCKS - cameraX,
                (long) sy * 16 + OVERHANG_MAX_BLOCKS - cameraY,
                (long) sz * 16 + OVERHANG_MAX_BLOCKS - cameraZ,
        };
    }

    /**
     * The camera-relative bounds of one region of {@code width x height x length} sections,
     * including the overhang of its outermost sections.
     */
    public static double[] regionBounds(int chunkX, int chunkY, int chunkZ, int width, int height, int length,
            double cameraX, double cameraY, double cameraZ) {
        return new double[] {
                (long) chunkX * 16 + OVERHANG_MIN_BLOCKS - cameraX,
                (long) chunkY * 16 + OVERHANG_MIN_BLOCKS - cameraY,
                (long) chunkZ * 16 + OVERHANG_MIN_BLOCKS - cameraZ,
                ((long) chunkX + width) * 16 + (OVERHANG_MAX_BLOCKS - 16) - cameraX,
                ((long) chunkY + height) * 16 + (OVERHANG_MAX_BLOCKS - 16) - cameraY,
                ((long) chunkZ + length) * 16 + (OVERHANG_MAX_BLOCKS - 16) - cameraZ,
        };
    }

    private static boolean intersects(RegionFilter filter, double[] b) {
        return filter.intersects(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /**
     * Every accepted SOLID and CUTOUT mesh whose section box passes {@code filter}, or {@code null}
     * when one could not be read safely.
     */
    public static List<CasterSource> snapshot(RenderSectionManager manager, double x, double y, double z,
            RegionFilter filter) {
        List<CasterSource> result = new ArrayList<>();
        Stats stats = LAST;
        stats.regionsLoaded = stats.regionsInFilter = stats.regionsNoResources = stats.sectionsInFilter = 0;
        stats.sectionsNoPointer = stats.sectionsNoVertices = stats.sectionsAccepted = 0;
        var regions = ((RenderSectionManagerAccessor) manager).fornax$getRegions();
        for (RenderRegion region : regions.getLoadedRegions()) {
            stats.regionsLoaded++;
            if (!intersects(filter, regionBounds(region.getChunkX(), region.getChunkY(), region.getChunkZ(),
                    RenderRegion.REGION_WIDTH, RenderRegion.REGION_HEIGHT, RenderRegion.REGION_LENGTH, x, y, z))) {
                continue;
            }
            stats.regionsInFilter++;
            var resources = region.getResources();
            if (resources == null) { stats.regionsNoResources++; continue; }
            GpuBuffer geometry = resources.getGeometryBuffer();
            for (int local = 0; local < RenderRegion.REGION_SIZE; local++) {
                int sx = region.getChunkX() + LocalSectionIndex.unpackX(local);
                int sy = region.getChunkY() + LocalSectionIndex.unpackY(local);
                int sz = region.getChunkZ() + LocalSectionIndex.unpackZ(local);
                if (!intersects(filter, sectionBounds(sx, sy, sz, x, y, z))) continue;
                stats.sectionsInFilter++;
                for (boolean cutout : new boolean[] {false, true}) {
                    var storage = region.getStorage(cutout ? DefaultTerrainRenderPasses.CUTOUT : DefaultTerrainRenderPasses.SOLID);
                    if (storage == null) continue;
                    long pointer = storage.getDataPointer(local);
                    if (pointer == 0) { stats.sectionsNoPointer++; continue; }
                    long[] counts = new long[7]; // ModelQuadFacing.COUNT: six axes + unassigned.
                    long total = 0;
                    for (int i = 0; i < counts.length; i++) {
                        counts[i] = SectionRenderDataUnsafe.getVertexCount(pointer, i);
                        total += counts[i];
                    }
                    if (total == 0) { stats.sectionsNoVertices++; continue; }
                    stats.sectionsAccepted++;
                    if (!(geometry instanceof VulkanGpuBuffer buffer)
                            || (geometry.usage() & GpuBuffer.USAGE_COPY_SRC) == 0
                            || !((Object) storage instanceof TerrainMeshRevision stamps)) return null;
                    var range = TerrainMeshSelection.validRange(SectionRenderDataUnsafe.getBaseVertex(pointer),
                            counts, geometry.size(), SectionRenderDataUnsafe.isLocalIndex(pointer));
                    if (range.isEmpty() || range.get().vertexCount() > Integer.MAX_VALUE) return null;
                    result.add(new CasterSource(new CasterSource.Key(sx, sy, sz, cutout),
                            stamps.fornax$revision(local), buffer, range.get()));
                }
            }
        }
        return result;
    }
}
