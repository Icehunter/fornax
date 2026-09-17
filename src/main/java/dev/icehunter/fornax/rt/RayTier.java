package dev.icehunter.fornax.rt;

/**
 * Which traversal answered a ray, and with what geometry.
 *
 * <p>The ordinal is the wire value in every result Fornax produces: the G channel of an image-form
 * answer and word 7 of a buffer-form hit record. Ascending fidelity, so "best available" is a
 * {@code max()} and a pack's minimum is a {@code >=} comparison. Zero means nobody answered, which
 * is what an untraced buffer and an uncleared texel both read back as.
 *
 * <p>Append only. Values 4..15 are reserved: the image encoding stores the tier as an exact small
 * float integer and the buffer encoding as a {@code uint}, so both have headroom, and an appended
 * constant leaves every existing {@code >=} comparison correct.
 *
 * <p>The two axes are recoverable from one number. {@code tier >= HARDWARE_VOXEL} means hardware
 * traversal; {@code tier == HARDWARE_MESH} means exact geometry. There is no software-mesh tier:
 * nothing in this engine builds a BVH on the CPU.
 */
public enum RayTier {
    /** Nobody answered. Every other field of the record is meaningless. */
    NONE,
    /** GLSL DDA over the brick grid. Any Vulkan device. */
    SOFTWARE_VOXEL,
    /** Metal acceleration structure over the triangles rt_expand writes. */
    HARDWARE_VOXEL,
    /** Metal acceleration structure over uploaded FornaxChunkVertex meshes. */
    HARDWARE_MESH;
}
