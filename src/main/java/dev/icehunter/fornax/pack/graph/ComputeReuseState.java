package dev.icehunter.fornax.pack.graph;

import java.util.Arrays;
import dev.icehunter.fornax.pack.ComputeReuseSpec;
import dev.icehunter.fornax.pipeline.FrameUniformValues;

/** Tracks successfully submitted kernel results; submission synchronization is owned by the runner. */
final class ComputeReuseState {
    private int[] values;
    private Object[] resources;
    private long[] revisions;
    private boolean valid;

    boolean needsDispatch(int[] nextValues, Object[] nextResources, long[] nextRevisions) {
        if (!valid || !Arrays.equals(values, nextValues) || !Arrays.equals(revisions, nextRevisions)
                || resources.length != nextResources.length) return true;
        for (int i = 0; i < resources.length; i++) {
            if (resources[i] != nextResources[i]) return true;
        }
        return false;
    }

    void submitted(boolean dispatched, int[] nextValues, Object[] nextResources, long[] nextRevisions) {
        if (dispatched) {
            values = nextValues.clone();
            resources = nextResources.clone();
            revisions = nextRevisions.clone();
            valid = true;
        }
    }

    static int valueCount(ComputeReuseSpec spec) {
        // Texel dimensions and dispatch extent are automatic; other push lanes are opt-in.
        return spec.runtime().size() + spec.globals().size() + spec.push().size() + 5;
    }

    static void writeFrameAndPush(ComputeReuseSpec spec, FrameUniformValues frame, PassParams params,
                                  int groupsX, int groupsY, int groupsZ, int[] values) {
        int index = spec.runtime().size();
        for (String lane : spec.globals()) values[index++] = frame.bits(lane);
        values[index++] = Float.floatToRawIntBits(params.texelSizeX());
        values[index++] = Float.floatToRawIntBits(params.texelSizeY());
        for (String lane : spec.push()) values[index++] = Float.floatToRawIntBits(pushValue(lane, params));
        values[index++] = groupsX;
        values[index++] = groupsY;
        values[index] = groupsZ;
    }

    static boolean supportsPush(String lane) {
        return switch (lane) {
            case "u_Param2", "u_Param3", "u_SunDirection.x", "u_SunDirection.y", "u_SunDirection.z" -> true;
            default -> false;
        };
    }

    private static float pushValue(String lane, PassParams params) {
        return switch (lane) {
            case "u_Param2" -> params.param2();
            case "u_Param3" -> params.param3();
            case "u_SunDirection.x" -> params.sunDirX();
            case "u_SunDirection.y" -> params.sunDirY();
            case "u_SunDirection.z" -> params.sunDirZ();
            default -> throw new IllegalArgumentException("unsupported push lane: " + lane);
        };
    }

    void invalidate() { valid = false; }
}
