package dev.icehunter.fornax.voxel;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * Whether a face between two blocks lets any light cross, per vanilla's OWN light-transport physics
 * -- never Sodium's (or any mesh-culling mod's) internal rendering decision. Uses the exact function
 * vanilla's own light-propagation code is built on, so this can never silently diverge from real
 * in-game lighting behavior, and a Sodium update can never change what this milestone considers
 * "exposed."
 */
public final class FaceExposure {
    private FaceExposure() {
    }

    /** {@code towardNeighbor} is the direction from {@code from} to {@code to}. Returns true if
     * vanilla's own light engine would let at least some light cross this boundary. {@code
     * getLightDampeningInto} returns either the shape-merge "fully sealed" sentinel (one above
     * {@code MAX_LEVEL}, e.g. two slabs whose combined shapes seal the shared face) or the
     * neighbor's own {@code getLightDampening()} passed through unchanged when the shapes don't
     * force a seal. A dampening of exactly {@code MAX_LEVEL} (vanilla's own per-block "opaque"
     * value, used by e.g. stone and tinted glass) already zeroes out even a maximum-level light
     * source, so it must be treated as sealed too -- hence the strict {@code <}, not {@code <=}. */
    public static boolean isExposed(BlockState from, BlockState to, Direction towardNeighbor) {
        int dampening = LightEngine.getLightDampeningInto(from, to, towardNeighbor, to.getLightDampening());
        return dampening < LightEngine.MAX_LEVEL;
    }
}
