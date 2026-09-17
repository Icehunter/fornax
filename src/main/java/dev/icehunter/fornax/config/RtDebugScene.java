package dev.icehunter.fornax.config;

/**
 * Which acceleration structure the {@code rt_debug} dispatch traces when a {@link RtDebugMode} is
 * selected. The two tiers of the celestial cascade build different geometry from different sources,
 * so looking at one says nothing about the other.
 *
 * <p>They also live in different coordinate frames: the voxel scene is addressed relative to its
 * exported window's first section, the mesh scene relative to a coarse grid origin the mesh tracer
 * rebases onto. The dispatch takes the origin from whichever scene it traces, which is why this is
 * a selector rather than a pair of overlaid views.
 */
public enum RtDebugScene {
    /**
     * The brick-grid voxel structure the {@code HARDWARE_VOXEL} tier traces: approximate boxes,
     * present only inside the exported window, and the scene where a face field naming nothing is
     * expected (the exact-triangle supplement carries UVs instead of one of the six axes).
     */
    VOXEL,

    /**
     * The uploaded chunk meshes the {@code HARDWARE_MESH} tier traces: exact terrain geometry,
     * bounded by the shadow caster volume rather than a window. Every full block face here carries
     * a decodable face, so a surface that paints as unknown in this scene is a defect rather than
     * an expected answer.
     */
    MESH;
}
