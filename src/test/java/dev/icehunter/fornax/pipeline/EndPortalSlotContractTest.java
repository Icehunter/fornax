package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.icehunter.fornax.pack.GeometrySlot;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link GeometrySlot#END_PORTAL} is allowed to carry.
 *
 * <p>An unmapped portal draws into vanilla's target and the graph's tonemap paints over it, leaving
 * a portal you can see through. Claiming the wrong slot is also how this tree twice deleted the
 * player and every humanoid mob, so the mapping is pinned both ways here.
 */
public class EndPortalSlotContractTest {
    /** Exactly two vanilla pipelines may land in this slot, and no others. */
    @Test
    void onlyThePortalAndTheGatewayClaimThisSlot() throws Exception {
        List<String> claimants = new ArrayList<>();
        for (Field field : RenderPipelines.class.getDeclaredFields()) {
            if (!RenderPipeline.class.equals(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            if (GeometryPipelineMap.slotOf((RenderPipeline) field.get(null)) == GeometrySlot.END_PORTAL) {
                claimants.add(field.getName());
            }
        }
        claimants.sort(String::compareTo);
        assertEquals(List.of("END_GATEWAY", "END_PORTAL"), claimants,
                "This slot may carry the portal and gateway draws and nothing else. Anything new"
                        + " here draws through the pack's portal program, which reads a"
                        + " position-only vertex format, and would come out wrong or invisible.");
    }

    /**
     * The surface sits inside a frame that already casts, and it gives off light rather than
     * blocking it. Enlisting it costs a shadow replay and a compiled variant for nothing.
     */
    @Test
    void thePortalCastsNoShadow() {
        assertFalse(GeometrySlot.END_PORTAL.castsShadow());
    }

    /**
     * Deferred, not forward. The draw is opaque, writes depth and lands in the solid phase. A
     * forward claim would keep vanilla's one colour target and hand the program the wrong space.
     */
    @Test
    void thePortalIsDeferredAndRendered() {
        assertFalse(GeometrySlot.END_PORTAL.rendersForward());
        assertTrue(GeometrySlot.END_PORTAL.isRendered());
        assertTrue(GeometryPipelineMap.isMapped(GeometrySlot.END_PORTAL));
    }
}
