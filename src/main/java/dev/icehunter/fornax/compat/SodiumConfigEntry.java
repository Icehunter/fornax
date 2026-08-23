package dev.icehunter.fornax.compat;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.screen.FornaxSettingsScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ExternalPageBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Registers Fornax as a first-class entry in Sodium 0.9's own video-settings screen via its
 * official third-party config API -- the {@code sodium:config_api_user} Fabric entrypoint declared
 * in fabric.mod.json, whose classes {@code ConfigLoaderFabric.collectConfigEntryPoints()} hands to
 * {@code ConfigManager} at client init (javap-confirmed against the real sodium-fabric-0.9.0 jar).
 * Fornax appears in the mod list on the left of Sodium's screen with a single "Fornax Settings..."
 * external page that opens the YACL-hosted {@link FornaxSettingsScreen}, receiving the
 * video-settings screen itself as parent so Done returns there. There is deliberately no ModMenu
 * entry and no separate standalone settings screen (both retired; Fornax depends on Sodium, so
 * this page always exists), leaving just the keybind, which opens {@link FornaxSettingsScreen}
 * directly.
 *
 * <p>Only ever loaded by Sodium's entrypoint lookup -- absent Sodium, nothing resolves the
 * entrypoint and this class is never touched (and the mod doesn't run anyway; see fabric.mod.json's
 * hard {@code sodium} dependency).
 */
public final class SodiumConfigEntry implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        try {
            registerEntries(builder);
        } catch (Throwable t) {
            // A future Sodium API change here must not take down the whole video-settings screen --
            // degrade to Fornax's own keybind (which opens the same YACL settings screen) instead.
            FornaxMod.LOGGER.warn("[Fornax] Failed to register Sodium video-settings entry", t);
        }
    }

    private static void registerEntries(ConfigBuilder builder) {
        ExternalPageBuilder settingsPage = builder.createExternalPage()
                .setName(Component.literal("Fornax Settings..."))
                .setScreenConsumer(videoSettings ->
                        Minecraft.getInstance().gui.setScreen(FornaxSettingsScreen.create(videoSettings)));

        builder.registerOwnModOptions()
                .setName("Fornax")
                // Full-color logo -> non-tinted variant (setIcon gets theme-tinted, for monochrome
                // glyphs). Icons resolve via TextureManager as plain texture paths, no atlas sprite.
                .setNonTintedIcon(Identifier.fromNamespaceAndPath("fornax", "textures/gui/config-icon.png"))
                .addPage(settingsPage);

        FornaxMod.LOGGER.info("[Fornax] Registered Sodium video-settings entry (Fornax Settings page)");
    }
}
