package dev.icehunter.fornax;

import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.metalfx.MetalHudEnv;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Fires before Minecraft's {@code main()}, and therefore before any Vulkan/MoltenVK/Metal
 * initialization: the one point in this mod's lifecycle early enough to set {@code
 * MTL_HUD_ENABLED} natively (see {@link MetalHudEnv} for why that timing is load-bearing, not
 * incidental). {@link FornaxConfig#load()} runs again from the normal {@link
 * FornaxMod#onInitializeClient()} entrypoint later; that call is idempotent, so calling it here
 * first just makes {@code metalHud} readable this much earlier.
 */
public final class FornaxPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        FornaxConfig.load();
        MetalHudEnv.enableIfConfigured();
    }
}
