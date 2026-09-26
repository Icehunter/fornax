package dev.icehunter.fornax.rt.mesh;

/**
 * The coarse grid a mesh structure is built against, so the float coordinates inside it stay small.
 *
 * <p>A structure holds positions as floats. World coordinates past a few million blocks lose whole
 * blocks at float precision, so every instance is placed relative to a grid origin near the camera
 * and the camera itself is rebased onto that origin before it reaches a kernel. The step is sixteen
 * sections; it does not follow the pack's visible radius, because a structure that moved with every
 * camera step would rebuild every frame.
 */
public final class MeshGrid {

    public static final int STEP_BLOCKS = 256;

    private MeshGrid() {
    }

    /** The grid origin at or below {@code coordinate}: a multiple of {@link #STEP_BLOCKS}. */
    public static int origin(double coordinate) {
        return ((int) Math.floor(coordinate / STEP_BLOCKS)) * STEP_BLOCKS;
    }

    /** A section's block coordinate relative to a grid origin, as the instance translation. */
    public static float sectionOffset(int section, int origin) {
        return (float) ((long) section * 16 - origin);
    }

    /** The camera relative to a grid origin, in doubles until the last step so nothing is lost first. */
    public static float rebase(double coordinate, int origin) {
        return (float) (coordinate - origin);
    }
}
