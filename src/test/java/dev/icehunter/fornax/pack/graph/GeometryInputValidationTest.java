package dev.icehunter.fornax.pack.graph;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.icehunter.fornax.pack.FornaxPackError;
import dev.icehunter.fornax.pipeline.GeometryInputs;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link GraphValidator}'s geometry-input finality rule (Round A, Task 3): a {@code geometry}
 * pass's declared {@code inputs} must each be either a finish-opaque builtin/engine-owned resource,
 * or a declared target some pass in the graph actually writes -- a never-written target would read
 * garbage at translucent-draw time, so it is refused at load. Disabled-target rejection is already
 * covered generically by {@code checkGateConsistency} (exercised here to confirm it still applies to
 * a geometry pass's inputs, not just fullscreen ones).
 */
class GeometryInputValidationTest {
    @Test
    void geometryInputBuiltinDepthOpaqueAccepted() {
        assertDoesNotThrow(() -> GraphValidatorTestSupport.validateTerrainWithInputs("builtin.depth_opaque"));
    }

    @Test
    void geometryInputSsrTargetWrittenBySomePassAccepted() {
        // ssr is written by a fullscreen pass elsewhere in the graph -> final at finish-opaque,
        // regardless of the two passes' relative file order (terrain_opaque is declared FIRST here,
        // deliberately, to pin that pass-index order is not the finality signal).
        assertDoesNotThrow(GraphValidatorTestSupport::validateTerrainWithSsrInputWrittenByPass);
    }

    @Test
    void geometryInputAcceptsPreviousFrameOfAnyHistoryTarget() {
        assertDoesNotThrow(GraphValidatorTestSupport::validateTerrainWithHistoryInputWrittenByPass);
    }

    @Test
    void geometryInputUnknownNameRejected() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                () -> GraphValidatorTestSupport.validateTerrainWithInputs("builtin.doesNotExist"));
        assertTrue(e.getMessage().contains("terrain_opaque"), e.getMessage());
    }

    @Test
    void geometryInputNeverWrittenTargetRejectedAsNotFinal() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                GraphValidatorTestSupport::validateTerrainWithNeverWrittenTargetInput);
        assertTrue(e.getMessage().toLowerCase().contains("final"), e.getMessage());
    }

    @Test
    void geometryInputDisabledTargetRejected() {
        assertThrows(FornaxPackError.class, GraphValidatorTestSupport::validateTerrainWithDisabledTargetInput);
    }

    // --- T2-review MEDIUM fix: builtin.depth_opaque is captured at the finish-opaque boundary, AFTER
    // every fullscreen/mipchain/copy/compute pass in the graph already ran this frame (see
    // GraphRunner.finish -- the capture call is the LAST thing before r.swapHistory()). A non-geometry
    // pass declaring it as an input would therefore always read the PREVIOUS frame's stale copy, with
    // nothing in graph.toml warning the pack author. Only a geometry pass (bound during Sodium's own
    // opaque terrain draw, which the pack author already knows runs before finish()) may reference it.

    @Test
    void depthOpaqueOnFullscreenPassRejected() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                GraphValidatorTestSupport::validateFullscreenPassWithDepthOpaqueInput);
        assertTrue(e.reason().contains("depth_opaque"), e.reason());
        assertTrue(e.reason().toLowerCase().contains("geometry"), e.reason());
    }

    @Test
    void depthOpaqueOnGeometryPassStillAccepted() {
        // Same builtin, GEOMETRY pass type -- the one case the new rule must NOT reject (already
        // covered by geometryInputBuiltinDepthOpaqueAccepted above; restated here right next to the
        // rejection test so the type-gating boundary is visible in one place).
        assertDoesNotThrow(() -> GraphValidatorTestSupport.validateTerrainWithInputs("builtin.depth_opaque"));
    }

    // --- Water-surface builtins (Deferred Water Task 2): written at the opaque stage HEAD, before
    // Sodium's own SOLID/CUTOUT draws -- unlike builtin.depth_opaque (geometry-only, mid-finish()),
    // these carry no PassType restriction and are already final for a geometry pass's own inputs.

    @Test
    void geometryInputWaterBuiltinsAccepted() {
        assertDoesNotThrow(() -> GraphValidatorTestSupport.validateTerrainWithInputs(
                "builtin.waterNormal", "builtin.waterDepth"));
    }

    // --- Reserved-slot overflow: GeometryInputs.RESERVED (8) is a fixed, process-wide bind-group
    // shape (see that class's own doc) -- a pack declaring more geometry inputs than that has nowhere
    // to bind the overflow and must be refused at load, not silently truncated at runtime.

    @Test
    void tooManyGeometryInputsRejected() {
        FornaxPackError e = assertThrows(FornaxPackError.class,
                GraphValidatorTestSupport::validateTerrainWithTooManyInputs);
        assertTrue(e.reason().toLowerCase().contains("reserved")
                || e.reason().contains(Integer.toString(GeometryInputs.RESERVED)), e.reason());
    }
}
