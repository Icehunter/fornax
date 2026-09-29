package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetalRtPaletteCapacityTest {
    @Test void nativeConsumerDecodesEveryPaletteByteAndUsesTheSelectedStride() throws Exception {
        Assumptions.assumeTrue(Objc.isLoaded());
        long device = Objc.createSystemDefaultMetalDevice();
        Assumptions.assumeTrue(device != 0);
        long pool = Objc.autoreleasePoolPush();
        var owned = new ArrayDeque<Long>();
        owned.push(device);
        try {
            String consumer = Files.readString(Path.of("src/main/resources/assets/fornax/shaders_engine/rt_trace.metal"));
            String probe = """
                    kernel void palette_capacity_probe(device uint* output [[buffer(0)]], uint i [[thread_position_in_grid]]) {
                        output[i*2] = rtPaletteIndex((i << 16u) | 0x5fffu);
                        output[i*2+1] = MAX_PALETTE_ENTRIES;
                    }
                    """;
            long queue = Objc.msgSendId(device, Objc.selector("newCommandQueue"));
            owned.push(queue);
            long output = MetalRtAcceleration.createBuffer(device, 256L * 2 * Integer.BYTES);
            owned.push(output);
            MemorySegment bytes = MemorySegment.ofAddress(Objc.msgSendId(output, Objc.selector("contents")))
                    .reinterpret(256L * 2 * Integer.BYTES);
            for (int capacity : new int[]{96, 128, 192, 240}) {
                long library = MetalRtShaders.compileSource(device, "palette_capacity_probe.metal",
                        MetalRtShaders.capacityPreamble(capacity) + consumer + probe);
                owned.push(library);
                long function = Objc.msgSendId(library, Objc.selector("newFunctionWithName:"), Objc.nsString("palette_capacity_probe"));
                assertNotEquals(0L, function);
                owned.push(function);
                var pipeline = Objc.msgSendIdIdErr(device, Objc.selector("newComputePipelineStateWithFunction:error:"), function);
                assertNotEquals(0L, pipeline.id(), pipeline.error());
                owned.push(pipeline.id());
                long command = Objc.msgSendId(queue, Objc.selector("commandBuffer"));
                long encoder = Objc.msgSendId(command, Objc.selector("computeCommandEncoder"));
                Objc.msgSendVoid(encoder, Objc.selector("setComputePipelineState:"), pipeline.id());
                Objc.msgSendVoidIdLongLong(encoder, Objc.selector("setBuffer:offset:atIndex:"), output, 0, 0);
                Objc.dispatchThreadgroups(encoder, 4, 1, 1, 64, 1, 1);
                Objc.msgSendVoid(encoder, Objc.selector("endEncoding"));
                Objc.msgSendVoid(command, Objc.selector("commit"));
                Objc.msgSendVoid(command, Objc.selector("waitUntilCompleted"));
                for (int i = 0; i < 256; i++) {
                    assertEquals(i, bytes.get(ValueLayout.JAVA_INT, i * 8L), "native byte " + i);
                    assertEquals(capacity, bytes.get(ValueLayout.JAVA_INT, i * 8L + 4));
                }
            }
        } finally {
            while (!owned.isEmpty()) Objc.msgSendVoid(owned.pop(), Objc.selector("release"));
            Objc.autoreleasePoolPop(pool);
        }
    }
}
