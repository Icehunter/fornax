package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import dev.icehunter.fornax.pack.material.AtlasTexelSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Resolves a block's REAL per-face color by walking its actual baked model quads and averaging real
 * atlas texel data -- not a single representative sprite, and not a shader-side sample. This is the
 * expensive part of voxel harvesting, and per the harvest algorithm's design, only ever called for
 * faces {@link FaceExposure#isExposed} has already said are worth resolving.
 */
public final class FaceColorResolver {
    private static final long HARVEST_SEED = 0L; // fixed seed: harvesting must be deterministic
                                                   // frame-to-frame for the same block, not re-rolled

    /** One quad's real average color per texel, with no tint, plus whether it takes the layer-0
     * biome tint. This is the full cost {@link #averageQuadColor} pays, cached so re-harvesting the
     * same block state never walks its sprite's texels again. Tint changes by harvest spot (biome),
     * so it is added after the cache lookup, not stored in the cached value. */
    private record QuadColor(int color, boolean tinted) { }

    /** Per-state, per-face cached quad colors. Cleared on block-atlas retirement (reload or close) by
     * {@link VoxelHarvestLifecycle#onBlockAtlasRetired}, since a cached color is only valid for the
     * exact atlas texels it was sampled from. */
    private static final ConcurrentHashMap<BlockState, Map<Direction, List<QuadColor>>> COLOR_CACHE =
            new ConcurrentHashMap<>();

    private FaceColorResolver() {
    }

    static void clearCache() {
        COLOR_CACHE.clear();
    }

    /** Returns a packed {@code 0xAARRGGBB} average over every quad this block's real model bakes for
     * {@code face}, or {@code 0} if it has none (a face with no quads at all -- e.g. a model that
     * genuinely draws nothing on that side). */
    public static int resolve(BlockState state, Direction face) {
        return resolve(state, face, -1);
    }

    /** Layer-zero biome tint is applied only to quads that request that layer, before averaging. */
    public static int resolve(BlockState state, Direction face, int tint) {
        List<QuadColor> quads = COLOR_CACHE
                .computeIfAbsent(state, FaceColorResolver::computeQuadColors)
                .getOrDefault(face, List.of());
        return combine(quads, tint);
    }

    /** Walks {@code state}'s real baked model once for all six faces, instead of once per face,
     * which is what six separate {@link #resolve} calls from {@link SectionHarvester#buildEntry}
     * would cost. Reads every quad's real atlas texels, the work {@code averageQuadColor} does,
     * paid once per block state for as long as the atlas lasts. */
    private static Map<Direction, List<QuadColor>> computeQuadColors(BlockState state) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(HARVEST_SEED), parts);

        Map<Direction, List<QuadColor>> byFace = new EnumMap<>(Direction.class);
        for (Direction face : Direction.values()) {
            List<QuadColor> quads = new ArrayList<>();
            for (BlockStateModelPart part : parts) {
                for (BakedQuad quad : part.getQuads(face)) {
                    quads.add(new QuadColor(averageQuadColor(quad), quad.materialInfo().tintIndex() == 0));
                }
                for (BakedQuad quad : part.getQuads(null)) {
                    if (quad.direction() == face) {
                        quads.add(new QuadColor(averageQuadColor(quad), quad.materialInfo().tintIndex() == 0));
                    }
                }
            }
            byFace.put(face, quads);
        }
        return byFace;
    }

    /** Combines cached, untinted quad colors into the same weighted average {@link #resolve} used
     * to return directly. This is the cheap half of the math, per quad rather than per texel, and
     * still runs every call since {@code tint} changes by harvest spot (biome). */
    private static int combine(List<QuadColor> quads, int tint) {
        long sumA = 0, weightedSumR = 0, weightedSumG = 0, weightedSumB = 0;
        int quadCount = 0;
        for (QuadColor quad : quads) {
            int avg = quad.color();
            if (tint != -1 && quad.tinted()) {
                avg = (avg & 0xFF000000)
                        | ((((avg >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 255) << 16)
                        | ((((avg >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 255) << 8)
                        | ((avg & 0xFF) * (tint & 0xFF) / 255);
            }
            int a = (avg >>> 24) & 0xFF;
            weightedSumR += (long) ((avg >> 16) & 0xFF) * a;
            weightedSumG += (long) ((avg >> 8) & 0xFF) * a;
            weightedSumB += (long) (avg & 0xFF) * a;
            sumA += a;
            quadCount++;
        }
        if (quadCount == 0 || sumA == 0) return 0;
        return ((int) (sumA / quadCount) << 24) | ((int) (weightedSumR / sumA) << 16)
                | ((int) (weightedSumG / sumA) << 8) | (int) (weightedSumB / sumA);
    }

    // Leaves and inside faces are unculled, so walk both lists and sort the unculled ones into the
    // six faces by their baked normal.
    static int resolve(List<BlockStateModelPart> parts, Direction face, ToIntFunction<BakedQuad> color) {
        return resolve(parts, face, -1, color);
    }

    static int resolve(List<BlockStateModelPart> parts, Direction face, int tint,
                       ToIntFunction<BakedQuad> color) {
        long sumA = 0, weightedSumR = 0, weightedSumG = 0, weightedSumB = 0;
        int quadCount = 0;
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = new ArrayList<>(part.getQuads(face));
            for (BakedQuad quad : part.getQuads(null)) {
                if (quad.direction() == face) quads.add(quad);
            }
            for (BakedQuad quad : quads) {
                int avg = color.applyAsInt(quad);
                // The harvest carries layer 0 only. Other layers must not borrow that tint.
                if (tint != -1 && quad.materialInfo().tintIndex() == 0) {
                    avg = (avg & 0xFF000000)
                            | ((((avg >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 255) << 16)
                            | ((((avg >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 255) << 8)
                            | ((avg & 0xFF) * (tint & 0xFF) / 255);
                }
                int a = (avg >>> 24) & 0xFF;
                weightedSumR += (long) ((avg >> 16) & 0xFF) * a;
                weightedSumG += (long) ((avg >> 8) & 0xFF) * a;
                weightedSumB += (long) (avg & 0xFF) * a;
                sumA += a;
                quadCount++;
            }
        }
        if (quadCount == 0 || sumA == 0) return 0;
        return ((int) (sumA / quadCount) << 24) | ((int) (weightedSumR / sumA) << 16)
                | ((int) (weightedSumG / sumA) << 8) | (int) (weightedSumB / sumA);
    }

    record Surface(boolean cutout, boolean cross) { }

    static Surface surface(BlockState state) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(HARVEST_SEED), parts);
        return surface(parts);
    }

    /** The baked materials say whether a surface is alpha-tested. A cross is accepted only for the
     * two upright diagonal rectangles the palette can hold, never a loose sheet of leaf quads. */
    static Surface surface(List<BlockStateModelPart> parts) {
        boolean cutout = false;
        boolean hasCulled = false;
        List<BakedQuad> unculled = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (Direction face : Direction.values()) {
                for (BakedQuad quad : part.getQuads(face)) {
                    hasCulled = true;
                    cutout |= quad.materialInfo().layer() == ChunkSectionLayer.CUTOUT;
                }
            }
            for (BakedQuad quad : part.getQuads(null)) {
                cutout |= quad.materialInfo().layer() == ChunkSectionLayer.CUTOUT;
                unculled.add(quad);
            }
        }
        return new Surface(cutout, !hasCulled && isDiagonalCross(unculled));
    }

    private static boolean isDiagonalCross(List<BakedQuad> quads) {
        if (quads.isEmpty()) return false;
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (BakedQuad quad : quads) {
            if (quad.materialInfo().layer() != ChunkSectionLayer.CUTOUT) return false;
            for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
                var p = quad.position(i);
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
        }
        if (!(maxX > minX && maxY > minY && maxZ > minZ)) return false;
        int planes = 0;
        for (BakedQuad quad : quads) {
            int plane = -1, corners = 0;
            for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
                var p = quad.position(i);
                // The baked coordinates must match exactly. No guessed slack.
                if ((p.x() != minX && p.x() != maxX) || (p.y() != minY && p.y() != maxY)
                        || (p.z() != minZ && p.z() != maxZ)) return false;
                int x = p.x() == maxX ? 1 : 0, y = p.y() == maxY ? 1 : 0;
                int diagonal = x ^ (p.z() == maxZ ? 1 : 0);
                if (plane != -1 && plane != diagonal) return false;
                plane = diagonal;
                corners |= 1 << (x + 2 * y);
            }
            if (corners != 15) return false; // all four corners of a rectangle
            planes |= 1 << plane;
        }
        return planes == 3; // both diagonal planes, allowing reverse-winding duplicates
    }

    /**
     * Cutout/cross milestone: a cross block's real corner geometry and atlas UV rect, harvested from
     * its own baked model's UNCULLED quads ({@code getQuads(null)} -- the direction-less quad list a
     * cross/billboard model's two diagonal planes always live in, since neither plane has a cull
     * face). {@code bbox} is the axis-aligned bounding box (1/16-block resolution, matching {@link
     * VoxelShapeClassifier.PackedBox}) across every unculled quad's real vertex positions; {@code
     * uvRect} is the first quad's sprite atlas rect.
     *
     * <p>DESIGN CHOICE (documented, not an oversight): this captures the real per-block BOUNDING BOX
     * from the model (so a short flower's shorter footprint differs from tall_grass's, generalizing
     * across vanilla AND modded cross blocks that use the standard two-diagonal-quad topology), but
     * the shader reconstructs the two planes as a standard corner-to-corner "X" within that box rather
     * than storing each quad's exact 4 corners -- the palette entry budget (see
     * BrickGridUpload.PALETTE_ENTRY_WORDS) has room for one bounding box (reusing the existing
     * boxes[0] slot) plus a UV rect, not full per-corner quad geometry. This is exact for every
     * vanilla cross-plant model (short_grass, ferns, saplings, flowers, dead_bush all bake the
     * standard diagonal-quad cross), and a reasonable approximation (a real, model-derived box instead
     * of a hardcoded unit-cube guess) for an exotic modded cross model that places its planes
     * non-diagonally within its own bounds.
     */
    public record CrossGeometry(VoxelShapeClassifier.PackedBox bbox, float[] uvRect) {
    }

    /** Returns the real cross-quad bounding box + atlas UV rect for {@code state}'s unculled model
     * quads, or {@code null} if it bakes none (a block tagged {@code cross} in blocks.toml but whose
     * real model has no direction-less quads -- a misconfiguration; the caller falls back to treating
     * it as a normal, non-cross voxel rather than guessing at geometry that doesn't exist). */
    @Nullable
    public static CrossGeometry resolveCrossGeometry(BlockState state) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(HARVEST_SEED), parts);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float[] uvRect = null;
        boolean any = false;
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(null)) {
                any = true;
                for (int i = 0; i < 4; i++) {
                    var pos = quad.position(i);
                    minX = Math.min(minX, pos.x());
                    minY = Math.min(minY, pos.y());
                    minZ = Math.min(minZ, pos.z());
                    maxX = Math.max(maxX, pos.x());
                    maxY = Math.max(maxY, pos.y());
                    maxZ = Math.max(maxZ, pos.z());
                }
                if (uvRect == null) {
                    TextureAtlasSprite sprite = quad.materialInfo().sprite();
                    uvRect = new float[] {sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()};
                }
            }
        }
        if (!any) {
            return null;
        }
        VoxelShapeClassifier.PackedBox bbox = new VoxelShapeClassifier.PackedBox(
                VoxelShapeClassifier.to16ths(minX), VoxelShapeClassifier.to16ths(minY), VoxelShapeClassifier.to16ths(minZ),
                VoxelShapeClassifier.to16ths(maxX), VoxelShapeClassifier.to16ths(maxY), VoxelShapeClassifier.to16ths(maxZ));
        return new CrossGeometry(bbox, uvRect);
    }

    /** Returns the atlas UV rect ({@code {u0, v0, u1, v1}}) of the first cube face (in {@link
     * Direction#values()} order) that bakes at least one quad, or {@code null} if the model bakes no
     * cube-face quads at all. Used for a {@code FULL}-shape cutout block (leaves): unlike {@link
     * #resolve}, which AVERAGES every quad's texel color for lighting/tinting, this needs the real
     * atlas RECT (not a color) so the shadow shader can sample the SAME texture the rasterized terrain
     * pass samples for its own alpha-cutout discard -- one representative face is sufficient since
     * vanilla leaves (and virtually every alpha-cutout cube block) use the same texture on all six
     * faces. */
    @Nullable
    public static float[] resolveCutoutRect(BlockState state) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(HARVEST_SEED), parts);

        // Walks the six culled Direction buckets AND the null (unculled) bucket. The null pass is the fix
        // for leaves (2026-07-20, live-confirmed): every leaf variant is tagged cutout and classifies FULL,
        // but a leaf model's quads are UNCULLED -- they live under getQuads(null), not under any Direction
        // -- so a Direction-only walk found nothing, returned null, and SectionHarvester silently left
        // cutout=false. The voxel then harvested as a plain solid occluder, which made the whole per-texel
        // alpha test and the foliage light-transmission setting dead code for the one block family they
        // exist to serve (0% and 100% transmission rendered identically). The cross-geometry resolver in
        // this same class already had to walk the null bucket for exactly this reason.
        //
        // Direction buckets are checked FIRST so a genuine culled cube face still wins where one exists --
        // this only adds a fallback, it does not change which sprite an already-working block resolves to.
        for (Direction face : Direction.values()) {
            for (BlockStateModelPart part : parts) {
                for (BakedQuad quad : part.getQuads(face)) {
                    TextureAtlasSprite sprite = quad.materialInfo().sprite();
                    return new float[] {sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()};
                }
            }
        }
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(null)) {
                TextureAtlasSprite sprite = quad.materialInfo().sprite();
                return new float[] {sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()};
            }
        }
        return null;
    }

    private static int averageQuadColor(BakedQuad quad) {
        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        var image = ((SpriteContentsAccessor) (Object) sprite.contents()).fornax$originalImage();

        // Sprite-LOCAL UV span derivation shared with FoliageDensityResolver.sampleQuad -- see
        // BakedQuadUv's doc for why this is a dedicated helper rather than a second copy.
        float[] span = BakedQuadUv.localSpan(quad, sprite);
        return AtlasTexelSampler.averageColor(image, span[0], span[1], span[2], span[3]);
    }
}
