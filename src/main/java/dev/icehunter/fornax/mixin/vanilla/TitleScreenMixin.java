package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.screen.FornaxSettingsScreen;
import dev.icehunter.fornax.screen.IconRowPlacement;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the same Fornax icon button {@code PauseScreenMixin} puts on the pause menu to the TITLE
 * screen, so the settings are reachable without loading a world. Engine settings apply with no
 * level loaded (they persist config and flip latches whose reload paths no-op until something
 * renders), and the Shader Packs screen has always worked from the title.
 *
 * <p><b>Placed beside vanilla's small-icon row</b> rather than in a fixed corner. That row holds
 * the skin, language and accessibility buttons, and it is where a player already looks for this
 * kind of control; the previous top-right corner was somewhere nobody thinks to check.
 *
 * <p>{@link IconRowPlacement} finds that row rather than assuming where it is, and explains why.
 * When it finds none, this falls back to the old top-right corner.
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {
    private static final int BUTTON_SIZE = IconRowPlacement.BUTTON_SIZE;
    private static final int MARGIN = 4;
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("fornax", "fornax_button");

    @Inject(method = "init", at = @At("TAIL"))
    private void fornax$addSettingsButton(CallbackInfo ci) {
        ScreenAccessor accessor = (ScreenAccessor) (Object) this;
        TitleScreen self = (TitleScreen) (Object) this;

        SpriteIconButton button = SpriteIconButton.builder(
                        Component.translatable("gui.fornax.pause_button"),
                        b -> accessor.fornax$getMinecraft().gui.setScreen(FornaxSettingsScreen.create(self)),
                        true)
                .size(BUTTON_SIZE, BUTTON_SIZE)
                .sprite(ICON, 16, 16)
                .tooltip(Component.translatable("gui.fornax.pause_button.tooltip"))
                .build();

        int[] beside = IconRowPlacement.rightOfIconRow(self);
        if (beside != null) {
            button.setPosition(beside[0], beside[1]);
        } else {
            button.setPosition(accessor.fornax$getWidth() - BUTTON_SIZE - MARGIN, MARGIN);
        }
        accessor.fornax$addRenderableWidget(button);
    }
}
