package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import dev.icehunter.fornax.pack.material.AtlasTexelSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a block's REAL per-face color by walking its actual baked model quads and averaging real
 * atlas texel data -- not a single representative sprite, and not a shader-side sample. This is the
 * expensive part of voxel harvesting, and per the harvest algorithm's design, only ever called for
 * faces {@link FaceExposure#isExposed} has already said are worth resolving.
 */
public final class FaceColorResolver {
    private static final long HARVEST_SEED = 0L; // fixed seed: harvesting must be deterministic
                                                   // frame-to-frame for the same block, not re-rolled

    private FaceColorResolver() {
    }

    /** Returns a packed {@code 0xAARRGGBB} average over every quad this block's real model bakes for
     * {@code face}, or {@code 0} if it has none (a face with no quads at all -- e.g. a model that
     * genuinely draws nothing on that side). */
    public static int resolve(BlockState state, Direction face) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(HARVEST_SEED), parts);

        long sumA = 0, weightedSumR = 0, weightedSumG = 0, weightedSumB = 0;
        int quadCount = 0;
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(face)) {
                int avg = averageQuadColor(quad);
                int a = (avg >>> 24) & 0xFF;
                weightedSumR += (long) ((avg >> 16) & 0xFF) * a;
                weightedSumG += (long) ((avg >> 8) & 0xFF) * a;
                weightedSumB += (long) (avg & 0xFF) * a;
                sumA += a;
                quadCount++;
            }
        }
        if (quadCount == 0 || sumA == 0) {
            return 0;
        }
        int avgA = (int) (sumA / quadCount);
        int avgR = (int) (weightedSumR / sumA);
        int avgG = (int) (weightedSumG / sumA);
        int avgB = (int) (weightedSumB / sumA);
        return (avgA << 24) | (avgR << 16) | (avgG << 8) | avgB;
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
