package dev.icehunter.fornax.pass.shadow;

import dev.icehunter.fornax.mixin.sodium.RenderSectionManagerAccessor;
import dev.icehunter.fornax.pipeline.FornaxRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/**
 * Builds the sun-shadow pass's caster list directly from every loaded {@code RenderRegion} whose
 * world-space AABB intersects the sun's shadow ortho volume this frame, independent of {@code
 * RenderSectionManager}'s own player-frustum-culled {@code SortedRenderLists} -- this is what keeps
 * shadows from silently disappearing for casters outside the player's current view AND for casters
 * inside the light's own (possibly tilted) frustum but outside a world-XZ radius around the camera.
 * See {@code .superpowers/sdd/shadow-casterlist-research.md} for the caster-list mechanism research
 * (Q7's direct-render() recommendation, still followed exactly here).
 *
 * <p><b>Why not reuse Sodium's own render lists:</b> {@code RenderSectionManager.getRenderLists()}
 * is rebuilt once per frame from an octree traversal seeded by the PLAYER camera's frustum --
 * sections outside that frustum are never visited, so a section behind the player (relative to the
 * player, not the light) never gets a chance to cast a shadow, even though the light doesn't care
 * about the player's facing. This class replaces that traversal with a flat, un-occluded scan
 * against the light's own ortho volume.
 *
 * <p><b>Mechanism:</b> enumerate {@link RenderRegionManager#getLoadedRegions()} (reached via {@link
 * RenderSectionManagerAccessor}, an {@code @Accessor} for {@code RenderSectionManager}'s private
 * {@code regions} field), reject whole regions whose world-space AABB cannot intersect the shadow
 * ortho volume (cheap whole-region test, see {@link #regionMayIntersect}), then for every surviving
 * region's 256 local section slots test the INDIVIDUAL SECTION's own 16-block-cube world AABB
 * against the same ortho volume (see {@link #sectionIntersectsShadowVolume}). Sections that pass get
 * added to a Fornax-owned {@link ChunkRenderList} for that region -- deliberately NOT {@code
 * region.getRenderList()}, which is the main pass's own persistent list; writing shadow-volume
 * sections into that object would leak into the main SOLID/CUTOUT/TRANSLUCENT draws later the same
 * frame and silently defeat their own frustum culling too. {@link ChunkRenderList#add(int)} is safe
 * to call even for never-built/mid-rebuild slots -- it reads {@code region.getSectionFlags(id)}
 * itself and simply omits anything without {@code HAS_BLOCK_GEOMETRY} from the geometry-bearing
 * subset it tracks, so no pre-filtering by build state is needed here.
 *
 * <p><b>The predicate: light-space AABB vs. the shadow ortho box.</b> {@link #aabbIntersectsShadowVolume}
 * transforms a camera-relative world AABB's 8 corners through the frame's {@code lightViewProj}
 * (the SAME camera-relative, texel-snapped matrix {@link ShadowCamera#compute} builds for the actual
 * shadow draw and {@code u_SunViewProj} -- reusing it here means the caster-list test and the
 * rasterizer's own projection can never disagree) and checks whether the resulting NDC bounding box
 * overlaps the unit ortho volume ({@code x,y in [-1,1]}, {@code z in [0,1]} -- {@code
 * zZeroToOne=true}, matching {@code ShadowCameraTest}'s own convention). Because the shadow
 * projection is a pure affine map (orthographic projection composed with a rigid look-at view, no
 * perspective divide -- {@code w} is always exactly 1, verified by the existing {@code
 * ShadowCameraTest}), the extrema of each transformed NDC axis are always attained at one of the
 * AABB's 8 corners; the corner-bounding-box computed here is therefore EXACT for "does the AABB's
 * bounding box in NDC overlap the ortho cube" -- never a false negative. It CAN be a (safe) false
 * positive relative to the true rotated-parallelepiped-vs-cube test (the light view rotates the box
 * unless the sun is at true noon), the same "err inclusive" direction a flat {@code camX,camZ}-vs-
 * {@code radius} world-XZ cylinder test would take -- but that simpler test is correct only at noon
 * and Y-blind by construction, missing occluders inside the true tilted frustum at low sun angles.
 * This predicate has no such blind spot: it tests a section's real position along the light's own
 * tilted axes, not a world-axis
 * proxy, so it is correct at every sun elevation and every camera height -- and at true noon (light
 * direction ~= world -Y) it degenerates to almost exactly the old world-XZ-cylinder-times-full-height
 * test, since the light's local XY then coincides with world XZ and its Z axis with world Y.
 *
 * <p><b>Shadow-acne fix compatibility (radial distortion):</b> {@code
 * lightViewProj} here is always the SAME plain, linear, undistorted matrix {@link
 * ShadowCamera#compute} builds and that {@code u_SunViewProj} carries -- the radial XY warp is
 * applied strictly downstream, in the vertex/fragment shaders that write/read the shadow map (see
 * {@code shadow.vsh}), never folded into this matrix. This predicate's "affine map" exactness
 * argument above therefore holds unchanged: the AABB corner test still measures against the true
 * linear ortho box, never a distorted one, so caster selection cannot regress from that
 * purely shader-side warp.
 *
 * <p><b>Frame-persistence:</b> {@code ChunkRenderList} instances are cached per {@code RenderRegion}
 * in a plain {@link IdentityHashMap} (identity keys -- {@code RenderRegion} has no {@code equals()}/
 * {@code hashCode()} override, so default {@code Object} identity applies) -- reused across frames
 * purely to avoid reallocating each region's three 256-entry backing arrays every frame, not for any
 * change-detection purpose (this class bypasses {@code ChunkRenderList.prepareForRender} and its
 * {@code region.clearAllCachedBatches()} side effect entirely, see the research doc's Q7 for why
 * that path is avoided). A {@code WeakHashMap} is deliberately NOT used here: the cached {@code
 * ChunkRenderList} itself holds a strong {@code final RenderRegion region} field back to its own key
 * (required by {@code DefaultChunkRenderer.render}'s {@code list.getRegion()} read), so a weak key
 * would never actually clear -- the value keeps its own key alive, defeating weak-reference GC
 * entirely and leaking one entry per region-ever-loaded for the process lifetime. Instead, every call
 * to {@link #build} explicitly prunes {@code CACHE} down to exactly this frame's {@code
 * RenderRegionManager#getLoadedRegions()} (a plain {@code retainAll} against a same-frame identity
 * {@link Set} built while enumerating regions below, not against {@code getLoadedRegions()} directly
 * -- that collection is a {@code fastutil} map-values view with an O(n) {@code contains()}, so
 * comparing against it directly inside {@code retainAll} would be quadratic in region count) --
 * mirroring {@code RenderRegionManager}'s own region lifecycle exactly (a region entry disappears
 * from the cache in the same frame it disappears from {@code getLoadedRegions()}, no later).
 *
 * <p>Every cached list is explicitly {@link ChunkRenderList#reset(int)} before repopulation each
 * frame it's touched -- required both to zero its per-frame counters (stale counts from a prior
 * frame would otherwise accumulate past {@code RenderRegion.REGION_SIZE} and trip {@code add(int)}'s
 * "Render list is full" guard) and to record this frame's number, mirroring the per-region
 * persistent-list model {@code RenderRegion.getRenderList()} itself uses.
 *
 * <p><b>Per-frame batch invalidation (closes the frozen-batch bug for out-of-frustum casters):</b>
 * {@code DefaultChunkRenderer.render} only refills a region's cached {@code MultiDrawBatch} when
 * {@code !batch.isFilled} -- otherwise it draws the cached command buffer and never even looks at the
 * {@code ChunkRenderList} passed in. Sodium's own main passes get a fresh batch every frame because
 * {@code ChunkRenderList.prepareForRender} (driven by {@code RenderListProvider.createRenderLists},
 * which only runs for frustum-visited regions) calls {@code region.clearAllCachedBatches()} on
 * camera movement. This class bypasses that machinery entirely (see above), so nothing would ever
 * clear the SHADOW/SHADOW_CUTOUT batches for a region that stays outside the player's frustum --
 * {@code build()} would keep rebuilding a fresh {@code ChunkRenderList} every frame while {@code
 * render()} kept drawing a frozen batch baked from the first frame that region was ever touched
 * (stale section membership AND stale player-relative face culling). {@link #build} therefore
 * explicitly calls {@code region.clearCachedBatchFor(FornaxRenderPasses.SHADOW)}/{@code
 * .clearCachedBatchFor(FornaxRenderPasses.SHADOW_CUTOUT)} for every region it adds to the touched
 * set, every frame, before returning -- symmetric with what {@code prepareForRender} does for the
 * main passes (which also rebuild their batches every frame; this is not extra work relative to the
 * main passes, just the shadow passes finally doing the same thing). This also makes {@code
 * RenderRegionManager}'s upload-time invalidation redundant for these two passes: {@code
 * SodiumWorldRenderer.setupTerrain} always finishes processing this frame's mesh uploads (via {@code
 * processChunkBuilds} -&gt; {@code RenderRegionManager.uploadResults}) before {@code drawChunkLayer}
 * (and therefore this method) ever runs, so by the time this per-frame clear executes, this frame's
 * uploads have already landed -- clearing again here is a strict superset of any narrower upload-time
 * clear, for every region actually drawn this frame.
 *
 * <p>Built ONCE per frame and reused for both the {@code SHADOW} and {@code SHADOW_CUTOUT} draws --
 * the two passes source different geometry storage (via {@code
 * DefaultChunkRendererGeometryStorageMixin}'s redirect) but the same set of nearby sections is
 * spatially relevant to both, so there is no reason to scan regions twice.
 */
public final class ShadowCasterLists {
    /** Block-space footprint of one region along X/Z (see {@code RenderRegion.REGION_WIDTH}/{@code
     * REGION_LENGTH}, both 8 sections = 128 blocks). */
    private static final int REGION_FOOTPRINT_XZ_BLOCKS = RenderRegion.REGION_WIDTH << 4;

    /** Block-space footprint of one region along Y (see {@code RenderRegion.REGION_HEIGHT}, 4
     * sections = 64 blocks -- unlike X/Z, the world spans MANY regions vertically, so this is only
     * one region's own vertical slice, never the world's full build height). */
    private static final int REGION_FOOTPRINT_Y_BLOCKS = RenderRegion.REGION_HEIGHT << 4;

    /** One section, in blocks -- {@code RenderSection}s are always 16^3. */
    private static final float SECTION_SIZE_BLOCKS = 16.0f;

    /** Reused across frames purely to avoid rebuilding each region's backing arrays -- see this
     * class's javadoc. Identity keys, explicitly pruned to the current frame's loaded-region set in
     * every {@link #build} call -- NOT a {@code WeakHashMap} (the cached {@code ChunkRenderList}
     * value holds a strong back-reference to its own key, so weak keys would never actually clear). */
    private static final Map<RenderRegion, ChunkRenderList> CACHE = new IdentityHashMap<>();

    private ShadowCasterLists() {
    }

    /**
     * @param renderSectionManager the world renderer's live {@code RenderSectionManager} (from
     *                              {@code SodiumWorldRendererOrchestrationMixin}'s {@code @Shadow}).
     * @param camX/camY/camZ        the player camera's world position this frame -- the SAME
     *                              position {@link ShadowCamera#compute} was called with, since
     *                              {@code lightViewProj} is camera-relative and every AABB tested here
     *                              must be expressed relative to that same origin.
     * @param lightViewProj         this frame's camera-relative, texel-snapped light view-projection
     *                              matrix -- {@code ShadowCamera.LightMatrices.viewProj()} from the
     *                              SAME {@link ShadowCamera#compute} call that produced {@code
     *                              u_SunViewProj}, so the caster-list test and the actual shadow
     *                              rasterization can never disagree about what the light volume is.
     */
    public static ChunkRenderListIterable build(RenderSectionManager renderSectionManager,
                                                 double camX, double camY, double camZ,
                                                 Matrix4f lightViewProj) {
        RenderRegionManager regions = ((RenderSectionManagerAccessor) renderSectionManager).fornax$getRegions();
        int frame = renderSectionManager.getFrame();

        // Built alongside the main loop below (which already visits every loaded region once) so
        // CACHE can be pruned to exactly this frame's loaded-region set afterward -- see class
        // javadoc's "Frame-persistence" for why this (not comparing against getLoadedRegions()
        // directly) keeps eviction linear rather than quadratic in region count.
        Set<RenderRegion> loadedThisFrame = Collections.newSetFromMap(new IdentityHashMap<>());

        List<ChunkRenderList> touched = new ArrayList<>();
        // Reused across every corner of every AABB test this call makes -- Matrix4f.transform(Vector4f)
        // mutates and returns its argument in place, so one scratch instance avoids an allocation per
        // corner in what can be a many-thousands-of-calls-per-frame hot path (see
        // aabbIntersectsShadowVolume's javadoc).
        Vector4f scratch = new Vector4f();
        for (RenderRegion region : regions.getLoadedRegions()) {
            loadedThisFrame.add(region);
            if (!regionMayIntersect(region, lightViewProj, camX, camY, camZ, scratch)) {
                continue;
            }

            ChunkRenderList list = null;
            for (int local = 0; local < RenderRegion.REGION_SIZE; local++) {
                int sectionX = region.getChunkX() + LocalSectionIndex.unpackX(local);
                int sectionY = region.getChunkY() + LocalSectionIndex.unpackY(local);
                int sectionZ = region.getChunkZ() + LocalSectionIndex.unpackZ(local);
                if (!sectionIntersectsShadowVolume(lightViewProj, sectionX, sectionY, sectionZ,
                        camX, camY, camZ, scratch)) {
                    continue;
                }

                if (list == null) {
                    list = CACHE.computeIfAbsent(region, ChunkRenderList::new);
                    list.reset(frame);
                    touched.add(list);
                    // C1 fix: force a fresh fillCommandBuffer every frame for every region this
                    // pass actually draws -- otherwise a region that stays outside the player's
                    // main-camera frustum never gets its SHADOW/SHADOW_CUTOUT MultiDrawBatch
                    // invalidated (see class javadoc's "Per-frame batch invalidation"), so
                    // DefaultChunkRenderer.render keeps drawing a frozen batch (stale membership +
                    // stale player-relative face culling) forever.
                    region.clearCachedBatchFor(FornaxRenderPasses.SHADOW);
                    region.clearCachedBatchFor(FornaxRenderPasses.SHADOW_CUTOUT);
                }
                list.add(local);
            }
        }

        // I1 fix: CACHE is a plain (strong-key) map, so unlike a correctly-functioning WeakHashMap
        // it needs an explicit per-frame eviction pass -- drop every entry whose region is no longer
        // loaded, mirroring RenderRegionManager's own region lifecycle.
        CACHE.keySet().retainAll(loadedThisFrame);

        return new TouchedListsIterable(touched);
    }

    /** Cheap whole-region reject: skip the 256-slot inner scan when the region's world-space AABB
     * (its X/Z footprint x its own 64-block Y slice) cannot intersect the light's shadow volume at
     * all. Safe to reject here whenever this returns false: since every candidate section's AABB is a
     * subset of the region's own AABB, and {@link #aabbIntersectsShadowVolume} computes an EXACT
     * corner-derived NDC bounding box (see class javadoc), the region's NDC bounding box always
     * contains every section's -- so a region-level miss guarantees every section inside it would
     * miss too. */
    private static boolean regionMayIntersect(RenderRegion region, Matrix4f lightViewProj,
                                               double camX, double camY, double camZ, Vector4f scratch) {
        double minX = region.getOriginX() - camX;
        double minY = region.getOriginY() - camY;
        double minZ = region.getOriginZ() - camZ;
        return aabbIntersectsShadowVolume(lightViewProj,
                minX, minY, minZ,
                minX + REGION_FOOTPRINT_XZ_BLOCKS, minY + REGION_FOOTPRINT_Y_BLOCKS, minZ + REGION_FOOTPRINT_XZ_BLOCKS,
                scratch);
    }

    /** True iff the given section's own 16-block-cube world AABB intersects the light's shadow
     * volume. {@code sectionX/Y/Z} are section coordinates (block coordinate = section coordinate
     * {@code << 4}), matching {@code RenderRegion.getChunkX/Y/Z()} + {@code
     * LocalSectionIndex.unpackX/Y/Z()}'s own units. */
    private static boolean sectionIntersectsShadowVolume(Matrix4f lightViewProj,
                                                           int sectionX, int sectionY, int sectionZ,
                                                           double camX, double camY, double camZ,
                                                           Vector4f scratch) {
        double minX = (sectionX << 4) - camX;
        double minY = (sectionY << 4) - camY;
        double minZ = (sectionZ << 4) - camZ;
        return aabbIntersectsShadowVolume(lightViewProj,
                minX, minY, minZ,
                minX + SECTION_SIZE_BLOCKS, minY + SECTION_SIZE_BLOCKS, minZ + SECTION_SIZE_BLOCKS,
                scratch);
    }

    /**
     * The predicate: transforms every one of the given camera-relative world AABB's 8 corners
     * through {@code lightViewProj} and tests whether the resulting NDC bounding box overlaps the
     * unit shadow-ortho volume ({@code x,y in [-1,1]}, {@code z in [0,1]} -- zZeroToOne, matching
     * {@link ShadowCamera#compute}'s own convention). {@code lightViewProj} must already be
     * CAMERA-RELATIVE (as {@link ShadowCamera#compute} always builds it) -- the min/max here are
     * therefore already camera-relative deltas, not absolute world coordinates. Package-private
     * (not private) so {@code ShadowCasterListsTest} can exercise this pure math directly, with no
     * Sodium/GPU dependency at all. {@code scratch} is a caller-owned, reused {@link Vector4f} --
     * {@code Matrix4f.transform(Vector4f)} mutates and returns its argument in place, so reusing one
     * instance across all 8 corners avoids an allocation per corner.
     */
    static boolean aabbIntersectsShadowVolume(Matrix4f lightViewProj,
                                               double minX, double minY, double minZ,
                                               double maxX, double maxY, double maxZ,
                                               Vector4f scratch) {
        float ndcMinX = Float.POSITIVE_INFINITY, ndcMinY = Float.POSITIVE_INFINITY, ndcMinZ = Float.POSITIVE_INFINITY;
        float ndcMaxX = Float.NEGATIVE_INFINITY, ndcMaxY = Float.NEGATIVE_INFINITY, ndcMaxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            float cx = (float) ((i & 1) == 0 ? minX : maxX);
            float cy = (float) ((i & 2) == 0 ? minY : maxY);
            float cz = (float) ((i & 4) == 0 ? minZ : maxZ);
            lightViewProj.transform(scratch.set(cx, cy, cz, 1.0f));
            ndcMinX = Math.min(ndcMinX, scratch.x);
            ndcMaxX = Math.max(ndcMaxX, scratch.x);
            ndcMinY = Math.min(ndcMinY, scratch.y);
            ndcMaxY = Math.max(ndcMaxY, scratch.y);
            ndcMinZ = Math.min(ndcMinZ, scratch.z);
            ndcMaxZ = Math.max(ndcMaxZ, scratch.z);
        }
        // A tiny epsilon guards only against float roundoff exactly on the ortho boundary -- never a
        // whole-block margin like the predicate this replaces used; the corner test above is already
        // exact for the AABB-vs-box question (see class javadoc), so no geometric slack is needed.
        final float eps = 1.0e-4f;
        return ndcMaxX >= -1.0f - eps && ndcMinX <= 1.0f + eps
                && ndcMaxY >= -1.0f - eps && ndcMinY <= 1.0f + eps
                && ndcMaxZ >= 0.0f - eps && ndcMinZ <= 1.0f + eps;
    }

    /**
     * Trivial {@link ChunkRenderListIterable} over a fixed snapshot of touched lists. Region
     * iteration order is cosmetically irrelevant for a depth-only, non-translucent pass (see the
     * research doc's Q4) -- {@code reverse} is honored anyway for correctness, even though {@code
     * FornaxRenderPasses#SHADOW}/{@code SHADOW_CUTOUT} never request it.
     */
    private static final class TouchedListsIterable implements ChunkRenderListIterable {
        private final List<ChunkRenderList> lists;

        private TouchedListsIterable(List<ChunkRenderList> lists) {
            this.lists = lists;
        }

        @Override
        public Iterator<ChunkRenderList> iterator(boolean reverse) {
            if (!reverse) {
                return lists.iterator();
            }
            ListIterator<ChunkRenderList> reversed = lists.listIterator(lists.size());
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return reversed.hasPrevious();
                }

                @Override
                public ChunkRenderList next() {
                    return reversed.previous();
                }
            };
        }
    }
}
