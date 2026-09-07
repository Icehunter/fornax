package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;
import dev.icehunter.fornax.pack.ComputeReuseSpec;
import dev.icehunter.fornax.pipeline.FrameUniformValues;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeReuseStateTest {
    @Test void changingUnselectedSunAndTimeLeavesTheAirKeyReusable() {
        ComputeReuseSpec spec = new ComputeReuseSpec(List.of(), List.of("u_SkyState.x", "u_FrameState.z"));
        ComputeReuseState state = new ComputeReuseState();
        FrameUniformValues frame = new FrameUniformValues();
        int[] key = new int[ComputeReuseState.valueCount(spec)];
        Object[] resources = {new Object()};
        long[] revisions = {};
        int dispatches = 0;
        for (int i = 0; i < 20; i++) {
            frame.beginFrame();
            frame.skyState(0f, i * .1f, 0f, i);
            frame.frameState(i, .3f, 0f, i * .01f);
            PassParams params = PassParams.of(16, 16).withSunDirection(i * .01f, .5f, 0f).withParam2(i);
            ComputeReuseState.writeFrameAndPush(spec, frame, params, 1, 1, 1, key);
            boolean dispatch = state.needsDispatch(key, resources, revisions);
            state.submitted(dispatch, key, resources, revisions);
            if (dispatch) dispatches++;
        }
        assertEquals(1, dispatches, "sun/time are outside this kernel's declared dependencies");
    }

    @Test void actualPackLookupDeclarationsIgnoreUnselectedSunAndClock() throws Exception {
        java.nio.file.Path graphPath = java.nio.file.Path.of("../plague/graph.toml");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(graphPath));
        dev.icehunter.fornax.pack.GraphSpec graph;
        try (var reader = java.nio.file.Files.newBufferedReader(graphPath)) {
            graph = dev.icehunter.fornax.pack.PackTomlLoader.loadGraph(reader, "graph.toml");
        }
        var kernels = graph.passes().stream().filter(p -> p.reuseWhenUnchanged() != null).toList();
        assertEquals(2, kernels.size(), "the two lookup kernels are the current pack fixture");
        for (var kernel : kernels) {
            ComputeReuseSpec spec = kernel.reuseWhenUnchanged();
            int[] key = new int[ComputeReuseState.valueCount(spec)];
            java.util.Arrays.fill(key, 0, spec.runtime().size(), Float.floatToRawIntBits(1f));
            FrameUniformValues frame = new FrameUniformValues();
            ComputeReuseState state = new ComputeReuseState();
            Object[] resources = {new Object()};
            long[] revisions = {1};
            for (int i = 0; i < 2; i++) {
                frame.beginFrame();
                frame.skyState(0f, i, 0f, i);
                frame.frameState(i, .3f, 0f, i * .1f);
                PassParams params = PassParams.of(16, 16).withSunDirection(i, 1f, 0f);
                ComputeReuseState.writeFrameAndPush(spec, frame, params, 1, 1, 1, key);
                assertEquals(i == 0, state.needsDispatch(key, resources, revisions), kernel.name());
                state.submitted(i == 0, key, resources, revisions);
            }
        }
    }

    @Test void aDeclaredPushLaneAndDispatchExtentStillInvalidateTheKernel() {
        ComputeReuseSpec spec = new ComputeReuseSpec(List.of(), List.of(), List.of("u_SunDirection.x"));
        int[] key = new int[ComputeReuseState.valueCount(spec)];
        FrameUniformValues frame = new FrameUniformValues();
        ComputeReuseState state = new ComputeReuseState();
        Object[] resources = {new Object()};
        long[] revisions = {};
        ComputeReuseState.writeFrameAndPush(spec, frame, PassParams.of(16, 16), 1, 1, 1, key);
        state.submitted(true, key, resources, revisions);
        ComputeReuseState.writeFrameAndPush(spec, frame,
                PassParams.of(16, 16).withSunDirection(.25f, 0f, 0f), 1, 1, 1, key);
        assertTrue(state.needsDispatch(key, resources, revisions));
        state.submitted(true, key, resources, revisions);
        ComputeReuseState.writeFrameAndPush(spec, frame,
                PassParams.of(16, 16).withSunDirection(.25f, 0f, 0f), 2, 1, 1, key);
        assertTrue(state.needsDispatch(key, resources, revisions));
    }

    @Test void unchangedInputsSubmitOneKernelAndReuseItsResult() {
        ComputeReuseState state = new ComputeReuseState();
        int[] values = {Float.floatToRawIntBits(1f), Float.floatToRawIntBits(0f)};
        Object[] resources = {new Object()};
        long[] revisions = {1};
        int dispatches = 0, reuses = 0;
        for (int frame = 0; frame < 20; frame++) {
            boolean dispatch = state.needsDispatch(values, resources, revisions);
            state.submitted(dispatch, values, resources, revisions);
            if (dispatch) dispatches++; else reuses++;
        }
        assertEquals(1, dispatches);
        assertEquals(19, reuses);
    }

    @Test void changingAnySelectedRuntimeOrFrameBitRequiresDispatch() {
        ComputeReuseState state = new ComputeReuseState();
        int[] values = {Float.floatToRawIntBits(1f), Float.floatToRawIntBits(0f)};
        Object[] resources = {new Object()};
        long[] revisions = {1};
        state.submitted(true, values, resources, revisions);
        values[0] = Float.floatToRawIntBits(Math.nextUp(1f));
        assertTrue(state.needsDispatch(values, resources, revisions));
        state.submitted(true, values, resources, revisions);
        assertFalse(state.needsDispatch(values, resources, revisions));
        values[1] = Float.floatToRawIntBits(-0f);
        assertTrue(state.needsDispatch(values, resources, revisions));
    }

    @Test void replacementResourceOrUpstreamDispatchInvalidatesTheResult() {
        ComputeReuseState state = new ComputeReuseState();
        int[] values = {1};
        Object[] resources = {new String("same logical target")};
        long[] revisions = {7};
        state.submitted(true, values, resources, revisions);
        assertFalse(state.needsDispatch(values, resources, revisions));
        resources[0] = new String("same logical target");
        assertTrue(state.needsDispatch(values, resources, revisions));
        state.submitted(true, values, resources, revisions);
        revisions[0]++;
        assertTrue(state.needsDispatch(values, resources, revisions));
    }

    @Test void failedSubmissionDoesNotCommitCandidateInputs() {
        ComputeReuseState state = new ComputeReuseState();
        int[] values = {1};
        Object[] resources = {new Object()};
        long[] revisions = {};
        assertTrue(state.needsDispatch(values, resources, revisions));
        assertTrue(state.needsDispatch(values, resources, revisions));
        state.submitted(true, values, resources, revisions);
        values[0] = 2;
        assertTrue(state.needsDispatch(values, resources, revisions));
        values[0] = 1;
        assertFalse(state.needsDispatch(values, resources, revisions));
    }

    @Test void lostFrameDataOrAReplacementShaderRunnerCannotReuseAnOldResult() {
        ComputeReuseState state = new ComputeReuseState();
        int[] values = {1};
        Object[] resources = {new Object()};
        long[] revisions = {};
        state.submitted(true, values, resources, revisions);
        assertFalse(state.needsDispatch(values, resources, revisions));
        state.invalidate();
        assertTrue(state.needsDispatch(values, resources, revisions));
        assertTrue(new ComputeReuseState().needsDispatch(values, resources, revisions));
    }
}
