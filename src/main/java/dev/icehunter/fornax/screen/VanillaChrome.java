package dev.icehunter.fornax.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The exact vanilla button-sprite chrome {@code net.minecraft.client.gui.components.AbstractButton}
 * and YACL's own {@code ControllerWidget}/{@code TickBoxController} draw through --
 * {@code widget/button} / {@code widget/button_disabled} / {@code widget/button_highlighted}, blitted
 * (nine-sliced by {@link GuiGraphicsExtractor#blitSprite}) across a widget's full bounds so hover and
 * disabled states read identically to native rows and buttons.
 *
 * <p>Opt-in only, via {@link PackRow#useVanillaChrome()} and {@link PackButton#vanillaChrome()} --
 * PackRow/PackButton's default translucent-fill look, shared with the legacy {@code
 * PackSettingsScreen}, is untouched by this class's existence.
 */
final class VanillaChrome {
    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");
    private static final Identifier BUTTON_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted");

    private VanillaChrome() {
    }

    /** Blits the same three-state button sprite vanilla's own widgets pick between. */
    static void drawButton(GuiGraphicsExtractor g, int x, int y, int width, int height, boolean active, boolean hovered) {
        Identifier sprite = !active ? BUTTON_DISABLED : (hovered ? BUTTON_HIGHLIGHTED : BUTTON);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
    }
}
