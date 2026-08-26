package dev.icehunter.fornax.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Non-interactive, genuinely CENTERED title text for Fornax's screens. Replaces vanilla
 * {@code StringWidget} in the headers: that widget renders its text from its own left edge, so a
 * full-width title overlaps the Back button top-left (e.g. "LightingBack" mangled together). This
 * one centers within its bounds unconditionally and never takes focus or clicks.
 */
final class TitleWidget extends AbstractWidget {
    TitleWidget(int x, int y, int width, int height, Component title) {
        super(x, y, width, height, title);
        this.active = false;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(Minecraft.getInstance().font, this.getMessage(),
                this.getX() + this.width / 2, this.getY(), 0xFFFFFFFF);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
