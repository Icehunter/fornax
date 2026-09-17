package dev.icehunter.fornax.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The view that makes the cascade visible: which tier answered each celestial texel. */
class RayTierMapViewTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of("src/main/", path));
    }

    /** A view with no presenter hooked is a menu entry that shows the previous frame. */
    @Test
    void theViewIsSelectableAndHasAPresenterAndAName() throws IOException {
        assertTrue(GBufferDebugView.RAY_TIER_MAP.isSelectable());
        assertEquals("Ray tier map", GBufferDebugView.RAY_TIER_MAP.label());

        String presenter = read("java/dev/icehunter/fornax/pass/debug/MetalRtDebugPass.java");
        assertTrue(presenter.contains("view == GBufferDebugView.RAY_TIER_MAP"),
                "the view must be blitted by a presenter, or selecting it shows whatever was there");
        assertTrue(presenter.contains("TerrainShadowResult.view()"),
                "it reads the cascade's own image");
        assertTrue(presenter.contains("|| view == GBufferDebugView.RAY_TIER_MAP"),
                "and must count as wanted, or the RT path may not run while it is selected");
    }

    /**
     * Validity before tier, in the shader as everywhere else. An untraced target is all zeros and
     * zero is a legal value for both the answer and the tier, so painting a colour from G without
     * testing A first would show tier NONE as a real answer across the whole map.
     */
    @Test
    void theBlitTestsValidityBeforeItReadsTheTier() throws IOException {
        String shader = read("resources/assets/fornax/shaders/post/ray_tier_debug_blit.fsh");
        int validity = shader.indexOf("if (answer.a <= 0.5)");
        int tier = shader.indexOf("int tier = int(answer.g + 0.5);");
        assertTrue(validity > 0 && tier > validity,
                "A decides whether anything else in the texel means anything");
    }

    /** One colour per tier, and a colour that cannot be mistaken for one when the two words disagree. */
    @Test
    void everyTierHasItsOwnColourAndAnImpossibleStateIsLoud() throws IOException {
        String shader = read("resources/assets/fornax/shaders/post/ray_tier_debug_blit.fsh");
        for (String tier : new String[]{"tier == 3", "tier == 2", "tier == 1"}) {
            assertTrue(shader.contains(tier), tier + " must have its own colour");
        }
        assertTrue(shader.contains("vec3(1.0, 0.0, 1.0)"),
                "validity set with no tier breaks the engine's own one-store law; it must be loud");
    }
}
