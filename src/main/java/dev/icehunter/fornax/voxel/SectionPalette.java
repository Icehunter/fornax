package dev.icehunter.fornax.voxel;

import java.util.List;

/** One section's harvested palette: a small number of distinct {shape, per-face color, emission,
 * transmission} combinations, indexed by an 8-bit value each of the section's 4096 voxels stores. */
public final class SectionPalette {
    /** {@code faceColors} is indexed by {@code net.minecraft.core.Direction.get3DDataValue()} (0-5),
     * each a packed {@code 0xAARRGGBB} -- {@code 0} where the face was never resolved (not exposed,
     * per {@link FaceExposure}, or the shape is {@code EMPTY}/{@code CROSS} and has no meaningful
     * per-face concept yet).
     *
     * <p>{@code lightTransmissive} marks a occupied (FULL-shape) voxel whose real vanilla light
     * dampening is below the fully-sealed threshold despite geometrically filling the voxel -- glass,
     * tinted glass excluded (it dampens fully despite looking like glass), ice, and similar. There is
     * deliberately no SEPARATE tint color field: the block's own per-face albedo (already captured
     * above) IS its transmission tint -- a colored-glass voxel's "light passing through tinted" color
     * is the same real color already resolved for its faces, not a second, redundant value. A later
     * lighting milestone reads this flag to know whether to treat a hit as an opaque stop or a
     * tinted pass-through; THIS milestone's debug view always treats every occupied voxel as a hard
     * stop regardless of this flag, per the spec's explicit scope line.
     *
     * <p>{@code emissionColor} is a SEPARATE, optional authored hue for this entry's cast LIGHT (not
     * its surface appearance) -- packed {@code 0x00RRGGBB} from {@code MaterialScalars.emissiveColor},
     * {@code 0} when the category authored no {@code emissive.color}. Unlike {@code lightTransmissive}
     * above, this genuinely needs its own channel: {@code faceColors} is what the block LOOKS like
     * (e.g. a torch's pale wood handle), while {@code emissionColor} is what the light it CASTS looks
     * like (e.g. a redstone torch's deep red glow) -- the two differ for exactly the categories this
     * field exists to fix. {@code BrickGridUpload.packEmissionWord} packs it into the emission word's
     * bits 8-31; {@code light_inject.comp} reads it and falls back to deriving a tint from {@code
     * faceColors} when it is zero.
     *
     * <p>{@code cutout} (cutout/cross milestone) marks a voxel whose real appearance is alpha-tested
     * rather than fully opaque -- {@code MaterialScalars.isCutout(categoryId)}, propagated verbatim
     * from blocks.toml. Only ever {@code true} when {@code shapeKind} is {@code FULL} (a real cube
     * with an alpha-cutout texture, e.g. leaves) or {@code CROSS} (billboard geometry, e.g. grass);
     * SectionHarvester never sets it for {@code PARTIAL}/{@code EMPTY} shapes (no meaningful UV rect
     * to alpha-test against for those). {@code uvRect} is the block's real atlas sprite rect ({@code
     * {u0, v0, u1, v1}}, ATLAS space) captured by {@link FaceColorResolver}, all-zero ({@link
     * #NO_UV_RECT}) when {@code cutout} is false. For {@code CROSS} entries, {@code boxes} holds
     * exactly one box -- the real harvested bounding box of the block's own cross-quad geometry (see
     * {@link FaceColorResolver#resolveCrossGeometry}), NOT a partial-shape collision box; the shader
     * reconstructs the standard two-diagonal-plane cross topology within that box.
     *
     * <p>{@code extinction} (volumetric foliage milestone, 2026-07-20) is the block's measured
     * extinction coefficient per block of path length ({@link FoliageDensityResolver#resolveExtinction}),
     * {@code 0} for any block that is not treated as a participating medium. A FULL-shape cutout block
     * (leaves) uses this INSTEAD OF a per-texel alpha test: its real geometry is a cloud of rotated
     * zero-thickness planes, not a textured cube, so mapping a ray hit to a cube-face UV and alpha-
     * testing it invents structure the model does not have (shadow where the leaf cloud has a gap,
     * none where it is dense). {@code CROSS} entries deliberately keep {@code uvRect} and the per-texel
     * alpha test instead -- their geometry genuinely IS two diagonal planes (the target pack ships no
     * cross model override, so vanilla's cross geometry, which the per-texel test correctly represents,
     * is exactly what renders), so {@code extinction} stays {@code 0} for them.
     *
     * <p>There used to be a {@code shapeTruncated} flag here (light-leak fix, adversarial review
     * finding S2, 2026-07-20): when {@link VoxelShapeClassifier#classify}'s real vanilla shape
     * decomposed into more boxes than {@link VoxelShapeClassifier#MAX_BOXES}, the excess was dropped
     * and this flag told the shader to distrust the (incomplete) surviving {@code boxes} and fall back
     * to full-cube occlusion. That fixed the light leak but over-shadowed badly -- confirmed live on
     * Diagonal Fences' thin, mostly-open geometry rendering as a solid blob. Removed the same day:
     * {@link VoxelShapeClassifier#classify} now MERGES the excess into one extra union box instead of
     * dropping it, so {@code boxes} is always a complete description of the shape's real footprint and
     * no "distrust this" signal is needed. */
    public record Entry(VoxelShapeKind shapeKind, List<VoxelShapeClassifier.PackedBox> boxes,
                         int[] faceColors, double emissiveStrength, boolean lightTransmissive,
                         int emissionColor, boolean cutout, float[] uvRect, float extinction,
                         int faceSealMask) {
        public Entry {
            if (faceColors.length != 6) {
                throw new IllegalArgumentException("faceColors must have exactly 6 entries, got " + faceColors.length);
            }
            if (uvRect.length != 4) {
                throw new IllegalArgumentException("uvRect must have exactly 4 entries, got " + uvRect.length);
            }
            if ((faceSealMask & ~FaceSealResolver.ALL) != 0) {
                throw new IllegalArgumentException("faceSealMask contains bits outside the six faces: " + faceSealMask);
            }
        }

        public Entry(VoxelShapeKind shapeKind, List<VoxelShapeClassifier.PackedBox> boxes,
                     int[] faceColors, double emissiveStrength, boolean lightTransmissive,
                     int emissionColor, boolean cutout, float[] uvRect, float extinction) {
            this(shapeKind, boxes, faceColors, emissiveStrength, lightTransmissive, emissionColor,
                    cutout, uvRect, extinction, FaceSealResolver.resolve(shapeKind, boxes));
        }

        /** Convenience overload for the overwhelming majority of entries (not cutout-flagged) --
         * defaults {@code cutout} false, {@code uvRect} to the shared {@link #NO_UV_RECT} zero
         * sentinel, and {@code extinction} to {@code 0f}, matching every pre-cutout-milestone call
         * site verbatim. */
        public Entry(VoxelShapeKind shapeKind, List<VoxelShapeClassifier.PackedBox> boxes,
                     int[] faceColors, double emissiveStrength, boolean lightTransmissive,
                     int emissionColor) {
            this(shapeKind, boxes, faceColors, emissiveStrength, lightTransmissive, emissionColor,
                    false, NO_UV_RECT, 0f, FaceSealResolver.resolve(shapeKind, boxes));
        }
    }

    /** Shared all-zero UV-rect sentinel for a non-cutout entry -- see {@link Entry#uvRect}'s own doc. */
    public static final float[] NO_UV_RECT = {0f, 0f, 0f, 0f};

    private final List<Entry> entries;

    public SectionPalette(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }
}
