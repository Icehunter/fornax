package dev.icehunter.fornax.mixin.vanilla;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code Screen}'s {@code width}/{@code height}/{@code minecraft} fields and its {@code
 * addRenderableWidget} method -- all declared on {@code Screen} itself, never redeclared on any
 * subclass -- for {@link PauseScreenMixin} (which targets {@code PauseScreen}, a subclass) to use.
 * Mixin cannot {@code @Shadow} a member that's only inherited, not declared directly on the mixin's
 * own target class (the same constraint {@code sodium.DrawContextInvoker}'s doc comment documents
 * for {@code VKDrawContext}/{@code DrawContext}), so this accessor/invoker pair targets {@code
 * Screen.class} directly instead, letting any mixin on any {@code Screen} subclass reach these
 * members via a cast.
 */
@Mixin(Screen.class)
public interface ScreenAccessor {
    @Accessor("width")
    int fornax$getWidth();

    @Accessor("height")
    int fornax$getHeight();

    @Accessor("minecraft")
    Minecraft fornax$getMinecraft();

    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T fornax$addRenderableWidget(T widget);
}
