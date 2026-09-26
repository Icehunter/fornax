package dev.icehunter.fornax.rt.mesh;

import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import dev.icehunter.fornax.metalfx.rt.TerrainMeshSelection;

import java.util.Objects;

/**
 * One uploaded terrain mesh as a caster: which section and pass it is, the mutation stamp of that
 * slot, and where its packed {@code FornaxChunkVertex} quads sit in the renderer's arena.
 *
 * <p>The buffer is held by identity and never dereferenced past the frame it was captured in: an
 * arena can be relocated or freed by the next frame's uploads. {@link #sameContent} is the diff
 * gate's whole comparison; the stamp, not the address, is what says the vertices changed, because
 * an equal-size replacement reuses the address.
 */
public record CasterSource(Key key, long revision, VulkanGpuBuffer buffer, TerrainMeshSelection.VertexRange range) {

    /** Section coordinates plus which terrain pass: SOLID and CUTOUT are separate meshes. */
    public record Key(int x, int y, int z, boolean cutout) {
    }

    public CasterSource {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(range, "range");
    }

    /** Same stamp, same buffer object, same range: nothing about the vertices changed. */
    public boolean sameContent(CasterSource other) {
        return other != null && revision == other.revision && buffer == other.buffer && range.equals(other.range);
    }

    /** Whole quads: two triangles each, in the (0,1,2),(2,3,0) order the shared index buffer uses. */
    public int triangleCount() {
        return Math.toIntExact(range.vertexCount() / 2);
    }
}
