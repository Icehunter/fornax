package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pipeline.GeometryInputs;
import java.util.Arrays;

/** Read-only terrain texel buffers, independent of the geometry texture sampler slots. */
final class GeometryBufferBindings {
    private static final String[][] references = new String[GeometrySlot.values().length][GeometryInputs.BUFFER_RESERVED];
    private static final RawVulkanGpuBuffer[][] wrappers = new RawVulkanGpuBuffer[GeometrySlot.values().length][GeometryInputs.BUFFER_RESERVED];
    private static GpuBuffer empty;

    private GeometryBufferBindings() {}

    static void set(GeometrySlot slot, int index, String reference) {
        references[slot.ordinal()][index] = reference;
    }

    static void clearReferences() {
        for (String[] row : references) Arrays.fill(row, null);
    }

    static GpuBuffer resolve(GeometrySlot slot, int index, TargetRegistry registry) {
        String reference = references[slot.ordinal()][index];
        BufferInstance buffer = registry != null && reference != null ? registry.getBuffer(reference) : null;
        // Prepare can precede engine allocation. Resolve the live handle at draw time, including
        // reallocation, rather than retaining a freed registry handle across frames.
        if (buffer != null && buffer.vkBuffer() != 0 && buffer.sizeBytes() >= Integer.BYTES) {
            RawVulkanGpuBuffer wrapper = wrappers[slot.ordinal()][index];
            if (wrapper == null || wrapper.vkBuffer() != buffer.vkBuffer() || wrapper.size() != buffer.sizeBytes()) {
                wrapper = new RawVulkanGpuBuffer(buffer.vkBuffer(), GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, buffer.sizeBytes());
                wrappers[slot.ordinal()][index] = wrapper;
            }
            return wrapper;
        }
        // A valid one-word descriptor lets shaders reject an absent ABI by textureSize without
        // touching unallocated memory. No graph target is silently substituted with scene data.
        if (empty == null) {
            empty = RenderSystem.getDevice().createBuffer(() -> "Fornax empty geometry buffer",
                    GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE, Integer.BYTES);
            try (var mapped = empty.map(false, true)) {
                mapped.data().putInt(0);
            }
        }
        return empty;
    }

    /** Called only at GraphRunner's device-idle pack teardown boundary. */
    static void close() {
        clearReferences();
        for (RawVulkanGpuBuffer[] row : wrappers) Arrays.fill(row, null);
        if (empty != null) {
            empty.close();
            empty = null;
        }
    }
}
