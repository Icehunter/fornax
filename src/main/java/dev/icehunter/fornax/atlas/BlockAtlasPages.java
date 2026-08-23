package dev.icehunter.fornax.atlas;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Map;

/**
 * The live {@code BlockState} -> atlas PAGE lookup the terrain mesher reads, mirroring {@link
 * dev.icehunter.fornax.pipeline.BlockClasses#flagsForBlock}'s cache shape exactly: a volatile {@code
 * Map<Block, Integer>} rebuilt wholesale on {@link #install}, never mutated in place, read from
 * chunk-build threads with no locking.
 *
 * <p><b>Phase 2 (this class's current state): the map is NEVER installed.</b> {@link #pageForState}
 * and {@link #pageForFluidState} therefore answer 0 for every block today -- honestly, not as a stub
 * pretending to be real data. {@code dev.icehunter.fornax.mixin.vanilla.SpriteLoaderPagedStitchMixin}
 * runs {@link BlockAtlasPaging}'s real allocator per reload and logs what it finds, but nothing yet
 * reconciles that per-reload plan
 * down to one page per {@code Block} (that reconciliation needs a model-baked BlockState -> sprite-
 * set lookup that doesn't exist as live data yet -- {@link BlockAtlasPageAssignmentCache} is the
 * tested, not-yet-wired mechanism for the reconciliation step itself) or calls {@link #install} with
 * the result. Both are Phase 3's job. Until then this class's only observable effect is that {@code
 * MaterialIdContext#setAtlasPage} is called with a value that is always 0, which is exactly the value
 * every block already implicitly had before this lane existed -- so wiring the lookup path end to end
 * now costs nothing behaviorally.
 */
public final class BlockAtlasPages {
    private static volatile Map<Block, Integer> byBlock = Map.of();

    private BlockAtlasPages() {}

    public static void install(Map<Block, Integer> map) { byBlock = Map.copyOf(map); }

    public static void clear() { byBlock = Map.of(); }

    public static int pageForState(BlockState state) {
        return byBlock.getOrDefault(state.getBlock(), 0);
    }

    /**
     * Convenience over {@link #pageForState}, resolving the fluid's OWN {@code BlockState} first via
     * {@link FluidState#createLegacyBlock()} -- same rationale as {@link
     * dev.icehunter.fornax.pipeline.MaterialIdContext#setBlockClass}'s {@code fluidKey}: a
     * waterlogged HOST block must never leak into a fluid's own page lookup. {@code
     * dev.icehunter.fornax.mixin.sodium.FluidRendererMaterialIdMixin} already resolves this same
     * {@code fluidKey} once per block for {@code BlockMaterials.idForState}/{@code
     * BlockClasses.flagsForBlock} and calls {@link #pageForState} directly with it rather than this
     * method, to avoid resolving {@code createLegacyBlock()} twice in the hottest loop in terrain
     * meshing; this overload exists for any other caller that only has a {@code FluidState} in hand.
     */
    public static int pageForFluidState(FluidState fluidState) {
        return pageForState(fluidState.createLegacyBlock());
    }
}
