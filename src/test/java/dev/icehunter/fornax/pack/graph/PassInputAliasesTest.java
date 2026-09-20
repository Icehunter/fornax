package dev.icehunter.fornax.pack.graph;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A shader that cannot say which target it reads is one a reader has to hold a list for. */
class PassInputAliasesTest {
    private static PassSpec fullscreen(String name, String shader, List<String> inputs) {
        return new PassSpec(name, PassType.FULLSCREEN, null, null, shader, inputs,
                List.of("out"), null, null, List.of(), null, null, null, null, null);
    }

    private static Map<String, String> aliases(PassSpec... passes) {
        return PassInputAliases.byShader(new GraphSpec(Map.of(), Map.of(), List.of(passes)), Set.of());
    }

    @Test void everyInputOfASingleUsePassIsNamed() {
        var out = aliases(fullscreen("local", "a.fsh",
                List.of("builtin.gNormal", "voxelPalette", "voxelLocalRadiance")));
        assertEquals("#define u_GNormal u_Input0\n"
                + "#define u_VoxelPalette u_Input1\n"
                + "#define u_VoxelLocalRadiance u_Input2\n", out.get("a.fsh"));
    }

    @Test void aSharedShaderKeepsOnlyTheNamesEveryPassAgreesOn() {
        var out = aliases(
                fullscreen("blur1", "blur.fsh", List.of("globals", "bloom1")),
                fullscreen("blur2", "blur.fsh", List.of("globals", "bloom2")));
        assertEquals("#define u_Globals u_Input0\n", out.get("blur.fsh"),
                "the shared first input is named; the one they disagree on keeps its number");
    }

    @Test void aPassWithFewerInputsBoundsWhatIsNamed() {
        var out = aliases(
                fullscreen("long", "s.fsh", List.of("depth", "gNormal", "ssrRaw")),
                fullscreen("short", "s.fsh", List.of("depth")));
        assertEquals("#define u_Depth u_Input0\n", out.get("s.fsh"));
    }

    @Test void twoInputsThatWouldShareOneNameLeaveTheSecondNumbered() {
        var out = aliases(fullscreen("p", "s.fsh", List.of("builtin.depth", "depth")));
        assertEquals("#define u_Depth u_Input0\n", out.get("s.fsh"),
                "the first keeps the name; the second cannot quietly take it");
    }

    @Test void anOptionsNameIsNotTakenByATarget() {
        var out = PassInputAliases.byShader(new GraphSpec(Map.of(), Map.of(),
                List.of(fullscreen("p", "s.fsh", List.of("exposure", "bloomFinal")))),
                Set.of("u_Exposure"));
        assertEquals("#define u_BloomFinal u_Input1\n", out.get("s.fsh"),
                "the option keeps u_Exposure; the target at zero stays numbered");
    }

    @Test void onlyFullscreenPassesGetNames() {
        var compute = new PassSpec("c", PassType.COMPUTE, null, null, "c.comp",
                List.of("globals"), List.of("out"), null, null, List.of(1, 1, 1), null, null,
                null, null, null);
        assertFalse(aliases(compute).containsKey("c.comp"),
                "a compute pass declares its own bindings in its own source");
    }

    @Test void aNameThatCannotBeAnIdentifierIsLeftAlone() {
        assertEquals(null, PassInputAliases.alias("9lives"));
        assertEquals("u_Ssr_history", PassInputAliases.alias("ssr.history"));
        assertEquals("u_Depth", PassInputAliases.alias("builtin.depth"));
    }

    @Test void thePacksOwnGraphNamesTheInputsThatMatter() {
        var out = aliases(fullscreen("voxel_local_direct", "shaders/post/voxel_local_direct.fsh",
                List.of("builtin.gNormal", "consolidatedGbuf", "builtin.depth", "voxelOccupancy",
                        "voxelPayload", "voxelPalette", "voxelBrickSummary", "voxelLocalRadiance")));
        String block = out.get("shaders/post/voxel_local_direct.fsh");
        assertTrue(block.contains("#define u_VoxelLocalRadiance u_Input7"));
        assertEquals(8, block.lines().count());
    }
}
