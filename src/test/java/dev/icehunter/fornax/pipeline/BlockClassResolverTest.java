package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.pack.material.BlockMaterialResolver;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The COAL class lane's tag list and its flag arithmetic.
 *
 * <p>Tag MEMBERSHIP is stubbed rather than read from a live registry, for the reason
 * {@link BlockMaterialResolver}'s own tests give: block tags are datapack content and are unbound in
 * a headless test. What is NOT stubbed is the tag list itself -- {@link #theCoalTagIsTheOneVanillaShips}
 * pins the exact id confirmed against the 26.2 jar, and the rest of the suite pins the behaviour
 * that tag produces.
 */
class BlockClassResolverTest {
    @BeforeAll
    static void bootstrap() {
        // Every other Bootstrap.bootStrap() caller in this suite calls tryDetectVersion() first --
        // Bootstrap.bootStrap() throws IllegalStateException("Game version not set") without it. This
        // class previously got away with the omission only because some other test class's own
        // correct call happened to run first in the same JVM and left SharedConstants already set; a
        // JUnit discovery-order change (or simply another test class running standalone) makes that
        // load-bearing coincidence break, and a broken Bootstrap.bootStrap() call poisons Blocks/
        // Items/EntityTypes for the rest of the JVM, cascading failures into every other test that
        // touches them -- not just this class's own.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A {@link BlockMaterialResolver.Lookup} whose tag contents are declared by the test. */
    private static BlockMaterialResolver.Lookup lookup(Map<String, List<Block>> tags) {
        return new BlockMaterialResolver.Lookup() {
            @Override
            public Optional<Block> block(String id) {
                return Optional.empty();
            }

            @Override
            public List<Block> tagMembers(String id) {
                return tags.getOrDefault(id, List.of());
            }
        };
    }

    /**
     * The one tag this resolver names is the one the 26.2 jar actually ships.
     *
     * <p>Confirmed against {@code data/minecraft/tags/block/coal_ores.json} in
     * {@code minecraft-merged-deobf-26.2.jar}, whose members are exactly {@code minecraft:coal_ore}
     * and {@code minecraft:deepslate_coal_ore}. This test cannot re-read the jar, so what it pins is
     * the id: well-formed, vanilla-namespaced, and exactly {@code coal_ores}. A typo or a stray
     * namespace fails here rather than being discovered as "coal ore still glows" in a cave.
     */
    @Test
    void theCoalTagIsTheOneVanillaShips() {
        assertEquals(List.of("minecraft:coal_ores"), BlockClassResolver.COAL_TAGS);
        for (String tag : BlockClassResolver.COAL_TAGS) {
            Identifier id = Identifier.tryParse(tag);
            assertTrue(id != null && !id.getPath().isEmpty(), "malformed tag id: " + tag);
        }
    }

    /**
     * Both members of the coal tag land on {@link BlockClasses#COAL}. These are the two blocks the
     * whole lane exists for: the 26.2 tag lists exactly the stone and deepslate variants, so a
     * resolve fed those members must flag both -- one missing means one of the two coal ores keeps
     * glowing and the fix reads as "half working", the classic symptom of a variant list.
     */
    @Test
    void coalOreAndDeepslateCoalOreResolveToTheCoalFlag() {
        Map<String, List<Block>> tags =
                Map.of("minecraft:coal_ores", List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));

        Map<Block, Integer> out = BlockClassResolver.resolve(lookup(tags));

        assertEquals(BlockClasses.COAL, out.get(Blocks.COAL_ORE));
        assertEquals(BlockClasses.COAL, out.get(Blocks.DEEPSLATE_COAL_ORE));
        assertEquals(2, out.size(), "nothing beyond the tag's own members is classified");
    }

    /**
     * Nothing that is not in the coal tag is classified, and an absent tag is not an error.
     *
     * <p>Block tags are datapack content; a world whose datapack removed the tag (or a headless
     * environment where tags never bound) must resolve to "no coal ores from there" rather than
     * failing -- that datapack round trip is precisely the "not IPBR" property: remove coal from the
     * tag and the glow comes back, with no code involved.
     */
    @Test
    void unclassifiedBlocksAndAbsentTagsAreBothQuiet() {
        Map<Block, Integer> out = BlockClassResolver.resolve(lookup(Map.of()));
        assertTrue(out.isEmpty(), "an environment with no coal tag bound classifies nothing");

        Map<Block, Integer> some = BlockClassResolver.resolve(
                lookup(Map.of("minecraft:coal_ores", List.of(Blocks.COAL_ORE))));
        assertFalse(some.containsKey(Blocks.COPPER_ORE));
        assertFalse(some.containsKey(Blocks.STONE));
        assertFalse(some.containsKey(Blocks.GLOWSTONE));
        assertEquals(BlockClasses.NONE, some.getOrDefault(Blocks.STONE, BlockClasses.NONE));
    }

    /**
     * A block reached through several tags gets ONE entry with the flags OR-ed, not the last one to
     * win. Synthetic today (one tag), but it is the shape a second class relies on, and an assigning
     * resolver would silently drop the first of two flags the day one is added.
     */
    @Test
    void mergesFlagsForABlockReachedThroughSeveralTags() {
        Map<Block, Integer> out = BlockClassResolver.resolve(lookup(Map.of(
                "minecraft:coal_ores", List.of(Blocks.COAL_ORE, Blocks.COAL_ORE))));

        assertEquals(BlockClasses.COAL, out.get(Blocks.COAL_ORE));
        assertEquals(1, out.size(), "a block reached twice lands on one entry");
    }

    /**
     * The installed lookup answers NONE for anything it was not given, and COAL for what it was.
     *
     * <p>Covers the read path the mesher actually uses, which is a different method from the one the
     * resolver returns; an install that dropped the map or a getter that defaulted to a nonzero flag
     * would leave every test above green while un-glowing (or flag-stamping) the whole world.
     */
    @Test
    void installedLookupDefaultsToNone() {
        try {
            BlockClasses.install(Map.of(Blocks.COAL_ORE, BlockClasses.COAL));
            assertEquals(BlockClasses.COAL, BlockClasses.flagsForBlock(Blocks.COAL_ORE));
            assertEquals(BlockClasses.NONE, BlockClasses.flagsForBlock(Blocks.COPPER_ORE));
            assertEquals(BlockClasses.NONE, BlockClasses.flagsForBlock(Blocks.STONE));
        } finally {
            BlockClasses.clear();
        }
        assertEquals(BlockClasses.NONE, BlockClasses.flagsForBlock(Blocks.COAL_ORE));
    }

    /**
     * The flag field fits the vertex lane it is packed into, with the spare capacity the design
     * claims.
     *
     * <p>a_Position.w is 16 bits and the low four carry the light emission level; of the remaining
     * twelve, the flag field claims only SEVEN (bits 4-10) since M13's paged block atlas reserved
     * bits 11-15 for a page index (see {@link FornaxChunkVertex#PAGE_INDEX_BIT_OFFSET}). Stated as an
     * assertion because the number is quoted in the javadocs as the reason a future block-level fact
     * does not need a vertex format change, and a widened emission lane or a WIDTH creeping back up
     * into the page-index slice would make them all wrong at once.
     */
    @Test
    void flagFieldFitsBesideTheEmissionNibble() {
        assertEquals(7, BlockClasses.WIDTH);
        assertEquals(0x7F, BlockClasses.MASK);
        // Four bits of emission plus seven of flags leaves exactly five for the page index, matching
        // FornaxChunkVertex.PAGE_INDEX_BIT_WIDTH -- nothing over, nothing double-claimed.
        assertEquals(FornaxChunkVertex.PAGE_INDEX_BIT_OFFSET, 4 + BlockClasses.WIDTH);
        // The bit assignment is quoted as a plain integer in the shaders (chunk_vertex.glsl's
        // FORNAX_BLOCK_CLASS_COAL = 1u); a renumbering here would silently re-class every block on
        // the shader side, so the exact value is pinned.
        assertEquals(1, BlockClasses.COAL);
        assertTrue(BuiltInRegistries.BLOCK.size() > 0, "registry bootstrapped");
    }
}
