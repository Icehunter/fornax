package dev.icehunter.fornax;

import com.mojang.blaze3d.platform.InputConstants;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.profile.ProfilerOverlay;
import dev.icehunter.fornax.screen.FornaxSettingsScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Registers Fornax's keybinds (all unbound by default) and their tick handler: opening the
 * YACL-hosted {@code FornaxSettingsScreen} (whose Shader Packs tab hosts pack selection), toggling
 * the frame profiler HUD, and dumping a full profiler breakdown to the log.
 */
public final class FornaxKeybind {
    /** Shared "Fornax" key-mapping category -- {@code dev.icehunter.fornax.debug.FornaxDebugKeys}
     * registers its own binds under this same category so both groups list together on the
     * Controls screen, rather than opening a second competing "Fornax" category. */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("fornax", "keys"));

    private FornaxKeybind() {
    }

    public static void register() {
        KeyMapping openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.open_settings", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));
        KeyMapping toggleProfiler = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.toggle_profiler", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));
        KeyMapping dumpProfiler = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fornax.dump_profiler", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openSettings.consumeClick()) {
                client.gui.setScreen(FornaxSettingsScreen.create(client.gui.screen()));
            }
            while (toggleProfiler.consumeClick()) {
                FornaxConfig.get().profilerOverlay = !FornaxConfig.get().profilerOverlay;
                FornaxConfig.save();
            }
            while (dumpProfiler.consumeClick()) {
                ProfilerOverlay.dumpToLog();
            }
        });
    }
}
