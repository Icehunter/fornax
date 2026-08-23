package dev.icehunter.fornax.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BooleanSupplier;

/**
 * Translucent flat button matching {@link PackRow}'s look -- dark translucent fill, thin light
 * border on hover/focus, centered label, no vanilla button texture. Used for everything clickable
 * on Fornax's own screens that isn't a settings row (bottom bars, category grid links, Back,
 * Reset), so the screens read as one consistent translucent surface instead of mixing opaque
 * vanilla chrome into the rows.
 */
final class PackButton extends AbstractWidget {
    private static final int FILL = 0x90000000;
    private static final int FILL_HOVERED = 0x903A3A3A;
    private static final int BORDER_HOVERED = 0xFFD0D0D0;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR_INACTIVE = 0xFF808080;
    /** Vanilla {@code AbstractWidget.inactiveColor} (-6250336 in ARGB int form) -- used only on the
     * {@link #vanillaChrome} path, so the disabled-button text tone matches vanilla exactly instead
     * of this class's own (slightly darker) {@link #TEXT_COLOR_INACTIVE}. */
    private static final int TEXT_COLOR_INACTIVE_VANILLA = 0xFFA0A0A0;

    private final Runnable onPress;

    /** When set, drives {@link #active} from live state every frame (footer enablement that changes
     * as the user stages edits, without rebuilding the tab's widget graph). */
    private BooleanSupplier activeSupplier;

    /** Opt-in only (see {@link #vanillaChrome()}): draws genuine vanilla button-sprite chrome ({@link
     * VanillaChrome}) instead of the translucent fill -- the Shader Packs tab's footer buttons only.
     * Default false leaves every other {@code PackButton} caller ({@code PackSettingsScreen}'s
     * Reset/Export/Import/Cancel/Apply/Done/Back/category-link buttons) byte-identical. */
    private boolean vanillaChrome;

    /** Opt-in only (see {@link #icon}): a small square vanilla-chrome button (the Shader Packs tab's
     * per-row settings-cog accessory) draws this GUI sprite centered on the button face instead of
     * {@link #getMessage()}'s text -- the message is kept only for narration/tooltip. Every other
     * {@code PackButton} caller leaves this {@code null} and renders text exactly as before. */
    private Identifier icon;
    private int iconSize;

    PackButton(int x, int y, int width, int height, String label, Runnable onPress) {
        super(x, y, width, height, Component.literal(label));
        this.onPress = onPress;
    }

    PackButton withTooltip(String tooltip) {
        this.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return this;
    }

    PackButton withActiveSupplier(BooleanSupplier supplier) {
        this.activeSupplier = supplier;
        return this;
    }

    /** Opts this button into vanilla button-sprite chrome (see {@link #vanillaChrome}). */
    PackButton vanillaChrome() {
        this.vanillaChrome = true;
        return this;
    }

    /** Draws {@code icon} (a GUI sprite identifier, {@code iconSize} square) centered on the button
     * face instead of the text label -- only meaningful combined with {@link #vanillaChrome()}. */
    PackButton icon(Identifier icon, int iconSize) {
        this.icon = icon;
        this.iconSize = iconSize;
        return this;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubled) {
        this.onPress.run();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (this.activeSupplier != null) {
            this.active = this.activeSupplier.getAsBoolean();
        }
        int x0 = this.getX();
        int y0 = this.getY();
        int x1 = x0 + this.width;
        int y1 = y0 + this.height;
        Font font = Minecraft.getInstance().font;

        if (this.vanillaChrome) {
            VanillaChrome.drawButton(g, x0, y0, this.width, this.height, this.active, this.active && this.isHoveredOrFocused());
            if (this.icon != null) {
                int ix = x0 + (this.width - this.iconSize) / 2;
                int iy = y0 + (this.height - this.iconSize) / 2;
                g.blitSprite(RenderPipelines.GUI_TEXTURED, this.icon, ix, iy, this.iconSize, this.iconSize);
            } else {
                g.centeredText(font, this.getMessage(), (x0 + x1) / 2, y0 + (this.height - 9) / 2 + 1,
                        this.active ? TEXT_COLOR : TEXT_COLOR_INACTIVE_VANILLA);
            }
            return;
        }

        boolean highlighted = this.active && this.isHoveredOrFocused();
        g.fill(x0, y0, x1, y1, highlighted ? FILL_HOVERED : FILL);
        if (highlighted) {
            g.fill(x0, y0, x1, y0 + 1, BORDER_HOVERED);
            g.fill(x0, y1 - 1, x1, y1, BORDER_HOVERED);
            g.fill(x0, y0, x0 + 1, y1, BORDER_HOVERED);
            g.fill(x1 - 1, y0, x1, y1, BORDER_HOVERED);
        }

        g.centeredText(font, this.getMessage(), (x0 + x1) / 2, y0 + (this.height - 9) / 2 + 1,
                this.active ? TEXT_COLOR : TEXT_COLOR_INACTIVE);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
