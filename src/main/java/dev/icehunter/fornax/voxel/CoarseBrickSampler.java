package dev.icehunter.fornax.voxel;

/**
 * Coordinate math for a coarse cascade tier's point sampling: which single block a given voxel of a
 * given tier's brick reads. (Named for the BRICK, not the section: at tier > 0 the thing sampled is a
 * whole multi-section brick -- a T3 brick spans 8^3 = 512 Minecraft sections -- not a single section,
 * which is why this class is {@code CoarseBrickSampler} and not {@code CoarseSectionSampler}.)
 *
 * <p>Coarse tiers deliberately read ONE block per voxel rather than aggregating every block the
 * voxel covers. That is what makes harvest cost per brick constant across tiers (16^3 = 4,096 reads
 * at every tier, whatever volume the brick spans) -- reading all of a tier-3 brick's 2,097,152
 * blocks would be ~10 billion reads for a full tier refresh. The design doc states the resulting
 * limitation plainly: a 1-block-thick wall at coarse-tier distance can fall between sample points
 * and be missed as an occluder. That is the intended graceful degradation.
 *
 * <p>Emission does NOT use this path alone -- missing a light is far more visible than missing a
 * wall, so emission is gated on the section palette's "contains any emissive block" signal and scans
 * densely only inside sections that flag it (see the design doc's harvest section).
 *
 * <p>This class is coordinate math ONLY -- it has no {@code Level}/world-reading half yet. The plan
 * this class was written under anticipates a later wrapper that actually reads the sampled block from
 * a live {@code Level}; that wrapper does not exist in this codebase yet (correctly deferred to the
 * task that consumes it), so treat this file as the pure-math half of a two-part design, not as a
 * complete harvest path on its own.
 */
public final class CoarseBrickSampler {
    private CoarseBrickSampler() {
    }

    /**
     * Block offset, relative to the brick's own origin, that a voxel at {@code voxelCoord} along one
     * axis samples at {@code tier}. Centre-biased within the voxel's block span rather than
     * corner-anchored: a corner anchor biases every sample to one side of the brick and systematically
     * misses geometry on the other. At tier 0 (1 block per voxel) this degenerates to the voxel
     * coordinate itself, so the coarse path reproduces today's exact per-block behaviour rather than
     * approximating it.
     *
     * <p>Scalar, not a 3-axis {@code int[]}, because all three axes apply this identical formula
     * independently -- a caller wanting a full 3D offset calls this once per axis. This matters here
     * specifically: this class's own javadoc above commits it to 4,096 calls per brick (16^3 voxels),
     * and at 4,913 slots x {@link VoxelCascade#MAX_TIERS} tiers that is ~20.1M calls per full cascade
     * refresh, landing inside {@code SectionHarvester.harvest}'s tight per-voxel loop -- a loop that
     * codebase convention deliberately keeps allocation-free (see that method's own doc). Returning an
     * {@code int[]} per call would allocate on every one of those ~20M calls; escape analysis MIGHT
     * scalar-replace a returned array when this is fully inlined, but relying on that is a fragile bet
     * for a hot path that an open bug already lives in, so the API is scalar instead.
     */
    public static int sampleBlockOffset(int tier, int voxelCoord) {
        int span = VoxelCascade.blocksPerVoxel(tier);
        int centre = span / 2;
        return voxelCoord * span + centre;
    }
}
