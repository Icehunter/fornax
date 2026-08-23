package dev.icehunter.fornax.pack.graph;

/**
 * Screen-sized reflection SSBO for the voxel water reflection pass: one entry per rendered pixel,
 * two R32_UINT words (rg + b/hit, half-float packed). The compute pass {@code voxel_water_refl}
 * writes it as a STORAGE_BUFFER; the water composite reads it back as a UNIFORM_TEXEL_BUFFER
 * (usamplerBuffer, texelFetch) -- the same compute-write / fullscreen-texel-read contract the
 * emitter light volume already proved. Declared in graph.toml as a {@code kind="buffer"} target so
 * GraphValidator recognizes the name; the engine (not scale) sizes it, mirroring
 * {@code BrickGridUpload.ensureAllocated}. {@code TargetRegistry.ensureBufferSize} zero-clears at
 * allocation (MoltenVK garbage-VRAM law), so no separate clear is needed.
 */
public final class VoxelWaterReflBuffer {
    public static final String TARGET = "voxelWaterRefl";
    public static final int WORDS_PER_PIXEL = 2; // word0 = packHalf2x16(r,g), word1 = packHalf2x16(b,hit)
    private static final int BYTES_PER_WORD = 4;

    private VoxelWaterReflBuffer() {}

    /** Bytes required to back a width x height reflection image. Long arithmetic: width*height*8
     * overflows a 32-bit int above ~1.6 Mpx (a Retina backing store exceeds that). */
    public static long byteSize(int width, int height) {
        return (long) width * height * WORDS_PER_PIXEL * BYTES_PER_WORD;
    }

    /** (Re)size the SSBO to the current render resolution; idempotent (ensureBufferSize no-ops when
     * already at that size). Call BEFORE runner build so ComputePassRunner.build classifies the
     * buffer input as STORAGE_BUFFER, exactly like BrickGridUpload's own pre-build allocation. */
    public static void ensureAllocated(TargetRegistry registry, int width, int height) {
        registry.ensureBufferSize(TARGET, byteSize(Math.max(1, width), Math.max(1, height)));
    }

    /** Free the SSBO when the pass is compile-disabled so a disabled tier-4 pays no VRAM. */
    public static void free(TargetRegistry registry) {
        registry.releaseBuffer(TARGET);
    }
}
