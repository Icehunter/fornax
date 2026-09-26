package dev.icehunter.fornax.mixin.vanilla;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of the player-mirror render pass must agree on attachment order, the same rule
 * {@code GBufferFormatLockTest} already pins for the main G-buffer. {@code
 * DeferredGeometryPipelines.buildMirror} declares {@code ColorTargetState}s at locations 0, 1, and
 * 2, and {@code PreparedRenderTypeDeferredMixin}'s render-pass descriptor binds attachments at the
 * same positions through repeated {@code withColorAttachment} calls. Location and bind order have
 * no shared enum forcing them to agree, so reordering either side swaps channels silently, for
 * example normal data landing in the albedo attachment, with no error anywhere.
 *
 * <p>This test covers all three mirror slots at once, by construction. {@code buildMirror} takes a
 * {@code GeometrySlot} parameter, and the render-pass branch reads {@code
 * DeferredGeometryPipelines.activeMirrorSlot()}, rather than using three separately written
 * branches. There is one {@code buildMirror} body and one render-pass branch, both parameterized
 * by slot, so this test's single check is the per-slot guarantee: it cannot pass for the floor
 * while failing silently for a wall, because there is no separate wall code path to diverge from
 * it.
 */
class MirrorAttachmentOrderContractTest {

    @Test
    void pipelineLocationsAndRenderPassAttachmentsAgreeOnOrder() throws IOException {
        String pipelineSource = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/pipeline/DeferredGeometryPipelines.java"));
        String mixinSource = Files.readString(Path.of(
                "src/main/java/dev/icehunter/fornax/mixin/vanilla/PreparedRenderTypeDeferredMixin.java"));

        int buildMirrorStart = pipelineSource.indexOf("private static RenderPipeline buildMirror(");
        assertTrue(buildMirrorStart >= 0, "buildMirror must exist");
        String buildMirrorBody = pipelineSource.substring(buildMirrorStart,
                pipelineSource.indexOf("\n    }\n", buildMirrorStart));

        List<String> pipelineOrder = List.of(
                formatConstantAfter(buildMirrorBody, "withColorTargetState(0,"),
                formatConstantAfter(buildMirrorBody, "withColorTargetState(1,"),
                formatConstantAfter(buildMirrorBody, "withColorTargetState(2,"));
        assertEquals(List.of("NORMAL_FORMAT", "ALBEDO_FORMAT", "MATERIAL_FORMAT"), pipelineOrder,
                "buildMirror's own attachment locations 0/1/2 must be normal, albedo, material in order");

        // The guard reads the active slot rather than a plain boolean, so one branch covers all
        // three mirror slots; see the class comment above. The marker below is that guard's
        // declaration line.
        int branchStart = mixinSource.indexOf(
                "GeometrySlot renderPassMirrorSlot = DeferredGeometryPipelines.activeMirrorSlot();");
        assertTrue(branchStart >= 0, "the mirror render-pass branch's own active-slot guard must exist");
        int branchEnd = mixinSource.indexOf(".withRenderArea(", branchStart);
        assertTrue(branchEnd >= 0, "the mirror descriptor must set a render area");
        String branchBody = mixinSource.substring(branchStart, branchEnd);

        assertEquals(List.of("mirrorNormal", "mirrorAlbedo", "mirrorMaterial"), colorAttachmentArgs(branchBody),
                "the render pass must bind mirrorNormal, mirrorAlbedo, mirrorMaterial in that order,"
                        + " to agree with buildMirror's own location 0/1/2 declarations above");
    }

    private static String formatConstantAfter(String body, String marker) {
        int at = body.indexOf(marker);
        assertTrue(at >= 0, "expected to find " + marker);
        int formatEnd = body.indexOf("_FORMAT", at) + "_FORMAT".length();
        assertTrue(formatEnd >= "_FORMAT".length(), "expected a *_FORMAT constant after " + marker);
        int nameStart = body.lastIndexOf("GBufferManager.", formatEnd) + "GBufferManager.".length();
        return body.substring(nameStart, formatEnd);
    }

    private static List<String> colorAttachmentArgs(String body) {
        List<String> args = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = body.indexOf(".withColorAttachment(", from);
            if (at < 0) break;
            int start = at + ".withColorAttachment(".length();
            int comma = body.indexOf(",", start);
            args.add(body.substring(start, comma).trim());
            from = comma;
        }
        return args;
    }
}
