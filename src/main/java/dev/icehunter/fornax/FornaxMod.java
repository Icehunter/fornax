package dev.icehunter.fornax;

import dev.icehunter.fornax.atlas.LabPbrAtlasDiskCache;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.debug.FornaxDebugKeys;
import dev.icehunter.fornax.metalfx.MetalFxSupport;
import dev.icehunter.fornax.metalfx.MetalHudControl;
import dev.icehunter.fornax.pack.PackReload;
import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import dev.icehunter.fornax.pack.material.MaterialResolution;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import dev.icehunter.fornax.pipeline.PersistentPipelineCache;
import dev.icehunter.fornax.profile.ProfilerOverlay;
import dev.icehunter.fornax.util.RendererReload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FornaxMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("fornax");

    /** Matches {@code fabric.mod.json}'s {@code id}; used to find this mod's own jar resources. */
    public static final String MOD_ID = "fornax";

    // Placeholder dimensions for the boot-time GraphValidator VRAM report only -- real target
    // allocation is always lazy (see GraphRunner.prepare()/ensureRunnersBuilt()), driven by the
    // window's actual size once a GPU device exists.
    private static final int BOOTSTRAP_WIDTH = 1920;
    private static final int BOOTSTRAP_HEIGHT = 1080;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Fornax] initialized");
        FornaxConfig.load();
        FornaxKeybind.register();
        FornaxDebugKeys.register();
        ProfilerOverlay.register();

        // Fabric fires this entrypoint from INSIDE Minecraft's constructor (after the singleton
        // instance is assigned, before its final fields exist), so nothing here may touch the
        // half-constructed client. RuntimeShaderPack.reload() gates its reloadResourcePacks() call
        // on this signal -- the client's own initial resource load already picks up the bootstrap
        // sources, so only later live pack switches need an explicit reload.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            RuntimeShaderPack.markClientStarted();
            // MetalFX spike M0 acceptance signal: one INFO line with the machine-level probe
            // verdict. Deferred to CLIENT_STARTED (not this half-constructed-client entrypoint)
            // so the first Metal call happens after the game is fully up; the probe itself is
            // platform-guarded and degrades to a log line on any failure.
            MetalFxSupport.logProbe();
            // Metal Performance HUD: apply the persisted setting once at startup, same deferral
            // reasoning as the probe above -- the window (and its CAMetalLayer) exists by
            // CLIENT_STARTED. Gated on the config actually being true: the layer's HUD starts off
            // by default (nothing to apply for the common default-off case), and apply(false) still
            // does real ObjC work (window/layer resolution, a respondsToSelector: probe) to reach
            // that no-op conclusion -- worth skipping entirely rather than paying it on every launch.
            if (FornaxConfig.get().metalHud) {
                MetalHudControl.apply(true);
            }
        });

        // Final flush of the persistent pipeline cache: PackReload.reload() already persists it at
        // the start of every later reload (so an F8 tuning session isn't lost to a force-kill), but
        // a normal quit never triggers another reload -- this is what catches that session's own
        // gains. Best-effort: PersistentPipelineCache.persist()/destroy() never throw.
        //
        // LabPbrAtlasDiskCache.shutdown() waits briefly for any labPBR disk-cache write still in
        // flight (writeAsync() runs on its own background thread) so a quit right after a fresh
        // build doesn't race a half-written temp file -- harmless either way (the target is only
        // ever replaced by the writer's own atomic move), just tidier.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            PersistentPipelineCache.persist();
            PersistentPipelineCache.destroy();
            ComputeShaderCompiler.shutdown();
            LabPbrAtlasDiskCache.shutdown();
        });

        // Tag ids in blocks.toml categories (the "#c:storage_blocks/iron" kind) don't resolve until
        // a world's datapack tags are actually bound, which happens well after this entrypoint runs
        // -- and can happen again on a datapack reload with no pack change of its own. Re-run the
        // resolver against whatever pack GraphRunner reports active whenever that fires. Terrain
        // meshed before this fires already baked in material id 0 for any tag-only category, so also
        // request a terrain rebuild -- unlike the ShaderPacksScreen apply
        // path, nothing else triggers one here.
        CommonLifecycleEvents.TAGS_LOADED.register((registryAccess, client) -> {
            MaterialResolution.refresh();
            RendererReload.request();
        });

        loadConfiguredPack();
    }

    /**
     * Loads whichever pack {@code FornaxConfig.get().activePack} names -- empty by default, which
     * is a no-op -- from {@code shaderpacks/} at mod init, mirroring {@code
     * ShaderPacksScreen#selectPack}'s own activation flow -- the actual discover/load/rebuild logic
     * lives in {@link PackReload#reload}, shared with the settings screen's live reapply path. Runs
     * before the constructor builds the real {@code PackRepository} entry that opens {@code
     * RuntimeShaderPack.getInstance()} (see {@code MinecraftPackRepositoryMixin}), so the pack's
     * rewritten sources are served by the client's initial resource load with no reload needed --
     * the real window doesn't exist yet at this point (Fabric fires this entrypoint from inside
     * {@code Minecraft}'s own constructor), hence the fixed bootstrap dimensions rather than a live
     * window size.
     */
    private static void loadConfiguredPack() {
        PackReload.reload(BOOTSTRAP_WIDTH, BOOTSTRAP_HEIGHT);
    }
}
