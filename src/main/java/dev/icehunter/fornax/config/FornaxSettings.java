package dev.icehunter.fornax.config;

import dev.icehunter.fornax.pass.ssaa.SsaaPreset;

/**
 * Plain GSON-serializable settings POJO for Fornax's rendering engine. PBR/SSAO/TAA/reflection
 * tunables that used to live here moved into the active pack's own runtime/compile options (see
 * {@code dev.icehunter.fornax.pack.option.PackOption} and a pack's {@code screens.toml}). This
 * class now holds only engine-owned settings, independent of whichever pack (if any) is loaded.
 */
public class FornaxSettings {
    /**
     * Master switch: when false, Fornax renders pure vanilla-Sodium (no G-buffer, no pack graph).
     *
     * <p>Off by default. Fornax ships no pack of its own, so a fresh install has nothing to render
     * with, and turning the engine on with no graph loaded would cost the G-buffer allocation for a
     * scene that still resolves to vanilla output. The player turns this on in the same step where
     * they pick a pack.
     */
    public boolean shadersEnabled = false;

    /**
     * Directory/zip name (without .zip) of the active pack under shaderpacks/. Empty means no pack:
     * pure Sodium, which {@code PackReload} treats as a first-class state rather than an error.
     *
     * <p>Empty by default: nothing is preselected, because nothing is bundled. A default naming a
     * pack that may not be installed would log a not-found warning on every fresh launch.
     */
    public String activePack = "";

    /**
     * Selects which raw G-buffer attachment the resolve pass writes to the screen instead of the
     * final lit terrain, for verifying the deferred render targets are populated correctly. {@code
     * OFF} (the default) reproduces the normal lit output. Engine-owned rather than a pack option:
     * it's a debugging aid for the G-buffer itself, independent of any particular pack's content.
     */
    public GBufferDebugView debugView = GBufferDebugView.OFF;

    /**
     * The SSAA FACTOR -- how hard to supersample once {@link #aaMethod} selects {@code SSAA};
     * ignored under every other method ({@code SsaaManager#applyCurrentScale()} gates on the
     * method, so this never activates supersampling by itself). On/off lives on {@link #aaMethod}
     * alone; the UI (Sodium's Engine page) accordingly never offers {@code OFF} here. {@code
     * SsaaPreset.OFF} survives only as a legacy deserialization value -- pre-v2 config files carry
     * it, and {@link #migrate} normalizes it to this default on first load.
     */
    public SsaaPreset ssaaPreset = SsaaPreset.X2;

    /**
     * Shows {@code dev.icehunter.fornax.profile.ProfilerOverlay}'s per-pass GPU timing panel in the
     * top-left corner, graded against the 11.1 ms / 90 FPS budget. Off by default -- it's a
     * diagnostic aid, not something a normal session needs on screen.
     */
    public boolean profilerOverlay = false;

    /**
     * The engine's own AA/upscale method selector -- replaces the pack-owned {@code TAA_ENABLED}
     * compile option (now retired) as the single source of truth for whether/how the frame
     * gets a temporal resolve, and the ONLY on/off switch for supersampling (see {@link
     * #ssaaPreset}). Defaults to {@code TAA} to match the legacy pack default it replaces. The
     * Sodium Engine page's apply hook calls {@code PackReload.reapplyActivePack()} on change (a
     * compile-state change -- see {@code EngineDefines}), never {@code RendererReload}.
     */
    public AaMethod aaMethod = AaMethod.TAA;

    /**
     * TAAU's render-resolution tier once {@link #aaMethod} selects {@code TAAU}; {@code
     * SsaaManager#applyCurrentScale()} applies {@link TaauRatio#perAxisScale()} to the render
     * target, and {@code CameraJitter} independently uses {@link TaauRatio#haltonSequenceLength()}.
     */
    public TaauRatio taauRatio = TaauRatio.BALANCED;

    /**
     * Temporal blend factor, migrated from the retiring pack option {@code u_TaaBlendFactor}. This
     * is the STEADY-STATE history weight, not the whole story: after a reset/disocclusion the
     * reconstruct shader's confidence ramp blends 1/n-style (history weight (n-1)/n over n frames
     * accumulated) and this value is only the cap that ramp saturates at -- at the 0.9 default a
     * static pixel reaches it by frame 10. Raising it deepens steady-state smoothing (and ghosting
     * risk); initial convergence speed is governed by the ramp, not this value.
     */
    public float taaBlendFactor = 0.9f;

    /** Contrast-adaptive sharpen strength the reconstruct pass applies after the temporal blend (TAA and TAAU). */
    public float reconstructSharpen = 0.5f;

    /**
     * Tilt of the sun and moon's daily arc, in degrees -- the equivalent of OptiFine/Iris's
     * {@code sunPathRotation}, which shaderpacks conventionally set somewhere around -40.
     *
     * <p>Vanilla's celestial path lies flat in the XY plane and therefore passes exactly through
     * the zenith, so at noon the sun is directly overhead, shadows collapse to nothing beneath
     * everything, and the whole day reads the same. Tilting the arc keeps the sun off the zenith
     * and gives noon a direction.
     *
     * <p>Applied in {@link dev.icehunter.fornax.util.SunDirection}, which is the single vector the
     * shadow map, the pack's lighting and the celestial discs are all built from -- so they cannot
     * disagree. Tilting it pack-side instead would have rotated the lighting away from the shadows.
     *
     * <p>0 is vanilla. Negative values tilt the arc one way, positive the other; the sign
     * convention matches the shaderpack property it stands in for.
     */
    public float sunPathRotation = -25.0f;

    /**
     * Experimental MetalFX frame generation: interpolates one frame between real frames. Requires
     * {@link #aaMethod} to be {@code METALFX} and vsync (FIFO present mode); adds ~1 frame of
     * latency. macOS 26+ Apple Silicon only.
     */
    public boolean frameGeneration = false;

    /**
     * Apple's Metal Performance HUD overlay ({@code CAMetalLayer.developerHUDProperties}),
     * macOS-only, live-toggled through {@code metalfx.MetalHudControl} (no restart, no pack
     * recompile) -- replaces the session-only {@code MTL_HUD_ENABLED} environment variable with a
     * setting. Independent of {@link #aaMethod}: the HUD renders over whatever the compositor
     * presents regardless of which AA/upscale path is active.
     */
    public boolean metalHud = false;

    /**
     * When true, the emitter-light voxel window's radius ignores the player's live render-distance
     * setting entirely, honoring the pack's {@code u_LightReach} option up to the engine's own VRAM
     * ceiling ({@code VoxelDebugRaymarchPass.RADIUS_CEILING}, 16 sections/256 blocks) regardless of
     * how far vanilla terrain itself is being rendered. Off by default: the coupled behavior keeps
     * the voxel window's VRAM/compute cost bounded to what's already loaded, the safer default for
     * the 1060 3GB floor this project profiles against. Does NOT affect {@code
     * HIGH_DETAIL_RADIUS_CEILING} -- that clamp exists for Light Detail=High's own ~8x memory-per-
     * section cost and stays a hard cap regardless of this setting.
     */
    public boolean voxelReachIgnoresRenderDistance = false;

    /**
     * Gates the multi-page block atlas (M13): a resource pack whose combined sprites exceed the
     * device's single-atlas dimension ceiling spills overflow sprites onto additional {@code
     * dev.icehunter.fornax.atlas.BlockAtlasPaging}-allocated pages instead of the reload aborting
     * with {@code StitcherException}. Read by {@code SpriteLoaderPagedStitchMixin}, which as of
     * Phase 2 runs a measurement-and-log pass only (plans pages, alters nothing). Off by default:
     * with the flag off, or once it does gate something, behavior for a pack that already fit one
     * page must stay bit-for-bit identical to today.
     */
    public boolean pagedBlockAtlasEnabled = false;

    /**
     * How much of a pack's authored labPBR sidecar resolution to keep -- see
     * {@link SidecarMapResolution} for the tiers and for why HALF is the default.
     *
     * <p>Read by {@code NormalMapAtlasReloadListener} and {@code MaterialMapAtlasReloadListener}
     * when they size their atlases, so a change takes effect on the next resource reload. The
     * settings screen triggers one on save.
     */
    public SidecarMapResolution sidecarMapResolution = SidecarMapResolution.HALF;

    /**
     * The schema version this build writes. A fresh file must be stamped with this BEFORE its first
     * save ({@code FornaxConfig.load()}'s no-file branch does so): an unstamped fresh file is
     * indistinguishable from a legacy one, and the next launch's {@link #migrate} would re-derive
     * {@link #aaMethod} from {@link #ssaaPreset} -- silently clobbering whatever the user picked in
     * their first session.
     */
    public static final int CURRENT_SCHEMA_VERSION = 3;

    /**
     * Config-file schema version, bumped by {@link #migrate} the first time a legacy (pre-{@link
     * #aaMethod}) {@code fornax.json} is loaded. Not itself a rendering setting -- purely a
     * migration marker, persisted so migration runs exactly once per config file rather than on
     * every load. Defaults to 0, NOT {@link #CURRENT_SCHEMA_VERSION}: a legacy file
     * with no {@code schemaVersion} key must deserialize as version 0 so {@link #migrate} can
     * recognize it -- which is exactly why the fresh-install save path has to stamp explicitly.
     */
    public int schemaVersion = 0;

    /**
     * Post-deserialization, pre-return migration hook for {@code FornaxConfig.load()}. A legacy
     * {@code fornax.json} (written before {@link #aaMethod} existed) deserializes with {@code
     * schemaVersion} at its Java default of 0 and {@code aaMethod} at ITS Java default ({@code TAA},
     * from this class's field initializer -- Gson leaves any field absent from the JSON at whatever
     * the class already initializes it to) regardless of what the legacy file actually implied, so
     * the real legacy signal is {@code ssaaPreset}, not {@code aaMethod}: a legacy file that had
     * supersampling on implied {@code SSAA}; anything else implied the old always-on {@code TAA}.
     * Idempotent in the sense that matters: calling this twice on the same input converges to the
     * same output, and nothing here re-derives an already-current field from a stale legacy signal.
     * It is NOT a guaranteed no-op for every object already at {@link #CURRENT_SCHEMA_VERSION} --
     * the {@code ssaaPreset}/{@code debugView} null-safety checks below are unconditional, not
     * version-gated (a removed enum constant can appear at any persisted version, including one this
     * build already stamped to current), so an object holding a stale null in either field is still
     * normalized here even when its {@code schemaVersion} is already current.
     */
    public static FornaxSettings migrate(FornaxSettings settings) {
        if (settings.schemaVersion < 1) {
            // v0 -> v1: derive the method from the one legacy signal that existed, ssaaPreset --
            // BEFORE the v2 step below rewrites it, since OFF-vs-not is exactly that signal.
            settings.aaMethod = settings.ssaaPreset != SsaaPreset.OFF ? AaMethod.SSAA : AaMethod.TAA;
        }
        if (settings.schemaVersion < 2) {
            // v1 -> v2: ssaaPreset became a factor-only field (aaMethod alone owns on/off), so a
            // persisted OFF is no longer meaningful -- normalize it to the field's default factor.
            // The method is untouched: whatever v1 chose (or the user has since picked) stands, so
            // a file that had supersampling off keeps rendering exactly as before.
            if (settings.ssaaPreset == SsaaPreset.OFF) {
                settings.ssaaPreset = SsaaPreset.X2;
            }
        }
        // Not version-gated: a null here means the file held an enum constant this build no longer
        // has (X9, removed from the ladder -- Gson maps an unknown constant to null, never an
        // error), and that can appear at ANY persisted version, including files this build already
        // stamped to 3 before a hypothetical future constant removal. Normalize to X4, the nearest
        // remaining lower factor, before anything dereferences the field. CAVEAT: the v3 stamp below
        // only triggers FornaxConfig.load()'s save-on-change (rewriting the stale "X9" to disk) when
        // the incoming schemaVersion was BELOW 3 -- for a file already at 3 whose enum constant was
        // removed by a later build (the case this comment exists for), re-stamping to the same 3 is
        // a no-op compare and save() never fires, so the fix applies in memory every launch without
        // ever reaching disk. Same caveat applies to the debugView null-guard just below.
        if (settings.ssaaPreset == null) {
            settings.ssaaPreset = SsaaPreset.X4;
        }
        // Gson maps persisted enum names that no longer exist to null. Keep old config files safe
        // across diagnostic-view removals before render code calls debugView.shaderId().
        if (settings.debugView == null) {
            settings.debugView = GBufferDebugView.OFF;
        }
        // Same rule for the sidecar resolution, and it needs it MORE than the two above: a config file
        // written by any build before this field existed simply has no key for it, which Gson also
        // leaves as null -- as does a config holding the earlier `sidecarAtlasBudget` key, which
        // this field replaced. Normalizing to HALF is what keeps the first atlas build from
        // dereferencing null, and HALF is what the retired byte budget already produced on the
        // packs the setting exists for.
        if (settings.sidecarMapResolution == null) {
            settings.sidecarMapResolution = SidecarMapResolution.HALF;
        }
        settings.schemaVersion = CURRENT_SCHEMA_VERSION;
        return settings;
    }
}
