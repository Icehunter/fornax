package dev.icehunter.fornax.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.lwjgl.system.MemoryUtil;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import dev.icehunter.fornax.pack.layout.DefineRewriter;
import dev.icehunter.fornax.pack.option.OptionScanner;
import dev.icehunter.fornax.pack.option.PackOption;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawShaderImportsTest {
    @Test
    void expandsEngineGlobalsForRawComputeCompilation() {
        String expanded = RawShaderImports.expand("""
                #version 450
                #define FORNAX_COMPUTE_GLOBALS
                #define FORNAX_GLOBALS_BINDING 0
                #moj_import <fornax:globals.glsl>
                void main() {}
                """, Map.of(), "water.comp");
        assertFalse(expanded.contains("#moj_import"));
        assertTrue(expanded.contains("uniform u_Globals"));
        assertTrue(expanded.contains("u_LocalActorPosition"));
    }

    @Test
    void recursivelyExpandsPackLocalIncludes() {
        String expanded = RawShaderImports.expand(
                "#version 450\n#moj_import <fornax_runtime:water.glsl>\n",
                Map.of("shaders/include/water.glsl",
                        "#moj_import <fornax_runtime:common.glsl>\nfloat waterFn(){return commonFn();}\n",
                        "shaders/include/common.glsl", "float commonFn(){return 1.0;}\n"),
                "water.comp");
        assertFalse(expanded.contains("#moj_import"));
        assertTrue(expanded.contains("float commonFn()"));
        assertTrue(expanded.contains("float waterFn()"));
    }

    @Test
    void realPlagueWaterComputeShadersExpandAndCompile() {
        Path plague = Path.of("..", "plague").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(plague.resolve("pack.toml")));
        Map<String, String> sources = PackDiscovery.loadShaderSources(plague);
        Map<String, PackOption> options = OptionScanner.scan(sources);
        for (String path : new String[]{
                "shaders/compute/water_prepare.comp",
                "shaders/compute/water_step_a.comp",
                "shaders/compute/water_step_b.comp",
                "shaders/compute/water_commit.comp"}) {
            for (String tier : List.of("1", "2")) {
                String rewritten = DefineRewriter.rewrite(sources.get(path), options,
                        Map.of("PLAGUE_WATER_INTERACTION", tier));
                String expanded = RawShaderImports.expand(rewritten, sources, path);
                ByteBuffer spirv = ComputeShaderCompiler.compileToSpirv(expanded, path + " tier=" + tier);
                try {
                    assertTrue(spirv.remaining() > 0, path + " tier=" + tier);
                } finally {
                    MemoryUtil.memFree(spirv);
                }
            }
        }
    }

    // Comment-blind import scanning: clouds.glsl's own noise-hook doc comment documents its usage
    // as prose, including a literal "#moj_import <fornax_runtime:clouds.glsl>" line inside a `//`
    // comment. IMPORT's regex has no notion of a comment, so while clouds.glsl is still being
    // expanded (its own key is "active"), that commented-out example line reads as a second, real,
    // self-referential import and throws "cycle" with no actual circular dependency anywhere in
    // the real file graph. Fullscreen/geometry passes never hit this because their source is
    // served pre-stripped (RuntimeShaderPack.servedSources); this raw-shaderc path deliberately
    // serves unstripped text instead.

    @Test
    void aCommentedOutExampleImportIsNotTreatedAsARealOne() {
        String expanded = RawShaderImports.expand(
                "#version 450\n#moj_import <fornax_runtime:documented.glsl>\nvoid main() {}\n",
                Map.of("shaders/include/documented.glsl", """
                        // Usage:
                        //     #moj_import <fornax_runtime:documented.glsl>
                        float documentedFn(){return 1.0;}
                        """),
                "test.comp");
        assertFalse(expanded.contains("#moj_import"));
        assertTrue(expanded.contains("float documentedFn()"));
    }

    @Test
    void aSelfReferentialCommentedExampleDoesNotThrowACycle() {
        // The imported file's own doc comment shows an example of importing ITSELF. This must not
        // throw, since the commented line is not a real import.
        assertDoesNotThrow(() -> RawShaderImports.expand(
                "#version 450\n#moj_import <fornax_runtime:selfDocumented.glsl>\nvoid main() {}\n",
                Map.of("shaders/include/selfDocumented.glsl", """
                        // Contract:
                        //     #moj_import <fornax_runtime:selfDocumented.glsl>
                        float selfDocumentedFn(){return 1.0;}
                        """),
                "test.comp"));
    }

    @Test
    void aBlockCommentedExampleImportIsAlsoNotTreatedAsARealOne() {
        // GlslCommentStripper handles both comment styles; this pins the /* */ half specifically,
        // since the scenario above only exercises the // half.
        String expanded = RawShaderImports.expand(
                "#version 450\n#moj_import <fornax_runtime:documented.glsl>\nvoid main() {}\n",
                Map.of("shaders/include/documented.glsl",
                        "/* #moj_import <fornax_runtime:documented.glsl> */\n"
                                + "float documentedFn(){return 1.0;}\n"),
                "test.comp");
        assertFalse(expanded.contains("#moj_import"));
        assertTrue(expanded.contains("float documentedFn()"));
    }

    @Test
    void aGenuineImportCycleStillThrows() {
        // A real cycle must still throw. Only a commented-out false one is swallowed.
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> RawShaderImports.expand(
                "#version 450\n#moj_import <fornax_runtime:a.glsl>\n",
                Map.of("shaders/include/a.glsl", "#moj_import <fornax_runtime:b.glsl>\n",
                        "shaders/include/b.glsl", "#moj_import <fornax_runtime:a.glsl>\n"),
                "test.comp"));
        assertTrue(e.getMessage().contains("cycle"));
    }

    @Test
    void theRealCloudsGlslNoiseHookCommentExpandsCleanly() {
        // Reads the actual sibling plague repo's real clouds.glsl: the file whose noise-hook
        // contract doc comment contains "#moj_import <fornax_runtime:clouds.glsl>" inside a `//`
        // comment, the exact scenario the tests above pin synthetically.
        Path plague = Path.of("..", "plague").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(plague.resolve("pack.toml")));
        Map<String, String> sources = PackDiscovery.loadShaderSources(plague);
        String cloudsGlsl = sources.get("shaders/include/clouds.glsl");
        Assumptions.assumeTrue(cloudsGlsl != null);
        assertTrue(cloudsGlsl.contains("#moj_import <fornax_runtime:clouds.glsl>"),
                "this test pins a real comment in the real file; if it's gone, this assumption is stale");
        String expanded = assertDoesNotThrow(() -> RawShaderImports.expand(
                "#version 450\n#moj_import <fornax_runtime:clouds.glsl>\n",
                sources, "clouds_march_volume.comp"));
        assertFalse(expanded.contains("#moj_import"));
    }
}
