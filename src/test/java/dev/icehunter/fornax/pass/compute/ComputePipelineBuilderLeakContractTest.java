package dev.icehunter.fornax.pass.compute;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code buildWithDescriptorLayout} creates a shader module, descriptor set layout and pipeline
 * layout in sequence before the final {@code vkCreateComputePipelines} call; a real device is
 * needed to exercise a mid-sequence failure directly (the same constraint that makes
 * {@code BuiltinResolutionContractTest} a source-level test), so this pins the exception-safety
 * shape instead: handles hoisted above the {@code try}, and a {@code catch (RuntimeException)}
 * that frees whatever was already created before rethrowing, the same shape
 * {@code ParticlePipelineBuilder.build} uses.
 */
class ComputePipelineBuilderLeakContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/dev/icehunter/fornax/pass/compute/ComputePipelineBuilder.java");

    @Test
    void buildWithDescriptorLayoutFreesPartiallyCreatedHandlesOnFailure() throws IOException {
        String source = Files.readString(SOURCE);
        int methodStart = source.indexOf("public static CompiledComputePipeline buildWithDescriptorLayout(");
        assertTrue(methodStart >= 0, "buildWithDescriptorLayout must still exist");
        String method = source.substring(methodStart);

        int tryStart = method.indexOf("try (MemoryStack stack");
        assertTrue(tryStart >= 0, "must still build inside a try-with-resources MemoryStack block");
        String beforeTry = method.substring(0, tryStart);

        assertTrue(beforeTry.contains("long shaderModule = VK13.VK_NULL_HANDLE;"),
                "shaderModule must be hoisted above the try so a later failure can still free it");
        assertTrue(beforeTry.contains("long descriptorSetLayout = VK13.VK_NULL_HANDLE;"),
                "descriptorSetLayout must be hoisted above the try so a later failure can still free it");
        assertTrue(beforeTry.contains("long pipelineLayout = VK13.VK_NULL_HANDLE;"),
                "pipelineLayout must be hoisted above the try so a later failure can still free it");

        int catchIndex = method.indexOf("} catch (RuntimeException e) {", tryStart);
        assertTrue(catchIndex >= 0,
                "a failure after any handle was created must be caught, not left to propagate unhandled");
        String catchBlock = method.substring(catchIndex, method.indexOf('}', catchIndex + 1) + 1);
        assertTrue(catchBlock.contains(
                        "destroy(device, VK13.VK_NULL_HANDLE, pipelineLayout, descriptorSetLayout, shaderModule);"),
                "the catch must free every handle successfully created so far before rethrowing");
        assertTrue(catchBlock.contains("throw e;"),
                "the original failure must still propagate after cleanup, not be swallowed");
    }

    @Test
    void destroySkipsNullHandlesSoItIsSafeOnAnyPartialSet() throws IOException {
        String source = Files.readString(SOURCE);
        int destroyStart = source.indexOf("public static void destroy(");
        assertTrue(destroyStart >= 0, "a public destroy(...) must exist for cleanup on partial failure");
        String destroyBody = source.substring(destroyStart);

        assertTrue(destroyBody.contains("if (pipeline != VK13.VK_NULL_HANDLE)"),
                "destroy must null-check pipeline before destroying it");
        assertTrue(destroyBody.contains("if (pipelineLayout != VK13.VK_NULL_HANDLE)"),
                "destroy must null-check pipelineLayout before destroying it");
        assertTrue(destroyBody.contains("if (descriptorSetLayout != VK13.VK_NULL_HANDLE)"),
                "destroy must null-check descriptorSetLayout before destroying it");
        assertTrue(destroyBody.contains("if (shaderModule != VK13.VK_NULL_HANDLE)"),
                "destroy must null-check shaderModule before destroying it");
    }
}
