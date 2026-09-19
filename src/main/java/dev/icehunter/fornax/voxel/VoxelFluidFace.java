package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.atlas.BlockAtlasGhostSprite;
import dev.icehunter.fornax.atlas.MaterialSourceIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Face mapping for a block that IS a fluid, taken from the fluid's own sprites.
 *
 * <p>A fluid's block model holds a particle texture and no elements, so the model path finds no
 * quad on any face of it and the block never becomes a light source however brightly it glows.
 * Lava is the case. The game keeps a fluid's real sprites in its fluid model instead, which is
 * what this reads.
 *
 * <p>Solid and cutout layers only, the same rule a model quad answers to in
 * {@link VoxelFaceTexture#mapping}. Water draws translucent, so it never takes this path.
 *
 * <p>The whole cell is claimed, and the whole still sprite with it. A flowing cell sits a ninth of
 * a block below its own ceiling, so its face is overstated by that much; a record is one light
 * with one size either way.
 *
 * <p>This reports a SOURCE, never a shape. {@link VoxelShapeClassifier} still answers EMPTY for a
 * fluid, so a fluid cell keeps occluding nothing: it sets no occupancy bit, seals no face and
 * stops no ray.
 */
public final class VoxelFluidFace {
    private VoxelFluidFace() {
    }

    /** The one sprite a fluid cell's six faces share, and whether its layer alpha-tests. */
    public record Fluid(TextureAtlasSprite sprite, boolean cutout) {
    }

    /** Null for anything that is not a fluid block, and for a fluid whose layer is not drawn
     * solid. A waterlogged block is its own block with its own model, not a fluid block. */
    static @Nullable Fluid resolve(BlockState state) {
        if (!(state.getBlock() instanceof LiquidBlock)) {
            return null;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return null;
        }
        var model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid);
        if (model == null) {
            return null;
        }
        ChunkSectionLayer layer = model.layer();
        if (layer != ChunkSectionLayer.SOLID && layer != ChunkSectionLayer.CUTOUT) {
            return null;
        }
        TextureAtlasSprite sprite = model.stillMaterial().sprite();
        return sprite == null ? null : new Fluid(sprite, layer == ChunkSectionLayer.CUTOUT);
    }

    /** Six identical faces, each the whole sprite rect, in {@link VoxelFaceTexture#mapping}'s own
     * seven-word layout. s runs along u and t along v, which is that layout's identity map. */
    static int[] words(Fluid fluid, int tint) {
        int[] words = new int[VoxelFaceTexture.ENTRY_WORDS];
        TextureAtlasSprite sprite = fluid.sprite();
        float u0 = sprite.getU0(), v0 = sprite.getV0();
        float du = sprite.getU1() - u0, dv = sprite.getV1() - v0;
        if (!Float.isFinite(u0) || !Float.isFinite(v0) || !(du > 0f) || !(dv > 0f)) {
            return words;
        }
        int header = ((1 | (fluid.cutout() ? 2 : 0)) << 24) | (tint & 0x00ffffff)
                | VoxelFaceTexture.OPAQUE_COVERAGE;
        if (sprite instanceof BlockAtlasGhostSprite ghost && ghost.hasOverflowCopy()) {
            header |= ghost.overflowPage() << VoxelFaceTexture.ATLAS_PAGE_SHIFT;
        }
        for (Direction face : Direction.values()) {
            int base = face.get3DDataValue() * VoxelFaceTexture.FACE_WORDS;
            words[base] = header;
            words[base + 1] = Float.floatToRawIntBits(u0);
            words[base + 2] = Float.floatToRawIntBits(v0);
            words[base + 3] = Float.floatToRawIntBits(du);
            words[base + 4] = Float.floatToRawIntBits(0f);
            words[base + 5] = Float.floatToRawIntBits(0f);
            words[base + 6] = Float.floatToRawIntBits(dv);
        }
        return words;
    }

    /** One sprite's evidence, repeated over six faces. A fluid's sprite plays frames, so it
     * carries ANIMATED and reads as unknown; {@link VoxelSourceEvidence} is what decides that a
     * proven cube's own light level still stands. */
    static VoxelFaceTexture.SourceFaces packSources(Fluid fluid, int tint, MaterialSourceIndex index) {
        MaterialSourceIndex.Summary summary = index.lookup(fluid.sprite());
        if (fluid.sprite() instanceof BlockAtlasGhostSprite ghost && !ghost.hasOverflowCopy()) {
            summary = summary.withFlags(MaterialSourceIndex.UNSUPPORTED_ATLAS_PAGE);
        }
        int[] words = words(fluid, tint);
        if ((words[0] >>> 24 & 1) == 0) {
            summary = summary.withFlags(MaterialSourceIndex.UNSUPPORTED_GEOMETRY);
        }
        List<MaterialSourceIndex.Summary> summaries = new ArrayList<>();
        for (int face = 0; face < Direction.values().length; face++) {
            summaries.add(summary);
        }
        boolean knownNonpositive = summary.flags() == MaterialSourceIndex.MISSING_MAP
                || (summary.supported() && summary.texelCount() > 0 && !summary.authoredCandidate());
        return new VoxelFaceTexture.SourceFaces(words, summaries, index.generation(), knownNonpositive);
    }
}
