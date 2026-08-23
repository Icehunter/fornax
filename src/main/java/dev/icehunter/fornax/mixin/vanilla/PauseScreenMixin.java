package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.screen.FornaxSettingsScreen;
import dev.icehunter.fornax.screen.IconRowPlacement;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a compact "Fornax" button to the pause (esc) menu, opening {@link
 * FornaxSettingsScreen#create}. {@code javap}-confirmed against the real, unobfuscated MC 26.2
 * client jar: {@code PauseScreen#init()} lays out its own buttons via a local {@code GridLayout}
 * (never a field), so there is no clean seam to insert a new row into that grid from an external
 * mixin without also depending on private grid-construction details -- the design doc explicitly
 * accepts a lower-risk placement instead ("a compact button beside or under 'Options...' is
 * acceptable v1 -- exact slot is user-tunable later"). This injects at the TAIL of {@code init()}
 * (after the vanilla grid has already added its own widgets) and adds one independently-positioned
 * button in the screen's top-right corner, which can never overlap the vanilla grid (centered
 * around the middle of the screen) regardless of window size.
 *
 * <p>{@code width}/{@code height}/{@code minecraft}/{@code addRenderableWidget} are all inherited
 * from {@code Screen}, not declared on {@code PauseScreen} itself, so this mixin reaches them via
 * {@link ScreenAccessor} rather than {@code @Shadow} (which cannot resolve inherited-only members
 * here -- see that interface's own doc comment).
 */
@Mixin(PauseScreen.class)
public class PauseScreenMixin {
    private static final int BUTTON_SIZE = 20;
    private static final int MARGIN = 4;
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("fornax", "fornax_button");

    @Inject(method = "init", at = @At("TAIL"))
    private void fornax$addSettingsButton(CallbackInfo ci) {
        ScreenAccessor accessor = (ScreenAccessor) (Object) this;
        PauseScreen self = (PauseScreen) (Object) this;

        // Icon button (user request): 20x20 square with the mod icon as a GUI sprite
        // (assets/fornax/textures/gui/sprites/fornax_button.png, auto-stitched into the gui
        // atlas), name/tooltip carried by the builder for narration + hover.
        SpriteIconButton button = SpriteIconButton.builder(
                        Component.translatable("gui.fornax.pause_button"),
                        b -> accessor.fornax$getMinecraft().gui.setScreen(FornaxSettingsScreen.create(self)),
                        true)
                .size(BUTTON_SIZE, BUTTON_SIZE)
                .sprite(ICON, 16, 16)
                .tooltip(Component.translatable("gui.fornax.pause_button.tooltip"))
                .build();
        // Beside vanilla's own icon row (bug report, feedback, skin, and the rest) rather than in
        // the corner. See IconRowPlacement for why the row is found instead of assumed.
        int[] beside = IconRowPlacement.rightOfIconRow(self);
        if (beside != null) {
            button.setPosition(beside[0], beside[1]);
        } else {
            button.setPosition(accessor.fornax$getWidth() - BUTTON_SIZE - MARGIN, MARGIN);
        }
        accessor.fornax$addRenderableWidget(button);
    }
}
