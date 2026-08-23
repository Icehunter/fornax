package dev.icehunter.fornax.pack.layout;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.MappableRingBuffer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live GPU-side mirror of a {@link PackOptionsLayout}'s {@code u_PackOptions} block: a
 * {@link MappableRingBuffer} sized to the layout's {@link PackOptionsLayout#blockSize()}, mirroring
 * {@code GBufferResolvePass.resolveSettingsBuffer()}'s map/write/close pattern. Bound as {@code u_PackOptions}
 * in every pack pipeline's bind group.
 *
 * <p>Every write rewrites the whole block, not just the changed member: {@code rotate()} may hand back a
 * different underlying {@link GpuBuffer} slot than the previous frame wrote, so a partial write would leave
 * that slot's other members stale or uninitialized. A small cache of the last-known value per option name
 * makes {@link #writeOne(String, float)} (a single slider drag) just as safe as {@link #writeAll(Map)} --
 * both funnel through the same full-block rewrite. The block is well under 256 bytes for all of v0.1's
 * options, so this is cheap.
 */
public final class PackOptionsBuffer {
    private final PackOptionsLayout layout;
    private final MappableRingBuffer ring;
    private final Map<String, Float> current = new LinkedHashMap<>();

    public PackOptionsBuffer(PackOptionsLayout layout) {
        this.layout = layout;
        this.ring = new MappableRingBuffer(() -> "Fornax pack options buffer",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, layout.blockSize());
    }

    public GpuBuffer currentBuffer() {
        return ring.currentBuffer();
    }

    /**
     * Reads back a single tracked option's last-written value, or {@code fallback} if the pack
     * declares no such option. Used by engine-side code that needs one specific pack option's value
     * outside the generic {@code u_PackOptions} GPU binding -- e.g. {@code UniformBufferManagerMixin}
     * forwarding the pack's own bump/AO strength options into terrain's separate, small
     * {@code u_PbrSettings} block (geometry passes never bind {@code u_PackOptions} itself; see
     * {@code GraphRunner.rebuild}'s own doc comment on that).
     */
    public float get(String name, float fallback) {
        Float v = current.get(name);
        return v != null ? v : fallback;
    }

    /** Replaces every tracked value (e.g. on pack/profile load) and rewrites the whole block. */
    public void writeAll(Map<String, Float> values) {
        current.clear();
        current.putAll(values);
        writeCurrent();
    }

    /** Updates a single option (e.g. a live slider drag) and rewrites the whole block. */
    public void writeOne(String name, float value) {
        current.put(name, value);
        writeCurrent();
    }

    private void writeCurrent() {
        ring.rotate();
        try (GpuBufferSlice.MappedView data = ring.currentBuffer().map(false, true)) {
            for (Map.Entry<String, Integer> e : layout.offsets().entrySet()) {
                Float v = current.get(e.getKey());
                if (v != null) {
                    data.data().putFloat(e.getValue(), v);
                }
            }
        }
    }

    /** Releases the underlying ring buffer's GPU resources; call when the owning pack unloads. */
    public void close() {
        ring.close();
    }
}
