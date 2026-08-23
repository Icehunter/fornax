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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
