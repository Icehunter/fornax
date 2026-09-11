package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EngineBufferUploadQueue#recordForBindings} takes the reader stage mask as a parameter
 * instead of a fixed value, because its two callers record on different queues: {@code
 * ComputePassRunner} on the compute queue, {@link GraphicsBufferUploads} on the graphics queue.
 * This test reads the two source files instead of calling the method, because both calls sit
 * inside code paths that need a live Vulkan device. Other wiring pins in this repo do the
 * same; see {@code GraphRunnerSkyReprojectionOrderingTest}.
 */
class EngineBufferUploadQueueContractTest {
    private static final Path SRC_ROOT = Path.of("src/main/java");
    private static final String CALL = "EngineBufferUploadQueue.recordForBindings(";

    @Test
    void theComputePassRunnerCallUsesTheComputeShaderStage() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/dev/icehunter/fornax/pack/graph/ComputePassRunner.java"));
        String call = extractCall(source, CALL);
        assertTrue(call.contains("VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT"),
                "ComputePassRunner records for a compute-bound reader, so its barrier must target "
                        + "the compute shader stage: " + call);
    }

    @Test
    void theGraphicsBufferUploadsCallUsesTheVertexAndFragmentShaderStages() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/dev/icehunter/fornax/pack/graph/GraphicsBufferUploads.java"));
        String call = extractCall(source, CALL);
        assertTrue(call.contains("VK13.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT"),
                "a fullscreen/particles reader binds this target in both stages: " + call);
        assertTrue(call.contains("VK13.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT"),
                "a fullscreen/particles reader binds this target in both stages: " + call);
    }

    @Test
    void recordForBindingsIsCalledFromExactlyComputePassRunnerAndGraphicsBufferUploads() throws IOException {
        // The text below only matches a call written out in full as
        // EngineBufferUploadQueue.recordForBindings(. A static import would let a caller write the
        // bare method name instead, which this scan would miss, so the scan finds every caller
        // only while no such import exists in the tree.
        String staticImport = "import static "
                + EngineBufferUploadQueue.class.getName() + ".recordForBindings;";
        List<String> callers = new ArrayList<>();
        try (var files = Files.walk(SRC_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String source = Files.readString(p);
                    assertFalse(source.contains(staticImport), p
                            + " statically imports recordForBindings, so a bare call there would"
                            + " not be found by this scan, which only matches calls that write the"
                            + " class name out");
                    if (source.contains(CALL)) {
                        callers.add(p.getFileName().toString());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        assertEquals(List.of("ComputePassRunner.java", "GraphicsBufferUploads.java"),
                callers.stream().sorted().toList(),
                "recordForBindings must be called by one class per queue it can run on");
    }

    /** The full statement starting at {@code needle}, up to (and including) its closing {@code );}. */
    private static String extractCall(String source, String needle) {
        int start = source.indexOf(needle);
        assertTrue(start >= 0, needle + " not found");
        int end = source.indexOf(");", start);
        assertTrue(end >= 0, "unterminated call to " + needle);
        return source.substring(start, end + 2);
    }
}
