package dev.icehunter.fornax.atlas;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The live BlockState/FluidState -> page lookup {@code MaterialIdContext#setAtlasPage} reads.
 *
 * <p>Phase 2's own contract: nothing installs this cache yet, so every lookup answers 0 regardless of
 * what's asked -- see {@link BlockAtlasPages}' own doc for why that is the honest state, not a bug.
 * {@link #installedLookupIsHonored} pins the OTHER half of the contract -- once a later phase does
 * call {@link BlockAtlasPages#install}, the lookup must actually reflect it, the same install/clear/
 * lookup shape {@code BlockClassResolverTest#installedLookupDefaultsToNone} already pins for
 * {@code BlockClasses}.
 */
class BlockAtlasPagesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        BlockAtlasPages.clear();
    }

    @Test
    void uninstalledLookupAnswersZeroForEveryBlock() {
        assertEquals(0, BlockAtlasPages.pageForState(Blocks.STONE.defaultBlockState()));
        assertEquals(0, BlockAtlasPages.pageForState(Blocks.COAL_ORE.defaultBlockState()));
        assertEquals(0, BlockAtlasPages.pageForFluidState(Fluids.WATER.defaultFluidState()));
        assertEquals(0, BlockAtlasPages.pageForFluidState(Fluids.LAVA.defaultFluidState()));
    }

    @Test
    void installedLookupIsHonored() {
        BlockAtlasPages.install(Map.of(Blocks.STONE, 2));

        assertEquals(2, BlockAtlasPages.pageForState(Blocks.STONE.defaultBlockState()));
        assertEquals(0, BlockAtlasPages.pageForState(Blocks.DIRT.defaultBlockState()),
                "unmapped blocks default to page 0, not the last-installed value");
    }

    @Test
    void clearReturnsToTheUninstalledDefault() {
        BlockAtlasPages.install(Map.of(Blocks.STONE, 5));
        assertEquals(5, BlockAtlasPages.pageForState(Blocks.STONE.defaultBlockState()));

        BlockAtlasPages.clear();

        assertEquals(0, BlockAtlasPages.pageForState(Blocks.STONE.defaultBlockState()));
    }

    /**
     * {@link BlockAtlasPages#pageForFluidState} resolves via the fluid's OWN legacy block, exactly
     * like {@code FluidRendererMaterialIdMixin}'s {@code fluidKey} -- installing a page under
     * WATER's block must be what a water FluidState resolves to, independent of whatever block a
     * caller might otherwise have had in hand (a waterlogged host, for instance).
     */
    @Test
    void fluidStateResolvesThroughTheFluidsOwnLegacyBlock() {
        BlockAtlasPages.install(Map.of(Blocks.WATER, 4));

        assertEquals(4, BlockAtlasPages.pageForFluidState(Fluids.WATER.defaultFluidState()));
        assertEquals(4, BlockAtlasPages.pageForState(Fluids.WATER.defaultFluidState().createLegacyBlock()),
                "pageForFluidState must agree with resolving createLegacyBlock() directly");
        assertEquals(0, BlockAtlasPages.pageForFluidState(Fluids.LAVA.defaultFluidState()),
                "an unmapped fluid must not inherit water's installed page");
    }
}
