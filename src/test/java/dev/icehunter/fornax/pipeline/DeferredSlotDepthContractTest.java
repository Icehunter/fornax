package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.icehunter.fornax.pack.GeometrySlot;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every pipeline {@link GeometryPipelineMap} claims must write depth.
 *
 * <p>A claimed slot renders into the G-buffer, and the resolve reconstructs each pixel's world
 * position from the depth buffer. Geometry that writes colour but not depth leaves nothing to
 * reconstruct from, so its G-buffer contents get lit using whatever surface is behind it -- shading
 * that looks merely a bit wrong rather than obviously broken, which is the hardest kind to trace.
 *
 * <p>Vanilla's overlay pipelines (glint, crumbling, spider eyes, the translucent beacon glow) all read
 * depth without writing it, and several sky pipelines carry no depth state at all. Every one of them is
 * a plausible-looking addition to the map, which is why this is a test rather than a comment.
 */
public class DeferredSlotDepthContractTest {
    @Test
    void everyMappedPipelineWritesDepth() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Field field : RenderPipelines.class.getDeclaredFields()) {
            if (!RenderPipeline.class.equals(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            RenderPipeline pipeline = (RenderPipeline) field.get(null);
            GeometrySlot slot = GeometryPipelineMap.slotOf(pipeline);
            if (slot == null) {
                continue;
            }
            var depthState = pipeline.getDepthStencilState();
            if (depthState == null) {
                offenders.add(field.getName() + " -> '" + slot.token() + "' has no depth state at all");
            } else if (!depthState.writeDepth()) {
                offenders.add(field.getName() + " -> '" + slot.token() + "' does not write depth");
            }
        }
        assertTrue(offenders.isEmpty(),
                "Pipelines mapped to a geometry slot must write depth, or the resolve will light them"
                        + " using the geometry behind them:\n  " + String.join("\n  ", offenders));
    }

    /**
     * Pins which slots are claimable, in both directions. Listed explicitly so that dropping a mapping
     * or quietly adding one to a documented exclusion is a deliberate edit to this test rather than a
     * change nothing notices.
     */
    @Test
    void claimableSlotsMatchWhatIsDocumented() {
        // Terrain routes through Sodium, not through a vanilla RenderPipeline. The rest either cannot
        // write depth or have no single pipeline that identifies them: the hand and held items share
        // ITEM_CUTOUT with dropped items on the ground, and nothing in the pipeline alone tells them
        // apart -- that distinction rides on the submit node, which this table cannot see.
        for (GeometrySlot slot : new GeometrySlot[]{
                GeometrySlot.TERRAIN, GeometrySlot.SPIDER_EYES, GeometrySlot.DAMAGED_BLOCK,
                GeometrySlot.ARMOR_GLINT, GeometrySlot.SKY_BASIC, GeometrySlot.SKY_TEXTURED,
                GeometrySlot.HAND, GeometrySlot.HAND_TRANSLUCENT, GeometrySlot.ENTITIES_GLOWING,
                GeometrySlot.SHADOW, GeometrySlot.SHADOW_ENTITIES}) {
            assertTrue(!GeometryPipelineMap.isMapped(slot),
                    "slot '" + slot.token() + "' is mapped, but is documented as unmappable");
        }

        for (GeometrySlot slot : new GeometrySlot[]{
                GeometrySlot.ENTITIES, GeometrySlot.ENTITIES_TRANSLUCENT, GeometrySlot.BLOCK_ENTITIES,
                GeometrySlot.BLOCK_ENTITIES_TRANSLUCENT, GeometrySlot.PARTICLES, GeometrySlot.WEATHER,
                GeometrySlot.BEACON_BEAM, GeometrySlot.LIGHTNING, GeometrySlot.CLOUDS,
                GeometrySlot.LINES}) {
            assertTrue(GeometryPipelineMap.isMapped(slot),
                    "slot '" + slot.token() + "' should have at least one vanilla pipeline mapped to it");
        }
    }

    /**
     * {@link ForwardPipelineMap}'s invariant is the INVERSE of the one above: everything it holds must
     * be a pipeline that CANNOT be deferred, and the two tables must be disjoint.
     *
     * <p>Disjointness is what keeps {@code GeometryPipelineMap.slotOf(...) != null} meaning exactly
     * "defer this draw". Four call sites read it that way -- the render-pass wrapper, the pipeline
     * wrapper, the shadow-cast decision and the shader source substitution -- and a pipeline in both
     * tables would be given a five-attachment variant AND have vanilla's one-attachment pass kept, in
     * the same draw. That is a validation error rather than a wrong-looking banner.
     */
    @Test
    void everyForwardMappedPipelineIsOneThatCouldNotHaveBeenDeferred() throws Exception {
        List<String> notForward = new ArrayList<>();
        List<String> inBothTables = new ArrayList<>();
        for (Field field : RenderPipelines.class.getDeclaredFields()) {
            if (!RenderPipeline.class.equals(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            RenderPipeline pipeline = (RenderPipeline) field.get(null);
            GeometrySlot slot = ForwardPipelineMap.slotOf(pipeline);
            if (slot == null) {
                continue;
            }
            if (GeometryPipelineMap.slotOf(pipeline) != null) {
                inBothTables.add(field.getName());
            }
            if (!slot.rendersForward()) {
                notForward.add(field.getName() + " -> '" + slot.token() + "'");
            }
            // The inverse of everyMappedPipelineWritesDepth. A depth-writing pipeline in this table is
            // not automatically wrong -- draw ORDER alone is disqualifying, and that is not visible
            // from the pipeline -- so this is not asserted. It is asserted for banner patterns
            // specifically below, where the fact IS a property of the pipeline.
        }
        assertTrue(inBothTables.isEmpty(),
                "these pipelines are mapped as BOTH deferred and forward, which makes"
                        + " GeometryPipelineMap.slotOf() stop meaning 'defer this': " + inBothTables);
        assertTrue(notForward.isEmpty(),
                "ForwardPipelineMap maps these to slots whose rendersForward() is false, so the draw"
                        + " site and the pipeline builder would disagree about what to build: " + notForward);
    }

    /**
     * Banner patterns specifically: the slot exists because this pipeline does not write depth, so if
     * vanilla ever gives it a depth write the slot's whole justification has changed and it should be
     * reconsidered rather than silently kept.
     */
    @Test
    void bannerPatternsIsForwardBecauseItsPipelineWritesNoDepth() {
        assertTrue(ForwardPipelineMap.isMapped(GeometrySlot.BANNER_PATTERNS),
                "banner_patterns has no pipeline mapped to it, so the slot draws nothing");
        assertTrue(!GeometryPipelineMap.isMapped(GeometrySlot.BANNER_PATTERNS),
                "banner_patterns is a FORWARD slot and must never appear in the deferred map");
        var depth = RenderPipelines.BANNER_PATTERN.getDepthStencilState();
        assertTrue(depth != null && !depth.writeDepth(),
                "BANNER_PATTERN now writes depth. That was half the reason it could not be deferred"
                        + " (the other half -- it draws after the graph resolves -- still stands), so"
                        + " revisit the slot rather than leaving this test updated in passing.");
        assertTrue(RenderPipelines.BANNER_PATTERN.getColorTargetState().blendFunction().isPresent(),
                "BANNER_PATTERN no longer blends. The forward variant copies its colour target states"
                        + " verbatim precisely so the blend cannot be dropped; if vanilla has dropped"
                        + " it, the pattern layers are now opaque rectangles by vanilla's own choice.");
    }
}
