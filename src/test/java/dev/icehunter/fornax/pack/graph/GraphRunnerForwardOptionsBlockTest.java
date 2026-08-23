package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which geometry passes receive the {@code u_PackOptions} block.
 *
 * <p><b>Both failure directions are silent, which is why this is pinned rather than trusted.</b>
 * Giving it to a DEFERRED geometry pass declares a uniform block with no backing binding in Sodium's
 * bind group -- the shader fails to compile, Vulkan hands back an invalid pipeline, and the world goes
 * black at the first terrain draw with nothing in the log naming the option. Withholding it from a
 * FORWARD one is the opposite and quieter failure: the pipeline still declares the bind-group entry
 * ({@code DeferredGeometryPipelines} adds it on the same condition), the shader never declares the
 * block, and the two disagree.
 *
 * <p>Written on the {@code GraphRunnerSunParamsTest} precedent. That test exists because a pass was
 * missing from a hardcoded list and read a default meaning "sun at full zenith", which shipped orange
 * clouds at midnight. This is the same shape of list.
 */
class GraphRunnerForwardOptionsBlockTest {

    private static PassSpec geometry(String name, GeometrySlot slot, String program) {
        return new PassSpec(name, PassType.GEOMETRY, slot, program, null, List.of(), List.of(),
                null, null, List.of(), null, null, null);
    }

    @Test
    void aForwardGeometryPassGetsTheBlockOnBothStages() {
        List<String> paths = GraphRunner.forwardGeometryShaderPaths(
                geometry("banner_patterns", GeometrySlot.BANNER_PATTERNS,
                        "shaders/blocks/banner_patterns"));
        assertEquals(List.of("shaders/blocks/banner_patterns.fsh",
                        "shaders/blocks/banner_patterns.vsh"), paths,
                "a forward pass's two stages share one pipeline and one set of bind group layouts, so"
                        + " both must carry the block or neither");
    }

    @Test
    void everyDeferredGeometrySlotGetsNothing() {
        for (GeometrySlot slot : GeometrySlot.values()) {
            if (slot.rendersForward()) {
                continue;
            }
            assertTrue(GraphRunner.forwardGeometryShaderPaths(
                            geometry("p", slot, "shaders/blocks/p")).isEmpty(),
                    "slot '" + slot.token() + "' is not forward and must not receive u_PackOptions --"
                            + " its bind group is Sodium's, which does not carry it, and the shader"
                            + " would fail to compile at the first draw");
        }
    }

    @Test
    void aPassWithNoSlotIsTerrainAndSoGetsNothing() {
        assertTrue(GraphRunner.forwardGeometryShaderPaths(
                geometry("terrain", null, "shaders/blocks/terrain")).isEmpty(),
                "an omitted slot defaults to TERRAIN, which is deferred");
    }

    @Test
    void aTrailingExtensionIsStrippedRatherThanDoubled() {
        assertEquals(List.of("shaders/blocks/banner_patterns.fsh",
                        "shaders/blocks/banner_patterns.vsh"),
                GraphRunner.forwardGeometryShaderPaths(
                        geometry("banner_patterns", GeometrySlot.BANNER_PATTERNS,
                                "shaders/blocks/banner_patterns.vsh")),
                "geometryProgramPath tolerates a written-out extension, so this must too -- otherwise"
                        + " the same graph.toml resolves a program one way and its options block"
                        + " another, and only one of them is wrong");
    }

    @Test
    void nonGeometryPassesAreNotThisBranchesBusiness() {
        assertTrue(GraphRunner.forwardGeometryShaderPaths(
                new PassSpec("tonemap", PassType.FULLSCREEN, null, null, "shaders/post/tonemap.fsh",
                        List.of(), List.of(), null, null, List.of(), null, null, null)).isEmpty(),
                "fullscreen passes get the block through their own branch; returning paths here too"
                        + " would splice it twice");
    }
}
