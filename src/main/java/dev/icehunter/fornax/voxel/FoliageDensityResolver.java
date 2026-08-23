package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import dev.icehunter.fornax.pack.material.AtlasTexelSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures a block's EXTINCTION COEFFICIENT (sigma, per block of path length) from its real baked
 * model, so the voxel shadow march can treat it as a participating medium rather than alpha-testing a
 * surface that does not exist.
 *
 * <p>WHY THIS EXISTS. A cutout cube's shadow used to be resolved by mapping the ray's entry point to a
 * cube-face UV and alpha-testing the block's texture. That is only meaningful if the block IS a
 * textured cube. Live-verified 2026-07-20: every leaf model in the target pack is 7-24 ZERO-THICKNESS
 * rotated planes spilling nearly a full block outside its own cell. A cube-face parameterisation has no
 * relationship to that geometry, so the test produced structure that was confidently wrong -- shadow
 * where the model has a gap, none where it is dense.
 *
 * <p>THE PHYSICS. Zero-thickness plate elements bake BOTH of a plate's facing sides as separate real
 * quads -- the four edge-on faces per plate are zero-area and rejected by {@link #sampleQuad}, but
 * front and back both survive. So summing every surviving baked quad's area gives a plate's TOTAL
 * SURFACE S = 2*A (A = the plate's one-sided area), not A itself -- and the same holds for a closed
 * solid (e.g. a cuboid): summing all six baked faces also gives S, its full surface area, not some
 * single-sided quantity.
 *
 * <p>Cauchy's theorem: a convex body's mean projected area, averaged over every viewing direction, is
 * S/4. Weighting each baked quad's area by the fraction of its texels that survive the alpha cutout
 * (which sums to a weighted S), and applying S/4 directly, gives the extinction coefficient:
 *
 * <pre>sigma = sum(area_i * opaqueFraction_i) * 0.25 / occupiedVolume</pre>
 *
 * The 0.25 is applied ONCE, directly to the raw sum of baked quad areas -- because that sum already
 * IS S, not A. Do not re-derive this as "one-sided area A times a projection factor of 0.5 (Cauchy's
 * A/2)": that reading is only valid if the sum you started from was A, and it is not -- it is S. This
 * exact confusion is the bug this class shipped with until 2026-07-20: it multiplied the summed baked
 * area by 0.5, i.e. treated S as if it were A and then projected it, computing S*0.5 = S/2 = A -- the
 * full one-sided surface area, not its mean projection. That is exactly 2x the correct S/4. (Verified
 * against acacia_leaves.json: 20 plate elements bake 40 non-degenerate quads summing to S = 50.625
 * block^2, i.e. one-sided A = 25.3125 block^2; the 0.5-factor bug returned sigma proportional to A,
 * the corrected 0.25 factor returns sigma proportional to S/4 = A/2, half as much.)
 *
 * Transmittance along a ray segment of length L through the voxel is then exp(-sigma * L) -- which,
 * unlike the old test, is invariant to which sprite a face uses (so connected textures stop mattering)
 * and to block rotation (so the pack's 4-way random leaf rotation stops mattering).
 *
 * <p>The volume normalisation is not cosmetic. Leaf clouds bleed roughly +-0.7 blocks past their cell,
 * so attributing all their plane area to one voxel overstates optical depth by ~10x (acacia_leaves:
 * ~1.4 with volume forced to 1 vs. the correctly normalised ~0.15) and would render single leaf blocks
 * nearly black.
 *
 * <p>GENERIC BY CONSTRUCTION: this measures whatever model is loaded. It contains no block ids, no
 * pack knowledge, and no tuned constants. Which blocks get this treatment is a pack decision, made in
 * blocks.toml (see MaterialScalars.isCutout / SectionHarvester.buildEntry).
 */
public final class FoliageDensityResolver {
    /** Matches FaceColorResolver's own harvest seed so both resolvers see the same model variant. */
    private static final long HARVEST_SEED = 0L;

    /** The same threshold terrain.fsh's ALPHA_CUTOUT discard uses -- a texel that reads solid on
     * screen must read solid here. */
    private static final float CUTOUT_ALPHA_THRESHOLD = 0.5f;

    /** One quad's contribution: its geometric {@code area} in block^2, the fraction of its texels that
     * survive the alpha cutout, and its block-space bounds (3 elements each). */
    public record QuadSample(float area, float opaqueFraction, float[] min, float[] max) {}

    private FoliageDensityResolver() {}

    /** Extinction coefficient per block of path length for {@code state}'s baked model, or 0 if it
     * bakes no quads (the caller then leaves the block a plain occluder). */
    public static float resolveExtinction(BlockState state) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(HARVEST_SEED), parts);

        List<QuadSample> samples = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            collectQuads(part.getQuads(null), samples);
            for (Direction face : Direction.values()) {
                collectQuads(part.getQuads(face), samples);
            }
        }
        return combineExtinction(samples);
    }

    private static void collectQuads(List<BakedQuad> quads, List<QuadSample> out) {
        for (BakedQuad quad : quads) {
            QuadSample sample = sampleQuad(quad);
            if (sample != null) {
                out.add(sample);
            }
        }
    }

    /** Pure combination step -- the physics, independent of the model API so it is directly testable
     * without a live client. See the class doc for the derivation. */
    static float combineExtinction(List<QuadSample> quads) {
        if (quads.isEmpty()) {
            return 0.0f;
        }
        float weightedArea = 0.0f;
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (QuadSample q : quads) {
            weightedArea += q.area() * q.opaqueFraction();
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], q.min()[i]);
                max[i] = Math.max(max[i], q.max()[i]);
            }
        }
        if (weightedArea <= 0.0f) {
            return 0.0f;
        }
        // Occupied volume floors at one cell per axis: geometry SMALLER than a block must not be
        // concentrated into a denser-than-real medium.
        float volume = 1.0f;
        for (int i = 0; i < 3; i++) {
            volume *= Math.max(max[i] - min[i], 1.0f);
        }
        // weightedArea is the coverage-weighted sum of BAKED quad areas, i.e. total surface S (both
        // sides of every plate -- see the class doc). Cauchy's S/4 applied directly, no further halving.
        return weightedArea * 0.25f / volume;
    }

    /** One quad's area (from its real vertex positions) and opaque coverage (from its own UV span in
     * its sprite), or null if its sprite image is unavailable. */
    private static QuadSample sampleQuad(BakedQuad quad) {
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float[][] p = new float[4][3];
        for (int i = 0; i < 4; i++) {
            var pos = quad.position(i);
            p[i][0] = pos.x();
            p[i][1] = pos.y();
            p[i][2] = pos.z();
            for (int a = 0; a < 3; a++) {
                min[a] = Math.min(min[a], p[i][a]);
                max[a] = Math.max(max[a], p[i][a]);
            }
        }
        // General planar-quad area = 0.5 * |(v2-v0) x (v3-v1)| (half the cross product of the two
        // diagonals). Exact for both parallelograms and general planar quads (e.g. a UV-mapped
        // trapezoid), unlike the parallelogram-only |(v1-v0) x (v3-v0)| shortcut, at identical cost.
        float area = 0.5f * crossMagnitude(sub(p[2], p[0]), sub(p[3], p[1]));
        // NaN vertices must not survive: `area <= 0.0f` is false for NaN, which would let a NaN
        // quad through and poison combineExtinction's sum into NaN.
        if (!(area > 0.0f)) {
            return null;
        }

        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        var image = ((SpriteContentsAccessor) (Object) sprite.contents()).fornax$originalImage();
        if (image == null) {
            return null;
        }
        // Sprite-LOCAL UV span of this quad, shared with FaceColorResolver.averageQuadColor via
        // BakedQuadUv so the two derivations cannot drift apart again.
        float[] span = BakedQuadUv.localSpan(quad, sprite);
        // Some pack models author DEGENERATE uv spans (e.g. "uv": [0,0,16,0], v0 == v1 -- confirmed
        // present in high-resolution packs' leaf models). No special-casing needed: opaqueFraction's
        // floor/ceil+clamp collapses a degenerate span to exactly the single texel row or column the
        // quad actually displays, which is the physically correct coverage for that quad.
        float coverage = AtlasTexelSampler.opaqueFraction(
                image, span[0], span[1], span[2], span[3], CUTOUT_ALPHA_THRESHOLD);
        return new QuadSample(area, coverage, min, max);
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float crossMagnitude(float[] a, float[] b) {
        float cx = a[1] * b[2] - a[2] * b[1];
        float cy = a[2] * b[0] - a[0] * b[2];
        float cz = a[0] * b[1] - a[1] * b[0];
        return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }
}
