# Fornax architecture

> This document describes the current code. Changes to any surface documented here must update
> this file in the same commit.

## 1. Overview

Fornax is a client mod (an add-on players install into Minecraft) built for the Fabric mod loader.
It replaces Sodium's forward terrain shading with a deferred, Vulkan-native pipeline. Vulkan is the
low-level graphics interface the game draws through, in the same role as DirectX or Metal on other
platforms. "Deferred" means the game first draws the raw shape and surface data for everything on
screen, then works out lighting afterward from that stored data, rather than lighting each object
the moment it is drawn (the "forward" approach Sodium uses on its own). Fornax has no rendering
opinions baked into its own Java code: every pass, target, and tunable value comes from a Vulkan
shaderpack, a folder of text files an artist writes rather than a programmer, loaded from
`shaderpacks/` at runtime. Three subsystems make this work:

- **Loader** (`pack`, `pack.layout`, `pack.material`, `pack.option`). Discovers a pack directory or
  zip, parses its TOML manifests (TOML is a plain-text settings format) into immutable records,
  scans annotated shader options, resolves block materials, and serves the rewritten shader text
  back to the client as a synthetic resource pack.
- **Graph interpreter** (`pack.graph`). Walks the loaded pack's declared render graph, a list of
  drawing and computing steps and how their inputs and outputs connect, once per frame, allocating
  and resizing GPU targets and dispatching each pass to a generic runner keyed by pass type. There
  is no hardcoded sequence of named passes anywhere in this layer; the graph itself is the program.
- **Integration surface** (`mixin.sodium`, `mixin.vanilla`). The seam between the graph interpreter
  and Sodium or vanilla Minecraft. A mixin is a way of patching another program's compiled code at
  load time to insert new behaviour without touching its source, and Fornax uses this technique to
  bracket the opaque terrain draw, to widen the vertex format, the uniform buffer (a block of GPU
  memory that every draw can read that frame, holding values like the camera position), the bind
  groups (the fixed slots a draw call uses to tell the GPU which textures and buffers to read), and
  the Vulkan push-constant range (a small, fast slot of per-draw data) that Sodium's terrain shaders
  use, and to drive supersampling and temporal jitter at the
  `GameRenderer`/`Window` level.

With no pack active, or with shaders disabled in the config, every one of these hooks is a no-op
(it does nothing) and terrain renders as plain, undeferred vanilla Sodium.

## 2. Package layout

| Package | Purpose |
|---|---|
| `dev.icehunter.fornax` | Mod entrypoint (`FornaxMod`): config load, pack discovery/boot, keybind registration |
| `.atlas` | LabPBR normal-map and material-map atlases built alongside the vanilla block atlas |
| `.compat` | The Sodium video-settings integration (`SodiumConfigEntry`): a single external "Fornax Settings…" page opening the YACL-hosted `FornaxSettingsScreen`, plus the pack-option config surface (`PackSettingsScreen`) |
| `.config` | `FornaxConfig`/`FornaxSettings` (engine-owned settings, Gson-backed), `GBufferDebugView`, `SettingsApplyRouter` (pure change-detection behind the YACL save callback) |
| `.mixin.sodium` | Integration mixins targeting Sodium's renderer, vertex format, and uniform/bind-group plumbing |
| `.mixin.vanilla` | Integration mixins targeting vanilla `GameRenderer`/`LevelRenderer`/`Minecraft`/`PauseScreen`/`Screen`/`Window`/the Vulkan pipeline builder |
| `.mixin.vulkan` | Raw Vulkan accessors plus the device-extension hook that enables `VK_EXT_metal_objects` for MetalFX interop |
| `.mixin.yacl` | YACL screen chrome, close/apply behaviour, and private-widget accessors |
| `.metalfx` / `.metalfx.objc` | MetalFX temporal scaling, Vulkan/Metal shared-image and shared-event interop, and the pure-Java FFM bridge to Objective-C/Metal |
| `.pack` | Pack manifest records (`PackModel`, `GraphSpec`, `PassSpec`, `TargetSpec`, `BlocksSpec`, ...), TOML parsing, discovery, shader-import validation, pack reload (`PackReload`). Also `ShadersEnabledFlip`, the shared master-toggle-only apply path (render-state latch), and `PackSwitch`, the shared active-pack-selection apply path (same rule): both are invoked only by `screen.FornaxPacksTab`'s self-applying Apply flow (the Engine save callback routes only `{SAVE_ONLY, PACK_REAPPLY}`, never these) |
| `.pack.graph` | The graph interpreter: `GraphRunner`, target allocation (`TargetRegistry`/`TargetPlan`/`TargetInstance`), per-pass-type runners, VRAM (the graphics card's own memory) reporting |
| `.pack.layout` | Shader source rewriting (`DefineRewriter`), the `u_PackOptions` layout/buffer, the synthetic `RuntimeShaderPack` |
| `.pack.material` | blocks.toml -> dense material IDs, block/tag resolution, the generated material GLSL include |
| `.pack.option` | Annotated-`#define` option grammar parsing and cross-file merging |
| `.pass.ssaa` / `.pass.taa` / `.pass.reconstruct` | The general render-scale target lifecycle: SSAA (render bigger, then shrink down for smoother edges) box downsample, TAA/TAAU temporal reconstruct (spreading anti-aliasing work across several frames using motion history) plus presentation sharpen, and the camera jitter sequence |
| `.profile` | GPU per-pass timing (`PassTimer`, ring-buffered across frames-in-flight) feeding a pure-JVM rolling-stats aggregator (`FrameProfiler`); `ProfilerOverlay` (top-left HUD) and `ProfilerLogDump` (full-table log dump) read it, both graded against the 11.1 ms / 90 FPS budget |
| `.pipeline` | Shared per-frame state: `GBuffer`/`GBufferManager` (the G-buffer is the set of full-screen images, such as surface colour, normal direction, and depth, that the deferred step stores before lighting runs), `FornaxChunkVertex`, the render-state latch, push-constant layout, previous-frame camera transform, per-thread material ID, the engine-guaranteed `SceneHistory` target |
| `.screen` | Pack settings UI: `PackManageScreen` (the pack-agnostic YACL "Manage" entry point, a Shader Options bridge; Import/Export/Defaults live as `mixin.yacl.CategoryTabMixin`-injected chrome, scoped via `PackChromeActions`) and its session-free `PackValuesActions` helper, the legacy bespoke option pages (`PackSettingsScreen`), the YACL-hosted engine-settings factory (`FornaxSettingsScreen`) opened from the pause/title menu, Sodium's video settings, and the open-settings keybind, and its custom Shader Packs tab (`FornaxPacksTab` + the pure `PackListState`) |
| `.util` | VRAM estimation, renderer-reload request plumbing, sun-direction math |

## 3. Frame skeleton

The graph interpreter is driven entirely from one mixin, `SodiumWorldRendererOrchestrationMixin`,
which brackets Sodium's opaque terrain draw:

```
SodiumWorldRenderer.drawChunkLayer(OPAQUE, ...)
  HEAD   -> EmitterFrameState.commit(...)
           GraphRunner.prepare(matrices, x, y, z)
             size/clear targets
             submit independent lighting compute in graph order
             insert one final compute -> graphics semaphore wait
  [Sodium's own SOLID/CUTOUT terrain draws run here, into the shared G-buffer]
  RETURN -> GraphRunner.finish(matrices, x, y, z)
```

Nothing runs for the translucent layer group.

**`GraphRunner.prepare()`** does nothing immediately unless a pack is active (the latched render
state, not live config; see §9). Otherwise it reads the current `mainRenderTarget` size, ensures
the G-buffer and every graph target are sized for it, lazily builds the GPU-backed pass runners
and options buffer once a device exists (pack activation itself runs before any device is
guaranteed to exist), sizes any mip-chain targets, and clears the G-buffer depth attachment to the
reversed-Z far value. It then submits the independent voxel-light producer chain
(`light_inject`, `light_propagate`, `light_list_reset`, and `light_list_build`, subject to compile
options) to the shared compute queue in graph order. Only the last runnable producer signals a
compute-to-graphics semaphore (a GPU-side signal one queue raises and another waits on), whose wait
is inserted before opaque terrain begins. Queue order makes that one signal cover the complete
producer chain while avoiding a wait inside an active Apple tile render encoder.

**`GraphRunner.finish(matrices, x, y, z)`** iterates the pack's declared passes in file order. A
`geometry`-typed pass is a pure placeholder, since Sodium's own draw already ran into the shared
G-buffer by the time this runs, and the independent lighting producers are placeholders here too,
because `prepare()` already submitted them. Every remaining pass is skipped if its `enabled_if`
compile-option expression evaluates false; otherwise it is dispatched by type to a `fullscreen`,
`mipchain`, `copy`, or dependent `compute` runner. An enabled pass whose runner is missing (a
contract violation: `ensureRunnersBuilt()` builds every enabled pass's runner or none, retrying) is
skipped but logged at ERROR once per pass per pack session, never silently. A silent skip here would
be the exact signature of the worst failure this engine can produce: the deferred chain runs and
produces nothing, with zero log evidence, because terrain lands in the G-buffer but the resolve pass
never composites it to screen. After the pass loop, a pack that declares no depth copy-back pass
gets a hardcoded fallback copy of G-buffer depth into the main render target's depth texture, so
translucent draws afterward always see correct depth.

If a `PassTimer` was built (lazily, once a device exists; see `ensureRunnersBuilt()`), the pass loop
is bracketed with GPU timestamp writes. One bracket spans the whole loop (`"frame"`); one spans up
to the loop's first geometry-slot iteration (`"geometry dwell"`, named that and not "terrain" on
purpose: Sodium's terrain draw already finished before `finish()` runs, so this measures only
GraphRunner's own dwell in that slot); and one wraps each dispatched pass by name. Results ride a
ring of query pools sized to `FramePacing.FRAMES_IN_FLIGHT` (3), the same shared constant used by
`ComputePassRunner`'s command/fence/descriptor ring, and are drained at the start of the next
`beginFrame()` that rotates onto a slot, before any of that frame's own writes, because the
backend's `writeTimestamp` host-resets the query index immediately (Vulkan `vkResetQueryPool`),
destroying any still-unread value there. Drained durations land in `GraphRunner.frameProfiler()` a
few frames late. `ProfilerOverlay` (a `HudElement` registered via `HudElementRegistry`, toggled by
`FornaxSettings.profilerOverlay`) refreshes its own cached copy of `snapshot()` at roughly 4 Hz and
renders that cache every frame; `snapshot()` itself allocates and sorts, so the HUD never calls it
per frame. An empty cache (nothing ever recorded, whether from an unsupported backend or no pack
loaded) renders a single "timestamps unavailable" line instead of an empty or garbled panel. A
second keybind (`ProfilerOverlay.dumpToLog()`) logs a full breakdown table via
`ProfilerLogDump.format()` at INFO, taking a fresh snapshot on demand. The pre-HUD throttled summary
line (one per roughly 900 frames) still exists for headless diagnosis but now logs at DEBUG, since
the HUD is the normal observable path. Mispaired brackets poison and drop that frame's timings
(logged once) rather than ever pairing timestamps across labels. A backend reporting no usable
timestamp period degrades `PassTimer` to a one-time-logged no-op; the pass loop's own behaviour and
ordering are unaffected either way.

Then, in order, at the very end of `finish`:

1. **History swap.** `TargetRegistry.swapHistory()` pointer-swaps `texture/view` with
   `historyTexture/historyView` for every history-backed target. This is a swap, not a copy.
2. **Camera commit.** `PreviousFrameCameraTransform.commit(...)` snapshots this frame's camera
   transform and projection/model-view matrices (deep-copied, since the matrix instances handed in
   are mutated in place elsewhere every frame), so next frame's motion-vector math has a genuine
   previous-frame reference. This must run last: swapping history first, then committing the
   camera, means the reprojection math a pass performs this frame always reads last frame's camera
   against last frame's (pre-swap) content, never a frame-ahead mismatch of one but not the other.

**Sky ownership.** Before any of the above runs, `LevelRenderer.addSkyPass` registers vanilla's sky
pass into the frame graph, and `LevelRendererSkyPassMixin` injects at its start. When the active
pack owns the sky (`GraphRunner.packOwnsSky()`: the pack is active and its `SKY_PROCEDURAL` compile
option resolves non-zero) and vanilla's own guards would otherwise let the sky draw (overworld
skybox, no powder-snow/lava fog, no sky-blocking mob effect), the mixin cancels the pass. Either way
it commits `SkyFrameState`'s did-cancel flag for the frame. This registration happens before the
frame graph executes, so before `SodiumWorldRendererOrchestrationMixin`'s terrain draw and before
any `GlobalUniformsWriteMixin` write of `u_Globals` this frame, meaning the committed flag is never
stale when the sky tail (§6) is written. The resolve pass paints procedural sky exactly when that
committed flag is set; it does not re-evaluate `packOwnsSky()` itself, so the cancel/paint pair
cannot drift apart. End and Nether frames, and any frame where vanilla's own guards would already
suppress the sky, keep vanilla's untouched behaviour.

**Sky ownership gates the flag, never the data.** `SkyFrameState` carries only the two did-cancel
flags. The sky's actual values (sky colour, sunrise colour, star brightness, true sun direction,
moon phase, rain level, sun angle) come from `SkyProbe`, read live off the camera's
`EnvironmentAttributeProbe` inside `GlobalUniformsWriteMixin` every frame, in every dimension,
regardless of which passes ran or whether the pack owns the sky. A pack does not need
`SKY_PROCEDURAL` to read a real sky colour or rain level.

That split was not always right, and getting it wrong was expensive. Those values used to ride the
cancellation branch of the mixin above, so any pack that let vanilla draw the sky read zeroes for
all of them: the engine was withholding data because of a styling decision, which inverts what this
layer is for (Iris hands every pack `skyColor`/`rainStrength`/`sunAngle` regardless of who draws
sky). It starved three consumers silently: a pack's ambient light colour, `light_inject.comp`'s
`GI_SUN_BOUNCE` term (a zero sun vector fails `clamp(sunDir.y, 0, 1)` at all hours, not only at
night), and `CelestialSprites.moonPhaseRect` (pinned to phase 0). A zero vec3 is a plausible colour,
so none of it failed loudly. The general rule this leaves behind: a lane that describes the world is
populated unconditionally; only a lane that records a decision this engine made may be gated on
that decision. `SkyProbe` reads the same attributes, in the same units, that
`SkyRenderer.extractRenderState` reads (bytecode-verified), so packs that already owned the sky see
bit-for-bit identical values.

`LevelRendererCloudsPassMixin` mirrors this at `addCloudsPass` registration, later the same frame
since vanilla calls `addSkyPass` before `addCloudsPass` (bytecode-verified), cancelling vanilla's
clouds pass when `GraphRunner.packOwnsClouds()` and committing `SkyFrameState`'s clouds tail
(did-cancel flag plus wind clock, `u_SkyState.z`/`w`) either way, with the same
flag-is-the-cancellation discipline.

**Compat yield.** Both `packOwnsSky()` and `packOwnsClouds()` additionally require
`!SkyModCompat.competingSkyModLoaded()`. Users routinely run Fornax alongside other sky/cloud mods
(for example Nuit, FabricSkyboxes), and two mods each cancelling vanilla's sky/clouds pass and
painting their own would fight over the same frame. `SkyModCompat` checks a small fixed set of
known-competing mod ids via `FabricLoader.isModLoaded`, caches the (session-invariant) answer, and
logs once at INFO the first time it finds a match. When true, Fornax never cancels vanilla's sky or
clouds passes for that session; this is coexistence by yielding, not an attempt to detect and
resolve a rendering conflict.

After `finish()` returns, vanilla's frame continues completely untouched: hud3d projection, the
hand depth clear, `renderItemInHand`'s submit, and the hand + screen-effects `renderAllFeatures`
flushes all run exactly as vanilla wrote them, into the off-screen render-scale target. The engine's
whole frame tail runs at `renderLevel` RETURN as explicit ordered statements from one injection: the
off-screen restore (SSAA's box downsample, or TAA/TAAU's temporal reconstruct, which by this point
sees the complete frame including translucents, hand, and screen overlays), the sceneHistory copy
(OFF/SSAA only; skipped by a flag when the reconstruct already wrote the slot), and the jitter
advance, always last. This ordering is a hard-won rule, not a default choice: a mid-frame variant
that resolved before the first-person hand, so the hand could draw onto the finished native frame,
was tried in three placements and each one corrupted some part of vanilla's hand/translucent phase
state (an actual failure caught in testing). First-person ghosting is instead solved inside the
reconstruct itself by responsive-pixel masking (see §10), which needs no injection between vanilla
draws at all.

## 4. Pack loading pipeline

1. **Discovery.** `PackDiscovery.discover()` scans `<game dir>/shaderpacks/` (created if absent). A
   directory qualifies if it contains `pack.toml`; a `.zip` qualifies if its root (mounted via
   `FileSystems.newFileSystem`) contains `pack.toml`. This is a plain OS/zip scan, not the Fabric
   resource-pack mechanism.
2. **TOML parsing.** Pack manifests are parsed with a real TOML library (night-config, shipped
   jar-in-jar) into four typed specs: `pack.toml` (name/version/format), `graph.toml` (targets and
   passes), `blocks.toml` (material categories, optional), `screens.toml` (settings-UI layout and
   quality profiles). `pack.toml`'s `format` field is checked against the one format version this
   build understands; a mismatch fails load immediately. All TOML tables are parsed
   insertion-order-preserving, since category and option declaration order becomes dense-ID order
   and uniform-block layout order downstream.

   `screens.toml` also carries two constructs beyond the base four (`main`/`screens`/`profiles`/
   `sliders`): `[metas.NAME]` tables (`PackTomlLoader.loadScreens` → `ScreensSpec.metas()`, a
   `Map<String, MetaSpec>`) and an optional `[yacl] pages = [...]` list (→
   `ScreensSpec.yaclPages()`). A `MetaSpec(label, description, values, assign)` is one meta-option:
   `values` is its ordered tier-name list, and `assign` maps each tier name to a
   `Map<String, Object>` of option-name to raw-TOML-literal, staged together as one unit (see §10's
   `MetaBinding`). A `[metas.X.assign."Tier Name"]` sub-table header may quote a spaced tier name;
   night-config strips the quotes on the parsed key, so `assign` stores it plain (`"Tier Name"`, not
   `"\"Tier Name\""`). The loader rejects any assign tier not present in that meta's own `values`
   list. A page element token `<meta:NAME>` resolves (via `ScreenElement.resolve`) to a new
   `ScreenElement.MetaRef(String metaId, MetaSpec meta)` case alongside the pre-existing
   `Option`/`ScreenLink`/`ProfileCycler`/`Empty`, throwing at resolve time if `NAME` isn't a declared
   meta. `[yacl].pages` names zero or more `[screens.X]` page ids to migrate onto native YACL
   rendering (see §10) instead of the legacy bespoke `PackSettingsScreen`. A page not listed there
   stays reachable only through the old screen; a page listed there is not also added to
   `[main].elements` (it has no legacy `[pageId]` link, which avoids the same page being editable
   through two different sessions at once).
3. **Shader source read plus option scan.** Every `.fsh`/`.vsh`/`.glsl` under the pack's `shaders/`
   directory is read into a path-sorted map, keyed pack-root-relative (`shaders/post/ssao.fsh`).
   Every line across every file is checked for an annotated `#define`; a well-formed annotation
   becomes a `PackOption`, merged across files in first-encounter order. Two files declaring the
   same option name must agree byte-for-byte (type, range/enum, label) or load fails with a
   conflict error naming the option.
4. **Validation.** Several independent checks all raise the same load-time error type, so a broken
   pack never reaches a half-loaded state:
   - Shader `#moj_import` includes are resolved eagerly against the same source map (see §8's
     namespace rule). An unresolvable include fails load instead of silently splicing a broken
     shader that only surfaces as a compile error deep in a render frame.
   - The graph is validated as a whole: every target's format parses, every `enabled_if` expression
     references only compile options (never a runtime option, enforcing the runtime/compile split
     the option grammar promises), every pass's input/output references resolve to a declared
     target or a `builtin.*` name, and the pass/target graph is checked for cycles. A VRAM estimate
     is computed and logged alongside this pass.
   - **Gate consistency.** A pass must never be enabled while an `enabled_if`-gated target it reads,
     writes, or mipchains is unallocated. The check is exact: it enumerates the combined domain of
     every compile option both expressions reference (booleans and bracketed enum lists, capped at
     4096 points, beyond which only a byte-identical `enabled_if` is accepted) and refuses the
     graph, naming the counterexample assignment. Without this, the mismatch would only surface as
     a runner-build failure that `ensureRunnersBuilt()`'s retry loop swallows, taking the entire
     post chain (resolve included) down with it: terrain still draws into the G-buffer but nothing
     ever composites it to the screen, an every-frame-blank failure with no load-time error
     anywhere.
   - A pack's settings-UI profiles are checked against the merged option table; an unknown key only
     warns, never fails load, since profiles are allowed to reference options a pack has since
     removed.
   - **`MetaValidator.validate`** (run immediately after `OptionScanner.scan`, before the pack model
     is returned) is the meta/yacl-pages analogue of the profile check above, but fatal rather than
     a warning: a meta naming a non-existent option, a `<meta:NAME>` page token naming an undeclared
     meta, or a `[yacl].pages` entry naming a page with no matching `[screens.X]` table each throws
     a named `FornaxPackError` (an `[metas.X.assign.TIER]` naming a tier not in that meta's own
     `values` fails even earlier, at parse time in `PackTomlLoader.loadScreens`). A broken
     meta/yacl reference is a genuinely malformed pack, since the settings UI has no fallback
     rendering for a dangling meta row, unlike a profile's soft-drift tolerance.
5. **Runtime shader pack construction.** Validated manifests plus a generated material GLSL include
   (see §8) are handed to the graph interpreter, which rewrites every shader's `#define`s for the
   selected compile values, prepends a generated `u_PackOptions` uniform block declaration to every
   fullscreen-pass shader that needs one, and publishes the result through a synthetic,
   always-present, hidden `PackResources` registered directly into the client's real
   `PackRepository` at construction time (there is no public Fabric API for a non-file-backed
   resource pack, so this is wired in by hand). Terrain pipeline compilation then targets this
   synthetic pack's shader instead of Sodium's own.

   **Vanilla core-shader overrides** ride the same publish: a pack file at
   `shaders/vanilla/<name>.fsh` (define-rewritten like any other source) is additionally served at
   the vanilla asset path `minecraft:shaders/core/<name>.fsh`. `VanillaShaderOverrides` is the
   registry of legal override points; each maps a file name to a gate compile-option
   (`lightmap.fsh` → `LIGHTMAP_CURVES` is the only v1 entry). An unregistered file name under
   `shaders/vanilla/` fails load, and a graph pass may never reference one as its shader. If the
   gate is off, the file is absent, `shadersEnabled` is false, or no pack is active, no entry is
   published and the synthetic pack does not even advertise the `minecraft` namespace, so vanilla's
   own shader text wins untouched (a true A/B comparison, invisible when off). The synthetic pack
   sits above vanilla's built-in pack in the repository order, and every publish triggers a resource
   reload whose `ShaderManager.apply` clears the device pipeline/module caches and eagerly
   recompiles all static pipelines, so an override lands (or reverts) without any extra invalidation
   hook. Deactivation clears only the override map and keeps the `fornax_runtime` sources published
   on purpose: the reload it fires recompiles Sodium's still-fornax-flavored terrain pipeline before
   the chained renderer reload reverts it to stock, so dropping sources there would be a "Couldn't
   find source" crash at the next chunk draw (an actual failure caught in testing).

   **Binary vanilla-asset overrides** ride the same publish under a second, parallel map. A pack
   file at `textures/vanilla/<name>` is read as raw bytes (`PackDiscovery.readTextureOverrides`, with
   no `#define` rewriting, since PNGs aren't text) and served at
   `minecraft:textures/environment/<name>`. `VanillaAssetOverrides` is the registry: nine legal
   names (`celestial/sun.png` plus the eight `celestial/moon/<phase>.png` files MC 26.2's celestials
   atlas sources from `textures/environment/celestial/`), all gated behind one compile-option,
   `CELESTIAL_TEXTURES`. The same "invisible when off" rules apply as for the shader-text overrides:
   if the gate is off, files are absent, `shadersEnabled` is false, or no pack is active, nothing is
   published. The synthetic pack advertises the `minecraft` namespace when either the text or binary
   override map is non-empty, and `getResource` checks the binary map first (raw bytes, no UTF-8
   round-trip) before falling back to the text map. Deactivation clears both maps together, since
   they share the one namespace and the one invariant.
6. **Rebuild triggers.** Four independent things can cause a live pack to recompile or refresh:
   - A **compile-option** change (quality toggles, enum switches) re-parses shader sources,
     rewrites `#define`s, and republishes through the synthetic pack.
   - An **engine AA/upscale method** change (`FornaxSettings.aaMethod`, the Sodium-hosted Engine
     page) is a compile-state change too. The `FX_*` engine defines it drives (see §10) change
     which pack graph shader text compiles, but this goes through `PackReload.reapplyActivePack()`
     rather than the pack settings screen's own apply path, and never triggers `RendererReload` (the
     terrain pipeline shape is unaffected; only the pack graph's own fullscreen passes recompile).
   - **Pack switch/deactivation** from the Shader Packs tab's Apply (`FornaxPacksTab` →
     `PackSwitch`) performs the same rebuild against the newly selected pack (or unloads back to
     plain vanilla Sodium for "None").
   - A **datapack tag bind** (`TAGS_LOADED`) only refreshes the block-material lookup table and
     requests a terrain remesh, never a full shader recompile, since no shader text depends on tag
     membership.

   A **runtime-option** change (a slider) never triggers any of the above; it only rewrites the live
   `u_PackOptions` GPU buffer. A **window resize** also never triggers a rebuild; every graph target
   is resized in place every frame as part of `prepare()`.

## 5. Target model

Every target a graph declares gets a `scale` and a sizing `basis` (`TargetBasis`, `render` default
or `output`, parsed from an optional `basis = "output"` key in `graph.toml`; an unrecognized value
fails load like any other malformed target field). A `render`-basis target sizes off the current
render resolution (`round(renderWidth * scale)`, floored at 1 pixel); render resolution is whatever
the main render target's current size is at the top of `prepare()`. Under SSAA that is already the
scaled-up size, and under TAAU it is the scaled-down size (see §10's `AaMethod`), so pack targets
always scale off whatever resolution the graph is actually running at, not necessarily the final
display resolution. An `output`-basis target instead sizes off the true native window resolution
regardless of render resolution, so it never loses detail once render resolution runs below native
under TAAU. `TargetPlan.compute`/`TargetRegistry.ensureSize` both take `(renderWidth, renderHeight,
outputWidth, outputHeight)` (a 4-arg `TargetPlan.compute` overload still exists for callers with
only one resolution, delegating with `output == render`).

`GraphRunner.prepare()` sources render size from `mainRenderTarget`'s own current size (correct,
since that is exactly what the graph renders into this frame, the off-screen render-scale target
under every method except OFF), but sources output size from `SsaaManager.nativeWidth()/
nativeHeight()` instead: the true physical framebuffer size, captured by `GameRendererMixin` at the
start of `GameRenderer.renderLevel` every frame, before any off-screen swap and before
`WindowMixin`'s scaled-size override can apply. `mainRenderTarget` itself is not a valid source for
output size, precisely because it is the off-screen render-scale target by the time `prepare()`
runs; reading its size for "native" would read the supersampled size under SSAA or the below-native
size under TAAU, not the display's actual native resolution. This sourcing fix closes a latent bug:
before it, `sceneHistory` (`output`-basis, see below) was sized off `mainRenderTarget` too, so under
SSAA it was allocated at the supersampled size rather than native, and the end-of-frame copy's
`Math.min(target, sceneHistory)` clamp silently wrote only a native-sized sub-region of that
oversized texture every frame. The remainder of the texture's UV space never received a value, a
permanent stale gap every `sceneHistory.history` consumer sampled across. `sceneHistory` now
genuinely shrinks to native size under SSAA, closing that gap; SSAA's own render resolution (what
the graph's other targets scale off) is unaffected. The engine-guaranteed `sceneHistory` target
(see §10) is the first and so far only `output`-basis target: SSR/resolve read history at native
detail no matter what resolution the graph itself ran the frame at.

A target may declare `history = true`, giving it a second, identically-sized texture. History
ping-pong is a pointer swap (`TargetInstance.swap()`), not a copy: at frame end, `current` and
`history` trade places for every history-backed target, so next frame's "read the previous frame"
input is this frame's write target from a moment ago, with no extra copy pass.

Reconciliation (`TargetRegistry.ensureSize`) is idempotent: a target already at the right size,
format, and history-ness is left alone; a mismatch tears down and rebuilds it; a target whose
`enabled_if` now evaluates false is freed rather than left allocated. Every freshly allocated
texture, and its history twin if any, is cleared to transparent zero before it is ever installed,
unconditionally, as a target-model-level guarantee rather than something left to whichever pass
happens to write it first. This exists because at least one supported Vulkan backend (MoltenVK,
which translates Vulkan calls to Metal) does not zero-fill newly allocated VRAM: a target built and
sampled before its first real write reads back whatever was previously resident in that memory.
Making the clear structural for every target, rather than an ad-hoc responsibility of individual
passes, removes an entire class of "why is there garbage on screen for one frame" bugs.

### Buffer targets (`kind = "buffer"`), and who sizes them

A target may declare `kind = "buffer"` instead of the default `texture`, making it a raw SSBO
(shader storage buffer, a flat block of GPU memory a shader can read and write arbitrarily) with no
format, scale, basis, history or filter, all of which are refused on it as unknown keys. It has
exactly two possible owners, and which one applies is decided by name, against
`GraphValidator.ENGINE_BUFFERS`:

* **Engine-owned**: `voxelBrickIndex`/`voxelOccupancy`/`voxelPayload`/`voxelFaceSeal`/
  `voxelPalette`/`voxelLightVolume`/`voxelBrickSummary` (`BrickGridUpload`), `voxelWaterRefl`
  (`VoxelWaterReflBuffer`), `analyticLightList` (`AnalyticLightListBuffer`). Their byte counts come
  from runtime quantities the graph cannot express (voxel window diameter, render resolution), so
  their own engine call site drives `TargetRegistry.ensureBufferSize`. A pack declares the target
  purely so the name is referenceable, and must not give it a size; the engine would overwrite it
  anyway.
* **Pack-owned**: anything else. It must declare `stride_bytes` (bytes per element, a multiple of
  4) and `count` (elements); `TargetPlan.compute` emits a `BufferEntry` for it and
  `TargetRegistry.ensureSize` allocates, resizes and frees it exactly like a texture target,
  including the `enabled_if` gate and the mandatory zero-clear at allocation.

Both directions are load errors, because both degrade silently otherwise. A pack buffer with no
size is allocated by nothing at all, and the first pass to bind it throws inside
`ensureRunnersBuilt()`'s swallowed retry loop, which discards every runner built in that attempt and
retries next frame, forever, so the symptom is "the whole post chain silently never runs," not an
error. That was the engine's actual state before this syntax existed: a `particles` pass naming a
pack buffer aborted every runner, every frame.

The size is expressed as `stride_bytes` × `count` rather than a single `size_bytes`, because the
element count is the only form in which the engine can check anything against a `particles` pass's
`instances`, and because the stride is what the pack's own `layout(std430) buffer { Element
data[]; }` already commits to. The size is resolution-independent by construction, which matters
for a field that accumulates: a reallocated buffer is a zero-cleared buffer, so sizing one off
render resolution would wipe its state on every window resize. The product is computed in `long`
and capped at `BufferSize.MAX_SIZE_BYTES` (1 GiB) at load; a 4-byte-aligned stride keeps the total
legal for the `vkCmdFillBuffer` call that performs the allocation-time zero-clear.

Only the pass types with a code path for a buffer may name one
(`GraphValidator.checkBufferBindable`): readable by `compute` and `particles` (as
`STORAGE_BUFFER`) and by `fullscreen` (as `UNIFORM_TEXEL_BUFFER`, R32_UINT); writable only by
`compute`. A buffer as a fullscreen/mipchain output, or as a copy/geometry input, is refused at
load rather than at runner build, where it becomes the same whole-graph abort described above.

### Builtin resource names (`builtin.*`)

`GraphValidator.BUILTINS` is the complete set of engine-resolved names a pass may reference without
declaring a target: `builtin.depth`, `builtin.blockAtlas`, `builtin.materialAtlas`,
`builtin.normalAtlas`, `builtin.lightmap`, `builtin.output`, the G-buffer attachments
(`builtin.gNormal`/`gAlbedo`/`gMaterial`/`gAo`/`gMotion`), `builtin.celestials` (the vanilla
sun/moon-phase atlas; see `CelestialSprites`), and `builtin.noise` (see below). `gAlbedo`'s alpha
channel carries the vanilla sky-light level (0-1, the lightmap Y coordinate) rather than the source
texture's own alpha; the pack-side terrain shader repurposes that lane once ALPHA_CUTOUT has already
consumed any meaningful alpha-test value, since no downstream resolve consumer reads the resolve
pass's own output alpha. `gbuffer_resolve.fsh` decodes it to gate SSR's sky-miss fallback, the
sun-shadow distance fade, and atmospheric fog's cave damping. Three further engine-owned names are
validated separately, each carrying an extra structural rule `GraphValidator.checkInputRef` enforces
on top of plain resolvability: `sunShadowMap` (`ShadowMapManager.TARGET`, no history slot),
`sceneHistory` (`SceneHistory.TARGET`, previous-frame-only, must be referenced as
`sceneHistory.history`), and `builtin.depth_opaque` (`OpaqueDepth.NAME`, an engine-owned,
self-managed D32 copy of the opaque G-buffer depth, not a `TargetRegistry` target since
`TargetFormat` has no depth format; captured at the finish-opaque boundary and cleared to the
reversed-Z far value at allocation; see its own subsection below). Every name resolves through
`GraphInputResolver`'s two switches (`resolveBuiltinView`/`resolveBuiltinTexture`), null-safe
against a resource that hasn't been captured/allocated yet. `ShadowMapManager.close()` runs inside
`GraphRunner.closeCurrent()` after its single device-idle-before-destroy boundary, so the D32 map
and dummy colour attachment are released on pack unload/switch the same way as `OpaqueDepth` and
water-prepass targets.

`builtin.noise` is the engine-generated 512×512 RGBA8 tileable noise texture (`NoiseTexture`),
lazily created once per session from a fixed seed, never re-rolled, never pack-supplied. It is the
one builtin whose contract extends past its content: `FullscreenPassRunner` binds it linear plus
repeat at the sampler bind site (every other input binds nearest plus clamp-to-edge), keyed off the
literal `"builtin.noise"` input-ref string, because the resource itself is filtered tileable noise
by design, not a discrete lookup. A general per-input filter syntax in `graph.toml` is not built
until a second consumer needs anything but the default.

### Pack-shipped texture assets (`[textures.*]`)

A shaderpack may ship its own static image assets for a shader to sample; a look-asset (for example
a hand-authored water-normal map) belongs in the pack, not the engine. `graph.toml` declares one
under `[textures.NAME]` with a single key, `file`, a path relative to the pack root
(`PackTextureSpec`, the texture-kind sibling of `TargetSpec`, parsed by `PackTomlLoader.loadGraph`
into a third `GraphSpec` component, `textures()`, alongside `targets()`/`passes()`). Unlike a target
this is never a render output: no format/scale/history/enabled_if/basis, only a name and a file.

`PackDiscovery.loadFrom` eagerly proves every declared file exists and decodes cleanly (a pure
`NativeImage.read`-and-discard probe, no GPU device needed) at pack-load time, the same fail-loud,
never-a-silent-black rule the `#moj_import` and `blocks.toml` snippet checks already follow. A
missing or corrupt file is a `FornaxPackError` naming the declaration, never a deferred failure
discovered mid-frame. `GraphValidator` additionally refuses a `[textures.*]` name colliding with a
`[targets.*]` name (ambiguous resolution), rejects it as a pass `output` (read-only asset), rejects
a `.history` suffix on it (no ping-pong slot), and treats it as always final-for-frame for a
geometry pass's finality check (loaded once at pack activation, never written by any pass).

`PackTextureRegistry` (`pack.graph`, sibling to `TargetRegistry`) owns the actual GPU resources:
created, pure bookkeeping with no GPU call, alongside `TargetRegistry` in `GraphRunner.rebuild()`
(`packTextureRegistry` field), then `ensureLoaded()` lazily decodes (`NativeImage.read`, mirroring
`NoiseTexture`'s own upload path) and uploads every declared texture not yet built. This is called
from `GraphRunner.prepare()` exactly like `TargetRegistry.ensureSize`; `rebuild()` itself can run
before any GPU device exists (mod init), so upload is deferred to the first frame a device is
available. It is torn down in `GraphRunner.closeCurrent()` alongside `registry`, after that method's
own `VulkanComputeBackend.waitForGpuIdleBeforeDestroy()` call, the same MoltenVK teardown rule every
other GPU resource in that method follows.

A pack references a declared texture by its bare name (for example `waterWaveNormal`, with no
`builtin.` prefix, since it is pack-supplied content, not engine-generated).
`GraphInputResolver.resolveView`/`resolveTexture` check `GraphRunner.packTextureRegistry()` for the
name (via `PackTextureRegistry.isDeclared`) between the builtin switch and the target-registry
fallback. `FullscreenPassRunner` binds it linear plus repeat (the tileable-asset contract
`builtin.noise` already established), generalizing that sampler special case from a literal
`"builtin.noise"` string check to also match any name `PackTextureRegistry.isDeclared` recognizes.

(This capability replaced the engine's own procedurally-generated `builtin.waterWaveNormal`
texture, `WaterNormalTexture`, a from-scratch baked-normal generator, once a pack could ship its own
real water-normal asset instead; that class, its `GraphValidator`/`GraphInputResolver`/
`FullscreenPassRunner` cases, and its test are gone. `builtin.noise` itself is untouched; clouds and
foam still read it.)

### Geometry-pass inputs (`u_GeomInput0..3`) and `builtin.depth_opaque`

A `GEOMETRY`-typed pass, previously a pure placeholder for Sodium's own opaque/cutout terrain draw
(see §3), can declare `inputs = [...]` like any other pass type, resolved onto a small, fixed set of
sampler slots appended to Sodium's shared terrain bind group (descriptor set 0):
`u_GeomInput0..GeometryInputs.RESERVED-1` (`GeometryInputs.RESERVED == 4`). The slot count is fixed
at class-init, before any pack loads, because `ShaderChunkRenderer.BIND_GROUP` is a process-wide
static built once (`ShaderChunkRendererBindGroupMixin` appends the four slots there); it cannot vary
per pack the way a `TargetRegistry` allocation can.

- **Slot mapping is declaration order.** A geometry pass's *i*-th declared input resolves onto
  `u_GeomInput{i}` (`GraphRunner.refreshGeometryInputViews`, called once per frame from `prepare()`,
  before Sodium's own opaque terrain draw, the mixin bind site's only consumer). An undeclared
  trailing slot, including every slot when no pack or no geometry pass is active, is bound to
  `builtin.noise` as a safe, non-garbage default, never a null or stale view. A slot whose declared
  input transiently fails to resolve (a compile-disabled target, a registry mid-rebuild) falls back
  to noise for that frame the same way rather than propagating the failure.
- **Geometry passes are keyed by `GeometrySlot`.** A `type = "geometry"` pass names which kind of
  geometry its `program` shades via its `slot` key (`terrain`, `entities`, `hand`, `particles`,
  `weather`, `sky_basic`, `sky_textured`, `clouds`, `beacon_beam`, `lightning`, `damaged_block`,
  `armor_glint`, `spider_eyes`, `lines`, `block_entities`, `shadow`, and others). Omitting `slot`
  means `GeometrySlot.DEFAULT` (`terrain`); an unknown token, or `slot` on a non-geometry pass, fails
  load. Only `terrain` routes geometry today (`GeometrySlot.isRendered()`): every other constant is
  declared and validated but inert, so a pack can author and ship those programs before the engine
  routes anything into them, rather than being unloadable until that day arrives. `geometryInputViews`
  is indexed by slot, and `GraphRunner.geometryInputView(slot, index)` takes the slot explicitly; the
  Sodium terrain bind site passes `GeometrySlot.TERRAIN`.
- **Two load-time `GraphValidator` rules bound this feature.** A geometry pass declaring more than
  `GeometryInputs.RESERVED` (4) inputs fails load outright (`pass.<name>.inputs`; there is nowhere
  to put the excess). Two geometry passes claiming the same slot also fail load
  (`checkAtMostOneGeometryPassPerSlot`, key `pass.<name>.slot`): each slot's inputs resolve into
  that slot's own bind group, so the second pass's inputs would silently never bind, refused loudly
  at load instead of left as a silent dead declaration. Distinct slots are independent and legal.
- **`program` is honoured, not decorative.** `GraphRunner.geometryProgramPath(slot)` turns a pass's
  pack-root-relative `program` into the extension-less path an `Identifier` wants
  (`shaders/blocks/terrain` → `blocks/terrain`), and returns null when no pack is active or no pass
  claims the slot, which means "draw as vanilla would," not an error. The Sodium terrain mixin reads
  it, falling back to the conventional path only if a pack leaves `program` off.

### Substituting pack programs into vanilla pipelines

Non-terrain geometry draws through vanilla's own `RenderPipeline`s, so routing a pack program into
them is a separate mechanism from Sodium's terrain path:

- `GeometryPipelineMap` maps vanilla `RenderPipelines` constants onto the `GeometrySlot` that shades
  them, keyed on pipeline identity (the constants are singletons; `RenderPipeline` has no value
  equality). Only the entity pipelines are mapped today.
- `ShaderManagerGeometrySourceMixin` wraps the `precompilePipeline(pipeline, ShaderSource)` call in
  `ShaderManager.apply`, routing mapped pipelines at the shader-source level.

Three constraints drove that design, each of which fails badly if ignored:

1. **Substitute source, not compiled pipelines.** The backend caches
   (`VulkanDevice.pipelineCache`, `GlDevice.pipelineCache`) are identity maps filled by
   `computeIfAbsent`, so replacing a compiled pipeline pins whatever the first lookup returned; a
   later pack switch then silently serves the old program with no error. `ShaderManager.apply`
   already calls `clearPipelineCache()` and re-runs on every resource reload, which is exactly when
   a pack change must take effect, and one hook covers both backends.
2. **Route an identifier, not source text.** `getShaderSource` returns GLSL whose `#moj_import`
   directives are already resolved and inlined; handing back raw file text makes the compiler reject
   a directive that should never survive preprocessing (`Invalid Directive: moj_import`). Passing
   the pack's `Identifier` back through the same callback lets vanilla resolve it normally.
3. **A pack may override one stage.** Shipping only `.fsh` keeps vanilla's vertex shader and
   therefore its varyings, vertex format and bind groups, with nothing to keep in sync.

Unmapped on purpose, not an oversight: `ARMOR_DECAL_CUTOUT_NO_CULL` and `GLINT` depth-test `EQUAL`
against their base pass, so substituting one without the other (or changing either's depth output)
makes decals z-fight or vanish. Translucent entity pipelines get their own slot because
`RenderPipeline.build()` requires every non-null colour target to share one `BlendFunction`; blended
geometry can never write an unblended multi-target G-buffer, so it stays forward-shaded like
terrain's translucent arm.
- **`builtin.depth_opaque`** (`OpaqueDepth`) is the one builtin usable only from a geometry pass;
  `checkInputRef` rejects it for every other pass type, since only a geometry pass has the
  SOLID/CUTOUT-vs-TRANSLUCENT sub-draw split the freshness rule below depends on. It is an
  engine-owned, self-managed D32 texture, kept out of `TargetRegistry` on purpose (whose
  `TargetFormat` has no depth format, and whose reconcile clear path is a colour render pass that
  cannot clear a depth attachment): `OpaqueDepth.ensureSize` builds and clears it to the reversed-Z
  far value (`FAR_CLEAR = 0.0`) at allocation, the same MoltenVK garbage-VRAM rule every
  `TargetRegistry` target follows, applied by hand, and `GraphRunner.closeCurrent()` frees it
  on every pack teardown (unload, pack switch, mid-session rebuild), reallocated fresh on the next
  `prepare()` with a pack active.
- **Capture timing.** `GraphRunner.finish()`, which mirrors `FramePipeline.finishOpaque` and runs
  at the `RETURN` of Sodium's opaque `drawChunkLayer` (see §3), strictly before Sodium's own
  translucent draw, ends with `opaqueDepth.capture(gbuffer.getDepthTexture(), width, height)` as
  the last thing it does before `TargetRegistry.swapHistory()`: a straight D32-to-D32
  `copyTextureToTexture` from the live G-buffer depth attachment into this self-owned texture (the
  same primitive `finish()`'s own fallback depth copy-back uses). It is a copy, not a live-attachment
  sample, because the G-buffer depth attachment is still bound for depth-testing during the
  translucent draw that follows; sampling it directly there would be a Vulkan hazard.
- **The freshness rule.** Every terrain sub-draw (SOLID, CUTOUT, and TRANSLUCENT) shares one
  compiled shader (`ShaderChunkRendererShaderLocationMixin`) and this one shared bind group, but they
  do not all see the same `builtin.depth_opaque` content: SOLID/CUTOUT run before `finish()`'s
  capture (they are the terrain draw `finish()` itself brackets; see §3), so they still sample last
  frame's copy. Only TRANSLUCENT, which runs after `finish()` returns, sees this frame's fresh
  capture. A pack's geometry-pass shader must therefore sample `builtin.depth_opaque` only inside
  its translucent-only compile/code path, never unconditionally, or the opaque sub-draws will
  silently render one-frame-stale depth with no error of any kind. `checkInputRef` enforces the
  coarse half of this (restricting the input to `PassType.GEOMETRY` at all); the fine half,
  confining the sample site to the translucent branch of that pass's own shader, is a
  shader-authoring discipline the engine cannot itself verify, since it has no visibility into which
  `#ifdef` branch a sample line sits in.
- **Finality rule.** `checkGeometryInputFinality` additionally rejects, at load time, a declared
  geometry-pass input that no pass in the graph ever writes (it would read garbage forever, not only
  transiently); builtins/engine-owned resources (`builtin.depth_opaque` included) are always
  considered final. Every graph pass runs inside `finish()`, which completes before the translucent
  draw, so a target's position in `graph.passes()` relative to the geometry pass is immaterial: a
  target written by a pass listed after the geometry pass in file order (for example `ssr`, whose
  trace/blur passes sit after `terrain_opaque`) is legitimately final-for-frame and samplable.

  **Compile-gate rule (a pack-authoring responsibility).** This finality check runs against the
  declared graph; it cannot see runtime/compile option values, only that a writer exists. A target
  with compile-gated writers (for example `ssr`'s trace/blur passes, gated on the pack's own SSR
  mode/quality options) consumed by a geometry pass that is itself ungated (or gated on different
  options) cannot have its freshness validated by the engine at load time: the engine only proves a
  writer exists somewhere in the graph, never that it is active for the same compile configuration
  the geometry pass's sample compiles under. A pack sampling such a target from its geometry pass
  must compile-gate that sample in sync with the writer's own gate (identical option values);
  otherwise a configuration where the writer is disabled but the geometry-pass sample is not leaves
  the geometry pass reading a never-written (or stale-disabled) target with no load-time error to
  catch it. This is a pack-authoring rule the engine cannot enforce structurally, unlike the
  load-time rules above.

## 6. Uniform contracts

### `u_Globals` (std140, 688 bytes)

Written in two pieces sharing one physical buffer: Sodium's own uniform writer produces the first
184 bytes unmodified, and `GlobalUniformsWriteMixin` appends the remaining fields to the same
builder before its terminal `get()`, so both halves land in one contiguous upload with no separate
buffer object. The backing ring buffer (`UniformBufferManagerMixin`) is widened accordingly.

| Field | Type | Offset | Size |
|---|---|---|---|
| `u_ProjectionMatrix` | mat4 | 0 | 64 |
| `u_ModelViewMatrix` | mat4 | 64 | 64 |
| `u_FogColor` | vec4 | 128 | 16 |
| `u_EnvironmentFog` | vec2 | 144 | 8 |
| `u_RenderFog` | vec2 | 152 | 8 |
| `u_TexelSize` | vec2 | 160 | 8 |
| `u_TexCoordShrink` | vec2 | 168 | 8 |
| `u_FadePeriodInv` | float | 176 | 4 |
| `u_UseRGSS` | bool | 180 | 4 |
| *(padding to the next mat4's 16-byte alignment)* | | 184 | 8 |
| `u_PrevProjectionMatrix` | mat4 | 192 | 64 |
| `u_PrevModelViewMatrix` | mat4 | 256 | 64 |
| `u_JitterOffset` | vec2 | 320 | 8 |
| `u_PrevJitterOffset` | vec2 | 328 | 8 |
| `u_InvProjModelView` | mat4 | 336 | 64 |
| `u_SunViewProj` | mat4 | 400 | 64 |
| `u_VoxelWindow` | ivec4 | 464 | 16 |
| `u_CameraAbs` | vec3 | 480 | 12 |
| *(padding to the next vec4's 16-byte alignment)* | | 492 | 4 |
| `u_SkyColor` | vec4 | 496 | 16 |
| `u_SunriseColor` | vec4 | 512 | 16 |
| `u_SkyCelestial` | vec4 | 528 | 16 |
| `u_SkyState` | vec4 | 544 | 16 |
| `u_WaterState` | vec4 | 560 | 16 |
| `u_ShadowMapParams` | vec4 | 576 | 16 |
| `u_CameraSkyLight` | vec4 | 592 | 16 |
| `u_InvProjModelViewNoJitter` | mat4 | 608 | 64 |
| `u_FrameState` | vec4 | 672 | 16 |

Total: 688 bytes exactly. Both sides apply the same std140 alignment rules (std140 is a fixed,
standard packing convention that lets GPU shader code and CPU-side buffer-writing code agree on
where each field sits in memory) to the same declared type sequence in the same order, so in
principle the offsets can only agree. But they are written in two languages by hand, and a
disagreement produces no compile error, no validation failure, and no log line: only a uniform
silently holding a neighbouring field's bytes. `GlobalsLayoutContractTest` therefore computes the
block size from `globals.glsl` under std140 rules and asserts it equals what
`UniformBufferManagerMixin` allocates, so a divergence fails the build instead of surfacing as a
feature that mysteriously does nothing.

That check also covers the vec3 trap: `Std140Builder.putVec3` pads a vec3 to a full 16 bytes, while
GLSL lets a following member with smaller alignment sit at offset+12. Never place a scalar directly
after a vec3 in this block; put it before, or keep the vec3 last.

`u_SunViewProj` is only meaningful while the pack's `SHADOWS` compile option is enabled (an
identity matrix otherwise; see `ShadowFrameState`). `u_VoxelWindow`/`u_CameraAbs` back the
emitter-lights light-volume addressing (a zero-diameter window before it first activates). The sky
tail (`u_SkyColor`/`u_SunriseColor`/`u_SkyCelestial`/`u_SkyState`) splits by kind (§3, "Sky ownership
gates the flag, never the data"): every data lane is read live from `SkyProbe` at write time and is
always populated, while the two did-cancel flags come from `SkyFrameState`, committed during pass
registration. `u_SkyColor.w` is the load-bearing sky did-cancel flag the resolve pass paints sky on:
1.0 if vanilla's sky pass was cancelled this frame, 0.0 otherwise; `u_SkyColor.rgb` beside it is
real regardless. `u_SkyState`'s lanes are: `x` = rain level (0..1), `y` = sun angle (radians), `z` =
the clouds did-cancel flag (the same 1.0/0.0 convention as `u_SkyColor.w`, but for vanilla's clouds
pass, set by `LevelRendererCloudsPassMixin`), `w` = the wind clock (ticks since world start, wrapped
at 2^20; see the mixin's own doc comment). `z` is reset to 0 by every `SkyFrameState.commitSky` call
and only ever set nonzero by `LevelRendererCloudsPassMixin`, which vanilla always calls after
`addSkyPass` within the same frame (bytecode-verified), so the reset is always in place before the
clouds mixin's own write.

`u_FrameState` carries the small per-frame scalars Iris/OptiFine packs depend on: `x` =
`frameCounter` (monotonic, wrapped at 720720, a highly composite number, so `mod N` cycles evenly
for small N with no discontinuity at the wrap), `y` = the camera block's block light 0..1
(completing vanilla's `eyeBrightness` pair, whose sky half lives in `u_CameraSkyLight.x`), `z` =
thunder level 0..1 (distinct from `u_SkyState.x` rain, since storm-gated effects cannot be expressed
by rain level alone).

`u_WaterState` is the one-vec4 water tail appended after the sky tail: `x` = 1.0 if the camera eye
is in water this frame, else 0.0; `yzw` reserved, always zero-filled. This is computed live, inline,
every frame by `GlobalUniformsWriteMixin` itself, not sourced from `SkyFrameState`/
`LevelRendererSkyPassMixin` the way the sky tail above is. That sky-pass-hook shape was the original
design and shipped a real bug: `addSkyPass` is never called in `SkyType.NONE` dimensions (the
Nether), so a flag committed only from that hook froze at its last Overworld value for the whole
Nether visit. The fix moves the read to the one call site that runs unconditionally once per frame
in every dimension, `GlobalUniforms.write(ByteBuffer)`'s own `hasUpdatedThisFrame`-guarded body,
right at this water tail's `putVec4` call, reading
`Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera() == FogType.WATER` directly
(bytecode-verified as the exact same call `GameRenderer` itself uses to populate
`CameraRenderState.fogType`). There is no frame-state holder, no commit/reset lifecycle, and
therefore no Nether gap. It is consumed resolve-side only, by `gbuffer_resolve.fsh`'s
`WATER_UNDERWATER_FOG` grade (`applyUnderwaterGrade`); `terrain.fsh`'s water surface itself does not
read this flag. `y` widens that same test into the full enum Iris/OptiFine packs know as
`isEyeInWater` (0 none, 1 water, 2 lava, 3 powder snow), written into a lane this block already
reserved and zero-filled so `x` keeps its exact prior meaning.

`u_ShadowMapParams` is the one-vec4 shadow tail appended after the water tail: `x` = the shared
radial-distortion bias (`ShadowCamera.shadowMapBias(shadowDistance, resolution)` = `1.0 -
R/shadowDistance` with `R` the full-detail radius derived from the map resolution and the
centre-texel quality target; see that method's own derivation doc. 25.6 blocks at the 2048 default
map, computed once per frame from the live `shadowDistance` runtime slider and the declared
`SHADOW_RESOLUTION`), `yzw` reserved. It is committed alongside `u_SunViewProj` by
`SodiumWorldRendererOrchestrationMixin`'s shadow-pass hook via `ShadowFrameState.commit(viewProj,
bias)`, so the two values can never drift apart (the same ordering guarantee §3's shadow-pass doc
already establishes for `u_SunViewProj` covers this field too; both ride the same commit call).

This field completes the engine's shadow-acne fix: (1) a matched radial (polar) XY distortion,
applied identically on write (`shadow.vsh` and Plague's `shadow_entities.vsh`, both right after
`gl_Position = u_SunViewProj * vec4(cameraRelativePos, 1.0)`) and on the pack-side read
(`gbuffer_resolve.fsh`'s `sampleSunShadow` and its shadow debug-view branch), where
`ShadowCamera.distortFactor(lVertexPos, bias) = lVertexPos*bias + (1-bias)` and dividing xy by this
factor pushes shadow-map texel density toward the map centre where the player camera looks, every
site reading the one shared `u_ShadowMapParams.x` (never recomputed per file; see
`ShadowCamera.distortFactor`'s own doc comment for the canonical formula reference); (2)
`sampleSunShadow`'s slope-scaled world-space normal-offset bias (`1/max(ndl, 0.15)`, replacing an
earlier flat `2.0 - ndl` multiplier; see that function's own doc comment); and (3)
`ShadowComparisonSampler`'s hardware comparison sampler (`sampler2DShadow`, `u_Input9`/`u_Input1`
bound as such; see `FullscreenPassRunner`), which resolves each PCF tap's pass/fail boundary with
hardware bilinear interpolation instead of a raw NEAREST compare. (A depth-scale write-side step,
`gl_Position.z *= 0.2`, was part of this list until it was later proven vestigial on the D32_FLOAT
target and removed from every site; `ShadowCamera`'s class javadoc carries the argument.) An earlier
attempt shipped only the distortion/compression pair and reverted it the same day: warping the
matrix's linear box test broke `ShadowCasterLists`, and running distortion without the hardware
comparison sampler's own anti-aliased edges made self-shadow acne worse, not better (an actual
failure caught in testing). `viewProj` itself still stays the plain, linear camera-relative
ortho*view matrix; distortion/compression are shader-only post-processes, never folded into the
matrix (see `ShadowCamera`'s class javadoc), so `ShadowCasterLists.aabbIntersectsShadowVolume`'s
affine-map exactness argument is unaffected. `ShadowCasterLists`' caster-frustum fix (the coverage
fix from the same investigation) was untouched throughout and stays.

### `u_PackOptions`

This block is not TOML-generated and not versioned by a hash. A pack's runtime (slider) options are
laid out in first-encounter declaration order by a small std140 layout builder, block size rounded
to 16 bytes. A scalar immediately following a vec3 is placed at the next 16-byte boundary on
purpose, rather than the spec-legal trailing 4 bytes of the vec3; this is stricter than bare std140
requires, so the CPU-side write offsets can never silently diverge from what a given driver actually
does with a vec3's spare bytes. The generated GLSL block declares an explicit `layout(offset = N)`
per member taken directly from the same offsets map the Java buffer writer uses, so the two sides
regenerate together on every rebuild instead of needing a version check; staleness is structurally
impossible rather than detected after the fact. A pack with zero runtime options gets no block at
all (an empty uniform block is not legal GLSL); the block is only prepended to shader files a
`fullscreen` pass actually uses.

### Per-pass blend state

A `fullscreen` pass may declare `blend = "translucent"` (straight alpha: src `SRC_ALPHA`, dst
`ONE_MINUS_SRC_ALPHA`, meaning shaders output non-premultiplied colour) or `blend = "additive"`
(`ONE`/`ONE`) in `graph.toml`; the value maps to the platform blend presets on the pipeline's
colour-target state, which every pass previously hardcoded to opaque overwrite. Combined with the
always-LOAD colour attachment, this lets a pass hardware-composite over `builtin.output` without
reading it; the clouds composite is the first consumer. `GraphValidator` rejects unknown values and
`blend` on non-fullscreen passes; absent means opaque, byte-identical to the old behaviour.

### Per-pass params

Every `fullscreen` pass gets one fixed 64-byte `u_PassParams` block: a `vec2` texel size of its own
output, two generic scalars, the per-frame sun direction, and two `vec4` celestial sprite rects
(`u_SunSpriteRect` at offset 32, `u_MoonSpriteRect` at offset 48), replacing what used to be a
dedicated uniform struct per pass. Most passes only consume the texel size; the two generic scalars,
the sun direction, and the sprite rects are populated only for the passes that need them (for
example the resolve pass receives the current debug-view selector, the sun direction, and the
sun/current-moon-phase sprite rects within `builtin.celestials`, see `CelestialSprites`, since it has
no vertex-shader varying to receive any of that through). A pack's own artistic tunables never ride
this block; they belong to `u_PackOptions` instead. This block is what the mechanical, one-off
per-pass uniform structs of an earlier, hardcoded pass sequence were generalized into.

A pass whose GLSL still declares the block at its pre-existing 32 bytes (never referencing
`u_SunSpriteRect`/`u_MoonSpriteRect`) needs no changes and keeps working unmodified: Blaze3D binds
the whole 64-byte buffer to the `u_PassParams` uniform slot regardless of how much of it a given
shader's own block declaration covers, and the driver never reads the unread trailing bytes; binding
a larger GPU buffer than a shader's declared block is legal. This does not apply to the compute-pass
push-constant path (`ComputePipelineBuilder`/`ComputePassRunner`), which is pinned to its own
independent `PassParams.PUSH_CONSTANT_BASE_SIZE` (32 bytes, unchanged) rather than following
`PassParams.BUFFER_SIZE`. Push constants have no equivalent "shader may declare a smaller block
within a larger buffer" latitude, since a pack's own compute shader hardcodes the byte offset of any
`ExtraPushConstants` data appended after the shared block (see `EmitterLightExtra`); growing that
base offset would have silently corrupted every existing compute pass with extra push-constant data.
`GraphRunner.computeParams` resets and reuses one thread-local mutable `PassParams` plus one
thread-local `Vector3f`; pass runners serialize the values synchronously before the next pass is
computed, eliminating the former record/vector allocation chain from the 50+-pass hot path.

**`computeParams` fills the two generic scalars and the sun direction by pass name.** A pass name it
does not recognize receives zeros in all of them, every frame, and nothing reports that, so this
block is not a channel a pack-authored pass can rely on for per-frame state. That is intentional
(the alternative is a general per-pass parameter syntax nothing has needed) and it is why the
reserved `globals` input exists on the two raw-Vulkan pass types.

### The reserved `globals` input (`compute` and `particles`)

`compute` and `particles` passes build their own descriptor sets by hand, so neither gets
`u_Globals` automatically the way a `fullscreen` pass does (`FullscreenPassRunner.build` binds it
unconditionally) and neither sits in Sodium's terrain bind group the way a `geometry` pass does.
Listing the reserved name `globals` in a pass's `inputs` binds Sodium's live `u_Globals` slice as a
`UNIFORM_BUFFER` at that input's positional binding index, the same "reserved name, positional
binding" rule `packOptions` already follows on both types. The slice's offset is load-bearing
(Sodium's uniform buffer is a ring; this frame's data lives mid-buffer), so it binds
`offset + length`, never the whole buffer at 0.

A `particles` pass needs it for the camera matrices; a billboard cannot be placed on screen without
them. A `compute` pass needs it for everything else in the block: the wind clock (`u_SkyState.w`),
the frame counter (`u_FrameState.x`), rain/thunder/wetness, the precipitation type at the camera,
the weather anchor (`u_WeatherAnchor.xyz`, the player body position, bob-free) and the true sun
direction. Without it a pack compute pass has no clock of any kind and cannot advance a simulation
across frames, since its only other per-frame channel is the name-keyed `u_PassParams` push constant
above. It is refused on every other pass type, where the name would bind nothing while silently
shifting the pass's other binding indices by one.

`ComputePassRunner.run` skips the whole dispatch for a frame where a pass declared `globals` but no
slice is live (before the session's first terrain draw, and at `prepare()` time where the pre-opaque
lighting passes run), the same choice `ParticlePassRunner.run` makes, and the safe one for a
simulation: one missed tick of an accumulating field is invisible, one tick against another frame's
clock is not.

### Push constants (60 bytes)

Every terrain draw pushes a 60-byte Vulkan push-constant block: `vec3 u_RegionOffset` at 0, `int
u_CurrentTime` at 12, `uint u_RegionID` at 16, a 12-byte alignment gap, `vec3 u_SunDirection` at 32,
a further 4-byte gap, `vec3 u_PrevRegionOffset` at 48, ending at 60 (the standard region
offset/time/id block is the first 20 bytes; everything past that is engine-added, needed for
sun-lit bump lighting and motion-vector reprojection in the terrain vertex shader).

The Vulkan pipeline layout used for terrain draws must declare a push-constant range at least this
large. A Vulkan pipeline layout only reserves space for the byte ranges it explicitly declares:
`vkCmdPushConstants` calls writing past a layout's declared range are dropped by the driver silently
in the general case, and only flagged if validation layers happen to be enabled. If the declared
range and the pushed range disagree, the failure mode is not a crash but quiet data corruption:
fields past the declared boundary (here, the sun direction and previous-frame region offset) never reach the shader, and everything depending on them reads zero with no error anywhere.

## 7. Vertex format

Terrain uses a dedicated 24-byte vertex format, substituted in place of Sodium's own compact format
at its one construction site. Because every real consumer of vertex layout resolves the concrete
format dynamically through an interface, this substitution needs no changes anywhere else; Sodium's
own shaders are never used for terrain while a pack is active.

| Attribute | GPU format | Offset | Size | Contents |
|---|---|---|---|---|
| `a_Position` | RGBA16_UNORM | 0 | 8 | xyz normalized over a fixed model-space cube (wide enough for the mesh builder's per-section overhang); w unused |
| `a_TexCoord` | RG16_UNORM | 8 | 4 | direct, unbiased atlas UV |
| `a_Color` | RGBA8_UNORM | 12 | 4 | vertex colour already combined with baked ambient occlusion |
| `a_LightAndData` | RGBA8_UINT | 16 | 4 | x = block light (0-15), y = sky light (0-15), z = renderer-internal material/render-layer bits (not the block-material ID), w = draw/region id |
| `a_Normal` | RGBA8_UINT | 20 | 4 | x = flat face index (0-5), y/z = u16 block-material category ID (low byte first), w reserved |

The face normal itself is not stored per vertex; it is recovered on the GPU from the flat face
index via a fixed lookup table, since a quad's own edges already determine it and storing it
redundantly would cost bytes for no benefit.

**Face-index agreement point.** The Java side derives the face index from the dominant axis of a
quad's own edge cross product (robust to partial-extent quads, such as slabs, snow layers, and
farmland, that a simple exact-normal-match approach would misclassify), using the engine's own
directional enum ordering. The GPU-side decode table must enumerate face normals in that exact same
order. Nothing enforces this agreement mechanically; it holds only because both sides are hand-kept
in sync against the same six-direction ordering.

**Material-ID agreement point.** The material ID for the block currently being meshed is captured
into a per-thread slot immediately before Sodium meshes that block's quads. `ChunkBuilderMeshingTask`
drives two independent meshing calls per block position on the same build thread: `BlockRenderer
.renderModel` for the block's own model geometry (skipped when `RenderShape != MODEL`, for example
pure fluid blocks), and `DefaultFluidRenderer.render` for that position's fluid surface geometry
(only when `FluidState` is non-empty). Both are set-before/clear-after scoped independently
(`BlockRendererMaterialIdMixin` and `FluidRendererMaterialIdMixin`); without the fluid-side mixin,
water/lava quads never carry the model-path material ID, because `renderModel` is never called for
them. Meshing runs on background worker threads, one block fully processed at a time per thread, so
the simple set-before/clear-after scoping is correct with no locking. Both mixins derive the ID from
the same `BlockState` the block path sees (for water, `Blocks.WATER` via its single post-1.13 block
id; `BlockMaterials.idForState` keys by `Block`, so no FluidState-to-BlockState derivation is
needed). The vertex encoder packs that ID into `a_Normal`'s y (low) and z (high) bytes. Whatever
GLSL consumes this vertex format must decode those same two bytes back into a 16-bit value in the
same byte order, low byte first. This is a plain convention, not something the type system checks.

## 8. Mixin inventory

All 49 mixins are `required: true`; a mixin listed in the mixin config that fails to apply is a hard
load error, but a mixin not listed at all is never applied, with no warning of any kind. There is
nothing to distinguish "intentionally removed" from "accidentally dropped from the list" except
checking the list itself.

Only one of the 49 is a full-method `@Overwrite` (the highest-risk mixin shape, since it silently
stops tracking whatever the original method does beyond what was true at the time it was written);
every other mixin is additive, injecting, redirecting, wrapping, or modifying against a specific
instruction or call, changing only what needs to change and leaving the rest of the original method
to evolve upstream without needing to be restated here.

**Sodium-targeting** (23):

| Mixin | Target | Purpose | Shape |
|---|---|---|---|
| `BlockRendererMaterialIdMixin` | `BlockRenderer` | Set/clear the per-thread material ID around each block's model meshing call | Inject (HEAD/RETURN) |
| `ChunkBuilderMeshingTaskMixin` | `ChunkBuilderMeshingTask` | Harvest each section's block data for the voxel grid the moment Sodium (re)builds it, piggybacking on Sodium's own change detection (background worker thread, per rebuild, never per frame) | Inject |
| `FluidRendererMaterialIdMixin` | `DefaultFluidRenderer` | Set/clear the per-thread material ID around each block's fluid-surface meshing call (the `renderModel`-parallel path for water/lava quads) | Inject (HEAD/RETURN) |
| `CompactChunkVertexMixin` | `ChunkMeshFormats` | Substitute the engine's own vertex format for the stock compact format | Redirect |
| `DefaultChunkRendererGeometryStorageMixin` | `DefaultChunkRenderer` | Route shadow-pass draws to read the already-built SOLID/CUTOUT geometry storage instead of the Fornax-only shadow pass's own (which Sodium never meshes) | Redirect x2 |
| `DefaultChunkRendererRenderMixin` | `DefaultChunkRenderer` | Bind the PBR-settings uniform right after the stock per-section time uniform | Inject |
| `DefaultChunkRendererRenderPassMixin` | `DefaultChunkRenderer` | Route deferred (opaque/cutout) draws into a multi-attachment G-buffer render pass instead of the single-attachment stock target; translucent untouched | WrapOperation |
| `DefaultChunkRendererTextureBindMixin` | `DefaultChunkRenderer` | Bind the normal-map and material-map atlases, then every reserved geometry-input slot (`u_GeomInput0..GeometryInputs.RESERVED-1`, noise-defaulted) right after the stock block atlas bind | Inject |
| `DrawContextGLMixin` | `GLDrawContext` | Resolve and upload sun-direction/previous-region uniforms by name (OpenGL backend) | Inject x2 |
| `DrawContextInvoker` | `DrawContext` | Expose a protected static helper to sibling mixins | Invoker |
| `DrawContextVKMixin` | `VKDrawContext` | Widen the per-draw Vulkan push-constant block from 20 to 60 bytes, with matching alignment gaps | **Overwrite** |
| `GlobalUniformsWriteMixin` | `UniformBufferManager$GlobalUniforms` | Append the engine's `u_Globals` tail fields (previous-frame camera matrices, jitter, `u_InvProjModelView`, `u_SunViewProj`, `u_VoxelWindow`, `u_CameraAbs`, the sky tail: did-cancel flags from `SkyFrameState`, all data lanes live from `SkyProbe`; the live-computed water tail; the shadow-distortion-bias tail) to Sodium 0.9.1's per-frame terrain uniform write | WrapOperation |
| `RenderSectionManagerAccessor` | `RenderSectionManager` | Expose the private `regions` field (the only route to the region manager instance) | Accessor |
| `RenderSectionManagerFogOcclusionMixin` | `RenderSectionManager` | Disable Sodium's fog-distance section shrink while a pack is active, so pack-owned aerial/border fog never loses geometry before its own fade | ModifyExpressionValue |
| `ShaderChunkRendererAccessor` | `ShaderChunkRenderer` | Expose the private static compiled-pipeline cache so it can be cleared on a render-state flip | Accessor |
| `ShaderChunkRendererBindGroupMixin` | `ShaderChunkRenderer` | Append the normal/material sampler slots, the PBR-settings uniform, and the reserved `u_GeomInput0..N-1` geometry-input sampler slots to the shared terrain bind-group layout | WrapOperation |
| `ShaderChunkRendererConstantsMixin` | `ShaderChunkRenderer` | Add a deferred-output shader constant for opaque/cutout passes only, while a pack is active | ModifyReturnValue |
| `ShaderChunkRendererDeferredPipelineMixin` | `ShaderChunkRenderer` | Build the five-attachment colour-target-state set for deferred pipelines, leaving translucent's single-target state untouched | WrapOperation |
| `ShaderChunkRendererShaderLocationMixin` | `ShaderChunkRenderer` | Redirect terrain shader compilation to the active pack's runtime shader (or the engine's built-in fallback with no pack active) | Redirect x2 |
| `SodiumWorldRendererOrchestrationMixin` | `SodiumWorldRenderer` | Bracket the opaque draw with the graph interpreter's per-frame prepare/finish | Inject x2 |
| `SodiumWorldRendererReloadMixin` | `SodiumWorldRenderer` | The single boundary where the render-state latch advances; also clears the static pipeline cache so the next draw recompiles under the new state | Inject |
| `SodiumWorldRendererRenderLayerMixin` | `SodiumWorldRenderer` | Populate shared per-frame render context and refresh PBR settings once per pass | Inject |
| `UniformBufferManagerMixin` | `UniformBufferManager` | Widen the shared per-frame uniform buffer and append the motion-vector/jitter fields; add a second small ring buffer for PBR settings | ModifyArg, Inject x2, WrapOperation |

**Vanilla/Blaze3D-targeting** (21):

| Mixin | Target | Purpose | Shape |
|---|---|---|---|
| `GameRendererMixin` | `GameRenderer` | Apply camera jitter; swap in/out the render-scale target; sequence SSAA downsample, TAA/TAAU reconstruct or MetalFX temporal upscale, scene-history handling, and jitter advance at frame tail | WrapOperation, Inject x2 |
| `GuiRendererCaptureMixin` | `GameRenderer` | Capture vanilla's HUD draw into `UiLayerCapture`'s transparent-background target whenever this frame produced a MetalFX-generated frame (`FrameGenPass.generatedFrameReady()`), then blend it back over the real native target so on-screen output is unchanged | WrapOperation |
| `PresentSeamMixin` | `Minecraft` | Present a MetalFX-generated frame through `windowSurface` immediately before vanilla's own `blitFromTexture(...)` call in `renderFrame`'s present section, so the swapchain sees generated → real in that order every armed frame | Inject |
| `CameraAccessor` | `Camera` | Expose the private `depthFar`, the per-frame far-plane distance `Camera.update()` derives, needed for `FrameGenPass` to feed `MTLFXFrameInterpolator`'s `farPlane` and linearize the reversed-Z depth | `@Accessor("depthFar")` |
| `FlameParticleLayerMixin` | flame particle render type | Tag flame particles as analytic/emitter light sources for the per-frame light list | Inject |
| `LevelRendererCloudsPassMixin` | `LevelRenderer` | Cancel vanilla's clouds pass when the active pack owns clouds (`GraphRunner.packOwnsClouds()`); commit `SkyFrameState`'s clouds tail (did-cancel flag + wind clock) either way | Inject (HEAD, cancellable) |
| `LevelRendererMixin` | `LevelRenderer` | Force the cached sky renderer to rebuild every frame, since supersampling swaps the render target it was captured against | Inject |
| `LevelRendererSkyPassMixin` | `LevelRenderer` | Cancel vanilla's sky pass when the active pack owns the sky (overworld, vanilla's own fog/mob-effect guards clear, `GraphRunner.packOwnsSky()`); commit `SkyFrameState`'s did-cancel flag either way (the sky's data comes from `SkyProbe`, not from here) | Inject (HEAD, cancellable) |
| `MinecraftPackRepositoryMixin` | `Minecraft` | Register the synthetic runtime shader pack as an always-present, hidden, top-priority repository source | ModifyArg |
| `PauseScreenMixin` | `PauseScreen` | Add a "Fornax" button to the pause menu, opening the YACL-hosted `FornaxSettingsScreen` | Inject (TAIL) |
| `ParticleLightMixin` | particle engine | Capture supported luminous particles into the per-frame analytic-light collector | Inject |
| `SmokeParticleLayerMixin` | smoke particle render type | Preserve the intended particle layer/light classification used by analytic-light harvesting | Inject |
| `TitleScreenMixin` | `TitleScreen` | Same Fornax icon button as the pause menu, on the title screen, so settings are reachable without loading a world | Inject (TAIL of init) |
| `ScreenAccessor` | `Screen` | Expose `width`/`height`/`minecraft` and invoke `addRenderableWidget` for mixins on `Screen` subclasses that can't `@Shadow` inherited-only members | Accessor, Invoker |
| `SpriteContentsAccessor` | `SpriteContents` | Expose the retained decoded atlas image for CPU-side voxel palette harvesting | Accessor |
| `TextureAtlasCelestialHookMixin` | `TextureAtlas` | Capture the celestials atlas (sun + all 8 moon-phase sprite UV rects) into `CelestialSprites` whenever it is (re)uploaded | Inject |
| `TextureAtlasBlockHookMixin` | `TextureAtlas` | Capture the live block atlas texture/view used by voxel cutout-occlusion and analytic-light passes | Inject |
| `TextureAtlasMaterialHookMixin` | `TextureAtlas` | Rebuild the material-map atlas whenever the block atlas is (re)uploaded | Inject |
| `TextureAtlasNormalHookMixin` | `TextureAtlas` | Rebuild the normal-map atlas whenever the block atlas is (re)uploaded | Inject |
| `VulkanRenderPipelineMixin` | `VulkanRenderPipeline` | Declare the widened 60-byte push-constant range on every terrain-family Vulkan pipeline layout | WrapOperation |
| `WindowMixin` | `Window` | Report the supersampled dimensions while a scaled frame is in flight, so downstream size queries stay consistent | ModifyReturnValue x2 |

**Raw-Vulkan-targeting** (2):

| Mixin | Target | Purpose | Shape |
|---|---|---|---|
| `GpuDeviceBackendAccessor` | `GpuDevice` | Expose Blaze3D's private backend so raw compute/interop code can require the Vulkan backend explicitly | Accessor |
| `VulkanDeviceExtensionMixin` | `VulkanDevice` | Add `VK_EXT_metal_objects` to the requested device-extension set on supported macOS systems | ModifyExpressionValue |

**YACL-targeting** (3):

| Mixin | Target | Purpose | Shape |
|---|---|---|---|
| `CategoryTabMixin` | `YACLScreen$CategoryTab` | Inject the Import.../Export.../Defaults... chrome buttons into YACL's own right-side button cluster (beside search/Reset/Undo/Done) on a `PackManageScreen`-built screen, scoped via `screen.PackChromeActions`; shrinks `descriptionWidget` by replacing its dimension supplier (via `OptionDescriptionWidgetAccessor`) rather than a one-off resize, since it re-reads that supplier every frame. Fails soft: any lookup/layout failure logs one warning total and leaves stock YACL chrome untouched, never crashing the screen | Inject (TAIL) x2 |
| `OptionDescriptionWidgetAccessor` | `OptionDescriptionWidget` | Expose the private final `dimensions` supplier so `CategoryTabMixin` can wrap it | Accessor |
| `YACLScreenCloseMixin` | `YACLScreen` | Route close/apply through the active pack edit session so staged pack values are not lost | Inject |

## 9. Material system

`blocks.toml` declares named categories, each listing block IDs and/or block tags
(`#namespace:path`). Categories are compacted into dense integer IDs, 1-based, in file declaration
order; ID 0 is reserved for "uncategorized" (pure LabPBR, no synthesis). IDs are capped so they fit
the 16-bit vertex channel that carries them.

**Resolution and thread safety.** Resolving categories against the live block/tag registry runs
only on the client thread (at pack (re)activation and again whenever datapack tags rebind). The
result publishes as a single, fully-immutable map assigned to a `volatile` field, a
single-writer/many-reader handoff with no locking anywhere. Sodium's background chunk-meshing
threads read that immutable snapshot directly; there is nothing to race, since a reader either sees
the old complete map or the new complete map, never a partially-updated one.

**Tag timing.** Direct block-ID entries resolve immediately, at any point. Tag-based entries do
not: block/item tags are not bound yet at the point a pack first loads (client startup, well before
any world's datapack tags are read), and querying an unbound tag throws rather than returning
empty. That specific exception is caught narrowly around only the tag-membership query itself,
logged once, and treated as "no members yet" rather than a load failure. A separate lifecycle
listener re-runs resolution (and requests a terrain remesh, since anything meshed before tags bound
already baked in ID 0 for tag-only categories) once tags are actually bound. Any other failure
during resolution still propagates normally; only the specific unbound-tag case is tolerated.

**Generated include.** Resolved categories are compiled into a generated GLSL include: per-category
`MAT_<NAME>` index constants, a `MAT_COUNT`, and parallel arrays (indexed by material ID) for
smoothness source/curve/minimum/scale, F0 mode, emissive source/strength, and per-category flags,
plus a dispatch function that switches on material ID to run any category's optional custom GLSL
body. `smoothness.scale` (`MAT_SMOOTHNESS_SCALE`, default 1.0) is a plain multiplier over the
category's authored LabPBR `_s` smoothness value, orthogonal to `source`/`curve`/`min`, which only
drive the Tier-2 gap-fill/override synthesis described in point 2 below and are meaningless when a
category's blocks already carry real `_s` data. A category may declare `smoothness = { scale = ... }`
with no `source` at all to get pure scaling with zero synthesis engaged. This include is generated
fresh on every load and every live rebuild, not only once at startup, since it must always match
whichever pack, and whichever compile-option state, is currently active.

**Three tiers**, from least to most pack effort:

1. **Pure LabPBR.** The default for any block not named in any category (including ID 0 itself).
   No synthesis; whatever specular/normal data the pack authored is used exactly as given.
2. **Albedo-luma synthesis.** A category supplies smoothness/F0/emissive parameters derived from
   the block's own albedo brightness. By default this only fills in values the pack left unauthored,
   but a category can instead force an override of authored data (used for categories like polished
   metals, where a uniform look across many blocks matters more than per-texture variation) via the
   category-level `force_override` key, which gates both smoothness and F0 synthesis. `emissive`
   carries its own independent `force` flag (`emissive = { ..., force = true }`) scoped to emission
   alone, since a pack may want a category's smoothness left gap-fill-only while still forcing its
   glow. This is needed when a texture author baked an explicit-zero LabPBR `_s` alpha (ore flecks
   in high-resolution labPBR packs), which the default gap-fill-only gate treats as "authored, leave
   alone" and `force_override` alone cannot reach without also overriding smoothness/F0. Both flags
   are generated into the same `MAT_FLAGS` uint array (bit 0 = `force_override`, bit 1 = emissive's
   `force`) rather than a second array, since they're both single per-category booleans consumed
   the same way, a bitmask test in terrain.fsh's Tier-2 gate.
3. **Per-category GLSL snippet.** A category can additionally supply a small GLSL body spliced
   directly into the generated dispatch function, for bespoke material behaviour no parameter table
   can express (for example pushing cut-gem blocks toward mirror-like reflectance).

## 10. Config

Engine-owned settings are kept to a minimum on purpose. Everything that used to be an engine-level
PBR/SSAO/TAA/reflection tunable now lives in the active pack's own options instead (see the
sibling pack's settings declarations). What remains is genuinely independent of any particular pack:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `shadersEnabled` | boolean | `true` | Master switch; `false` renders pure vanilla Sodium regardless of pack selection |
| `activePack` | String | `""` | Directory/zip name under `shaderpacks/` to load at startup; empty means no pack |
| `debugView` | enum | `OFF` | Which raw G-buffer attachment the resolve pass shows instead of the final lit image |
| `ssaaPreset` | enum | `X2` | SSAA factor only; applied by `SsaaManager` solely while `aaMethod` is `SSAA` (the method row owns on/off). `OFF` survives in the enum purely for legacy config deserialization and is normalized away by migration, never offered in the UI. The ladder is `X1_5`/`X2`/`X4`/`X8`/`X16`: `X9` was removed outright, a saved `X9` deserializes as `null` (Gson maps unknown enum constants to null) and migrates to `X4` (the nearest lower factor at the time of removal; `X8` arrived after that contract was fixed) |
| `aaMethod` | enum (`AaMethod`) | `TAA` | The engine's own AA/upscale method selector: `OFF`, `TAA`, `SSAA`, `TAAU`, `METALFX` (see below) |
| `taauRatio` | enum (`TaauRatio`) | `BALANCED` | TAAU/MetalFX render-resolution tier: `perAxisScale()` (0.77/0.67/0.58) drives the render target and `haltonSequenceLength()` (8/12/16) drives the jitter cycle |
| `taaBlendFactor` | float | `0.9` | Steady-state temporal history weight (migrated from the retiring pack option `u_TaaBlendFactor`), the cap the reconstruct pass's confidence ramp saturates at, not the weight of every frame; see "Temporal reconstruct" |
| `reconstructSharpen` | float | `0.5` | Contrast-adaptive sharpen strength `ReconstructPass` applies after the temporal blend; TAAU enforces a ratio-scaled floor over it (see "Temporal reconstruct") |
| `frameGeneration` | boolean | `false` | Experimental MetalFX frame generation: interpolates one frame between real frames. Requires `aaMethod = METALFX` and vsync (FIFO present mode); adds roughly 1 frame of latency when engaged. `FrameGenPacer` adaptively engages/disengages the actual double-present per frame (hysteresis around render fps vs. display refresh; see "Adaptive pacing" below), so arming this does not guarantee constant latency/cost, only that the setting is on and the machine genuinely needs the assist. macOS 26+ Apple Silicon only; the settings-screen toggle is built only when `MetalFxSupport.isFrameInterpolationAvailable()`, greyed out/hidden otherwise. Live-read every frame by `FrameGenPass.armed()` (no pack recompile needed). Turning it off, or switching `aaMethod` away from `METALFX`, releases `FrameGenPass`/`UiLayerCapture`/`FrameGenPresenter`'s interop resources via their `deactivate()` methods, but only at save time, through `SettingsApplyRouter`'s `FRAMEGEN_DEACTIVATE` action (the before/after field diff, same mechanism as `PACK_REAPPLY`/`SAVE_ONLY`), never from the option's own YACL listener: YACL applies every option's binding before any listener fires, so a listener sees the field already at its new value and can't tell whether this is the transition frame. The save-time diff is the only point that knows |
| `metalHud` | boolean | `false` | Apple's Metal Performance HUD overlay (`CAMetalLayer.developerHUDProperties`, public API since macOS 13), replacing the session-only `MTL_HUD_ENABLED` env var with a live-toggleable setting, independent of `aaMethod` (the HUD overlays whatever the compositor presents, regardless of which AA/upscale path is active). `metalfx.MetalHudControl.apply(enabled)` resolves the game window's `NSWindow` → `contentView` → `layer` (verified via `isKindOfClass:` to actually be MoltenVK's `CAMetalLayer`) and sets a `{"mode": "default"}` properties dictionary to enable or an empty `NSDictionary` to disable; both take effect on the next presented frame, no relaunch. Settings-screen toggle built only when `metalfx.objc.Objc.isLoaded()` (the FFM bridge itself, a lighter gate than `MetalFxSupport.isAvailable()`'s temporal-scaler-support probe, since the HUD needs nothing from the MetalFX framework). Applied on either transition direction via `SettingsApplyRouter`'s `METAL_HUD_APPLY` action (symmetric with `FRAMEGEN_DEACTIVATE`'s save-time-diff mechanism, but two-way rather than one-way), plus once at `CLIENT_STARTED` for a persisted-on config. Fails closed throughout: any resolution failure (no window yet, non-Metal content view, pre-13 macOS) logs once at WARN and does nothing |
| `schemaVersion` | int | `0` | Config-file migration marker only, not a rendering setting; see "Migration" below |

**Storage.** A plain Gson-serialized JSON file in the Fabric config directory. Load tolerates a
missing or corrupt file by falling back to defaults (logged, never thrown); a missing file is
written out immediately with current (default) values, stamped with
`FornaxSettings.CURRENT_SCHEMA_VERSION` before that very first write, since an unstamped fresh file
would be indistinguishable from a legacy one, and the next launch's migration would re-derive
`aaMethod` from `ssaaPreset` and silently clobber whatever the user picked in their first session.

**Migration.** `FornaxConfig.load()` runs `FornaxSettings.migrate(...)` on the deserialized object
before installing it, right after Gson parses the file and before returning. A legacy file (written
before `aaMethod` existed) deserializes with `schemaVersion` at its Java default of `0`; `migrate`
derives `aaMethod` from the one legacy signal that actually existed, `ssaaPreset` (nonzero implied
`SSAA`; anything else implied the old always-on `TAA`). The v2 step then normalizes a persisted
`ssaaPreset = OFF` to the factor default `X2`, ordered after v1 on purpose, since v1 needed OFF as
the legacy on/off signal (the method itself is untouched, so a file that had supersampling off keeps
rendering identically: the factor is inert unless the method is `SSAA`). A version-ungated step then
normalizes a `null` `ssaaPreset` to `X4`: null means the file held an enum constant this build no
longer has (`X9`, removed from the ladder; for a removed constant Gson maps the unknown name to null
rather than leaving the field initializer's value, the one exception to the absent-field rule
below), and that can appear at any persisted version, so it can't ride a schema gate. The v1 step
tolerates the null by construction (`null != OFF` still reads as "supersampling was on"). Finally
`schemaVersion` stamps to `CURRENT_SCHEMA_VERSION` (3, bumped for the X9 removal so an old file
holding it is rewritten on disk exactly once). Gson leaves any field absent from the JSON at
whatever the class's own field initializer sets it to (never null, never a compile error), the same
lesson the pack-option loader's default-value handling depends on. This is idempotent by
construction: a settings object already at `CURRENT_SCHEMA_VERSION` is returned unchanged, and
`FornaxConfig.load()` only re-`save()`s the file when migration actually changed something, so a
legacy file is migrated on disk exactly once.

**Engine AA/upscale method.** `AaMethod` (`OFF`/`TAA`/`SSAA`/`TAAU`/`METALFX`) replaces the
pack-owned `TAA_ENABLED` compile option as the single source of truth for whether, and how, a frame
gets a temporal resolve; `EngineDefines.forMethod`/`glslPreamble` (`pack.graph`) turn it into
`FX_TAA`/`FX_UPSCALE`/`FX_METHOD_OFF`/`FX_METHOD_TAA`/`FX_METHOD_SSAA`/`FX_METHOD_TAAU`/
`FX_METHOD_METALFX`, overlaid onto `GraphRunner.rebuild`'s `compileValues` (engine facts win over
anything a pack itself declares under those names, so a pack's own `enabled_if` can gate on them)
and, as literal `#define` lines, prepended to every fullscreen pass's shader source unconditionally
(via the same `insertAfterFirstLine` mechanism the generated `u_PackOptions` block uses), so GLSL
can `#ifdef FX_UPSCALE` directly. `CameraJitter` (`pass.taa`) re-keys off the same field: `OFF`/
`SSAA` jitter to `(0,0)`; `TAA` keeps the original 4-tap rotated grid; `TAAU` and `METALFX` use a
Halton(2,3) low-discrepancy sequence (`CameraJitter.haltonNdc`), its cycle length driven by
`taauRatio`. `GameRendererMixin`'s projection jitter and `UniformBufferManagerMixin`'s
jitter-uniform-upload gate both read `aaMethod.wantsJitter()` rather than a pack compile option.
Changing `aaMethod` from the Engine settings screen calls `PackReload.reapplyActivePack()`, a full
pack graph rebuild (the same class of action as a pack compile-option edit, since the `FX_*` defines
change which shader text compiles), but on purpose not `RendererReload.request()`: only the pack
graph's own fullscreen-pass shaders change, never the terrain pipeline shape `RendererReload` exists
to resync.

**Reload sequencing rule.** `RuntimeShaderPack.reload` republishes sources but the resource reload
it triggers is asynchronous: until its future completes, the shader manager resolves against the
previous resource snapshot. `GraphRunner.rebuild` returns that future, and every caller that pairs a
rebuild with `RendererReload.request()` (pack activation from the Shader Packs tab's Apply,
compile-option apply in `PackEditSession`) must chain the request on it via `thenRunAsync(...,
minecraft)`. Requesting immediately recompiles terrain against the stale snapshot: on a
None-to-pack activation that snapshot has no `fornax_runtime` sources at all ("Couldn't find
source", a hard crash at the next chunk draw); on a compile-option apply it silently compiles the
previous option values. The unload/error-revert directions may request immediately; they land on
the engine fallback terrain shader, which ships in the mod jar and exists in every snapshot.
Additionally, TAA/TAAU only engage their off-screen target + jitter when the G-buffer and
`sceneHistory` exist (`fornax$ssaaBeginFrame` guard); with shaders disabled or no pack they degrade
to plain rendering instead of reaching the reconstruct with nothing to bind.

The identical hazard applies to the pack graph's own fullscreen/mipchain/compute pass runners, not
only terrain. `GraphRunner.ensureRunnersBuilt()` (called every frame from `prepare()`) gates on a
`sourcesReady` flag that only flips true once `rebuild`'s own resource-reload future lands
(generation-guarded against a superseded rebuild's future landing after a newer one started), never
on `runnersBuilt` alone. A `FullscreenPassRunner`'s `RenderPipeline` names its fragment shader by
`Identifier` only; Blaze3D compiles it lazily on first bind against whatever the shader manager
currently resolves, so building a pipeline before the reload lands can pair a freshly-built
bind-group against the previous compile-value snapshot's shader text. Two pass variants sharing one
shader file behind different `enabled_if`s but declaring a different bind-group input count (for
example `resolve`/`resolve_hdr`/`resolve_hdr_el` sharing `gbuffer_resolve.fsh`, gating
`u_Input10`/`u_Input11` on `HDR_ENABLE`/`EMITTER_LIGHTS`) then fail to compile: Blaze3D logs
"Couldn't compile pipeline ...: Unable to find shader defined uniform" and marks that pipeline
object permanently invalid; a second bind of the same object throws `IllegalStateException: Pipeline
is not valid`, which used to propagate straight out of the pass loop and crash the game (an actual
failure caught in testing: a Settings Reset that toggled both `HDR_ENABLE` and `EMITTER_LIGHTS` off
in one Apply). Two independent layers now guard this: `ensureRunnersBuilt()` never builds a runner
from a stale snapshot in the first place, and `FullscreenPassRunner.run()` catches a bind failure
defensively (marks the runner `invalid`, logs once, skips the pass for the rest of its lifetime), so
even an unforeseen mismatch, a genuinely broken pack and not only this timing race, degrades to
"pass doesn't render" rather than crashing, the same philosophy `GraphRunner.finish()`'s
missing-runner skip already established.

**Render-scale generalization.** `SsaaManager` (`pass.ssaa`) started as an SSAA-only supersample
manager and is now the general render-scale manager for every method; the class/member names still
carry the "SSAA" prefix (kept to limit blast radius) but the javadoc there is the source of truth.
`applyCurrentScale()` (was `applyCurrentPreset()`) derives the frame's per-dimension `scaleFactor`
from `aaMethod`: `SSAA` → `ssaaPreset.linearScale()` (> 1.0), `TAAU` → `taauRatio.perAxisScale()`
(< 1.0), `TAA`/`OFF` → `1.0`. `isActive()` means `scaleFactor != 1.0` (was `> 1.0`, SSAA-only);
`needsOffscreenTarget()` is the separate, broader question of whether `aaMethod` needs an off-screen
target at all (`aaMethod != OFF`); `TAA` at scale `1.0` still needs one, since the temporal
reconstruct pass cannot read and write the same texture. `GameRendererMixin.fornax$ssaaBeginFrame`
gates the off-screen swap on `needsOffscreenTarget()`, not `isActive()`; `ensureScaledTarget(native *
scale)` yields a native-sized target for TAA, a genuinely smaller one for TAAU, and a genuinely
larger one for SSAA. At end of frame, `fornax$restoreNativeTarget` resolves that off-screen target
back into the native `mainRenderTarget`: SSAA alone gets the real box-filter downsample
(`SsaaDownsamplePass`, averaging supersampled texels, kept SSAA-only on purpose); TAA and TAAU both
get the engine-owned temporal `ReconstructPass` instead (see "Temporal reconstruct" below), which
replaced the interim `RenderScaleBlitPass` plain linear blit. `WindowMixin` needed no change for any of this: it
already scales `getWidth()`/`getHeight()` by `SsaaManager.getScaleFactor()` only while
`isFrameActive()`, and that arithmetic is correct unchanged for `scale < 1` (`round(x * 0.67)`
correctly reports the reduced TAAU size to viewport/scissor consumers) exactly as it already was for
`scale > 1`.

**Scene-colour history.** `SceneHistory` (`pipeline`) is an engine-guaranteed, ping-ponged target
(`sceneHistory`, `rgba8`, `history = true`) written unconditionally under every `aaMethod`,
including `OFF`. A pack's own SSR/resolve passes read `sceneHistory.history` as an ordinary input,
never `taa.history` from a pack-owned temporal-blend pass, so reflections no longer depend on any
particular AA method being active. Packs never declare this target: `GraphRunner.rebuild` injects
`SceneHistory.spec()` into the loaded pack's target set (`SceneHistory.injectInto`, idempotent
across rebuilds) so `TargetRegistry`/`TargetPlan` allocate and ping-pong it exactly like a
pack-declared history target; `GraphValidator` recognizes the bare name the same way it recognizes a
`builtin.*` reference, since the graph it validates at pack-load time predates any rebuild-time
injection. The same injected pair is costed as an explicit `(engine-injected)` row in the
validator's VRAM report, so the logged estimate never silently understates a pack by two full-size
colour textures. The write itself is not a graph pass; under `TAAU` the pack graph runs at render
(low) resolution, so a graph-level copy would capture the wrong basis. It runs from
`GameRendererMixin`'s single end-of-frame injection (`fornax$endFrame`, `renderLevel` RETURN), which
calls the off-screen-target restore (`fornax$restoreNativeTarget`), this copy, and the jitter advance
as explicit sequential statements rather than three sibling `@Inject`s whose relative order would
rest on mixin application subtleties. The copy therefore always reads the final native
`mainRenderTarget` colour: the resolved off-screen target (SSAA's downsample destination, or
TAA/TAAU's blit destination) when one was active this frame, or the plain native target the pack
graph's own resolve pass wrote straight into under `OFF`.

**Temporal reconstruct.** `ReconstructPass` (`pass.reconstruct`) is the engine-owned pass that
replaced `RenderScaleBlitPass` for both `TAA` and `TAAU`; SSAA keeps its own box-filter
downsample untouched. It reads the low-res resolved scene colour (`lowResSource`, native-sized for
TAA, below-native for TAAU), `builtin.gMotion`/`builtin.depth` at render resolution, and the
previous frame's final native colour from `SceneHistory.reconstructReadSlot` (the post-swap current
slot; see the read-phase rule below), and runs as two render passes that split accumulation from
presentation: pass 1 (`post/reconstruct`) renders the unsharpened temporal accumulation (rgb) plus
the accumulation age (a) directly into `SceneHistory.writeSlotView`, the same post-swap slot and
phase the end-of-frame copy would write, so under TAA/TAAU the pass replaces that copy outright
(`GameRendererMixin` skips it via a per-frame flag); pass 2 (`post/reconstruct_sharpen`) reads that
accumulation and writes the sharpened presentation into the native target, alpha restored to 1.0.
The split is a rule, not an optimization: a sharpened output that becomes next frame's history gets
edge enhancement re-applied to its own output every frame at the blend's roughly 0.9 recycle rate,
which is divergent iteration, caught in testing as red/rainbow speckle webs on distant foliage
(worst exactly where per-channel contrast disagrees and detail is sub-pixel; near geometry's high
contrast zeroed the sharpen weight, which is why it stayed clean). Sharpened pixels exist only in
the presented frame, never in the accumulator.

**Responsive-pixel masking (first-person exclusion).** The reconstruct runs at `renderLevel`
RETURN, so its source colour includes the first-person hand, held items (their translucent phases
too), and screen overlays, none of which exist in G-buffer motion/depth (they're vanilla-drawn
after the engine's depth copyback). Left unmasked, near-zero terrain motion behind the swinging
hand validates history and ghosts it to roughly 10% opacity. The mask: the pass additionally binds
the render target's own depth (`u_SceneDepth`, cleared far before the hand, then vanilla wrote
first-person depth into it), and wherever it is meaningfully nearer than the terrain-only G-buffer
depth (reversed-Z: larger by more than `HAND_DEPTH_EPSILON`, 0.005, roughly a 10m-equivalent step
at the 0.05 near plane) and itself inside the first-person volume
(`FIRST_PERSON_PROXIMITY_DEPTH`, 0.02, nearer than 2.5m) the pixel blends fully current with its
age reset, rendered fresh every frame, no ghost, no accumulation, crisp at render-res. The proximity
bound is load-bearing, not belt-and-braces (an actual failure caught in testing): the delta alone
also fires on water, since a translucent surface writing scene depth over a deeper seafloor's
G-buffer depth is the identical signature as the hand. Hand/held items always satisfy proximity;
water beyond arm's reach never does (an accepted edge case: chest-deep water pixels inside 2.5m mask
into a tiny, fully-current region).

The same two signals drive a three-tier history weight. Tier 1, opaque surfaces (no depth delta):
the full 1/n-ramped weight up to `taaBlendFactor`, unchanged. Tier 2, translucent-overlay regions
(delta at any distance, such as water, glass, and every forward-pass surface, since they carry no
motion or material data and the depth-delta signature is their detector): the weight additionally
caps at `TRANSLUCENT_OVERLAY_HISTORY_CAP` (0.5, a shader constant per the established precedent, the
tunable knob). This is the standard responsive-surface treatment for animated translucents: water's
surface texture is animated while its motion vectors are the seafloor's static ones, so
full-strength accumulation averages the waves to flat colour (an actual failure caught in testing).
The retired native-res `taa_blend` got away with it because its 3×3 clamp box spanned 3 native
pixels and each frame's animation displaced history through the clamp; under TAAU at ratio 0.67 the
same 3-source-texel box spans roughly 4.5 native pixels and the low-res source attenuates wave
amplitude, so the clamp stops doing that job and the wash dominates, and the cap restores per-frame
animation at least at half strength while still smoothing edges. Age is not reset in tier 2 (that is
tier 3's job alone). Tier 3, first-person pixels (delta and proximity): zero history, age reset, as
above. `ReconstructHandMaskTest` pins the two-part predicate and the three-tier weight math against
the shipped constants with real reversed-Z numbers, including the water cases. The hand's projection
never carried jitter anyway (`hud3dProjectionMatrixBuffer.getBuffer(Projection)` is a different
overload from the level fetch the jitter wrap targets, bytecode-verified), so a full-current hand
does not wobble.

At ratio 1.0 (TAA) the shader is functionally equivalent to the retired `taa_blend.fsh`, held there
by a line-by-line diff against it: point current-frame sample at `texCoord` (no un-jitter, no
kernel; "un-jittering" a native-res frame only bilinearly smears texel centres, the softness bug an
earlier revision shipped), point motion fetch at `texCoord`, the same 3×3 neighborhood clamp around
`texCoord` with the same `[0,1]` seeds, the same in-bounds + depth-similarity validity test with the
same `0.05` threshold, and the same `mix(current, clampedHistory, blendFactor)` at steady state.
Divergences exist only where justified: below ratio 1.0 (TAAU) the current sample becomes a nine-tap
optimized Catmull-Rom at the un-jittered position, `texCoord` plus `jitter/2`, since the jittered
projection shifts rendered content by `+jitter/2` in UV (the same offset `terrain.vsh` subtracts
from motion vectors), and the motion fetch becomes 3×3 depth-dilated (reversed-Z closest-depth wins;
upscaling magnifies the edge ghosting this kills); the confidence ramp and sharpen below apply at
every ratio. Validity intentionally accepts smooth motion: walking reprojects roughly 6 px/frame
with a reversed-Z depth delta of roughly 0.0004 on continuous surfaces, far under the threshold
(`ReconstructValidityMathTest` pins these numbers against the shipped shader source). Anti-ghosting
in motion is the clamp's job, exactly as in the old pass.

The temporal blend is confidence-ramped, not fixed: the output alpha channel carries each pixel's
accumulation age (frames since reset, normalized to the shader's `CONFIDENCE_FRAMES` cap of 32). The
age lives only in sceneHistory's alpha (pass 1 writes it with the accumulation and reads it back
next frame; the presented framebuffer's alpha is 1.0 again after pass 2) and no pack consumer reads
it (audited: pack `ssr_trace`/`gbuffer_resolve` passes sample `sceneHistory.history` as `.rgb`
only). Each frame the history weight is `min((n-1)/n, taaBlendFactor)` for n frames accumulated: a
running 1/n average while young (0 on a fresh sample, 0.5 on the second frame, 0.875 by the eighth),
saturating at the `taaBlendFactor` steady state (frame 10 at the 0.9 default), so a static scene
reaches full sharpness in roughly 8-12 frames instead of crawling there under a fixed 0.9
exponential. The age resets on two events: a validity failure (branchless: the zeroed age zeroes
the weight, degenerating the mix to the pure current sample, exactly the old pass's else-branch;
history reads clamp-to-edge so the discarded out-of-range fetch is safe), and a material clamp
rewrite, where the neighborhood box pulls history by more than jitter-shimmer noise
(`smoothstep(0.02, 0.1)` on the rewrite distance). There the stored age re-youngs while this frame's
blend weight stays untouched (preserving old-pass steady-state equivalence, since the old pass also
blended the clamped value at the full factor), so the frames following a content change ramp from
young, which is what makes stopping after motion sharpen in roughly 10 frames instead of roughly 30.
Motion stability is untouched: the clamp still bounds what history contributes, and the ramp only
ever lowers the history weight below the steady-state cap. History alpha from frames written under
`OFF`/`SSAA` (scene alpha, roughly 1.0) reads as full confidence, giving a seamless
steady-state hand-off when the user switches methods mid-session.

The presentation sharpen (`reconstructSharpen`, contrast-adaptive, `post/reconstruct_sharpen`) is
luma-driven, one scalar weight for all three channels; per-channel weights let a flat channel
sharpen hard while a busy one doesn't, causing chromatic ringing exactly where channels disagree
(red speckle on green foliage, the other half of the corruption caught in testing). It gets a
ratio-scaled floor: the effective strength is `max(setting, 1 - ratio)`, which is 0.23/0.33/0.42 for
the Quality/Balanced/Performance TAAU tiers, 0 at ratio 1.0, since temporal upscaling reconstructs
softer than native rendering. TAA honours the setting exactly, and at 0.0 the pass degenerates to a
pure copy (integrity first: the floor is a presentation aid, never a substitute for correct
reprojection). Its taps step by the output texel size; the accumulation is native-res regardless of
render scale. `u_RatioIsOne` is derived by `ReconstructPass.reconstruct` itself by comparing
`lowResSource`/`nativeDest` dimensions, never passed in by the caller, so TAA and TAAU share one
dispatch path with no separate flag to keep in sync. Settings ride a dedicated 48-byte std140
`u_ReconstructSettings` block (`MappableRingBuffer`, rotated per call): two texel-size vec2s
(source/output), the jitter NDC offset, blend factor, sharpen strength, and the ratio flag, in that
std140 offset order (0/8/16/24/28/32, padded to the 48-byte block boundary);
`ReconstructSettingsTest` pins the layout at the pure-JVM level. `GameRendererMixin.fornax$reconstruct`
resolves the G-buffer/`sceneHistory` inputs and throws rather than silently misbehaving if either is
unexpectedly absent (shaders disabled or no pack loaded while `aaMethod` is still `TAA`/`TAAU`,
independent settings, so this combination is reachable, unlike the terrain G-buffer redirect's own
"should be unreachable" null check).

**MetalFX temporal upscale.** `AaMethod.METALFX` uses the same low-resolution target and Halton
jitter contract as TAAU, but `MetalFxUpscalePass` tries an `MTLFXTemporalScaler` before the engine
reconstruct. Availability is probed once (`MetalFxSupport`): macOS/aarch64, a working Java FFM
bridge, a Metal device, and `MTLFXTemporalScalerDescriptor.supportsDevice:` are all required. The
settings UI only offers the method when that probe passes; a persisted value on unsupported
hardware, a missing reactive-mask API, or any runtime encode failure falls back to TAAU for the rest
of the session and logs once.

The bridge is pure Java (`metalfx.objc.Objc`): Java FFM (the foreign-function-and-memory API, which
lets Java call native code directly) downcalls `libobjc`, Metal, and MetalFX directly, with no JNI
library or bundled native artifact. Launchers must pass `--enable-native-access=ALL-UNNAMED` (the
Gradle test task and README carry the same requirement) so JEP 472 restricted calls do not warn
today or hard-fail on a future JDK.

Four render-resolution inputs are copied into `VK_EXT_metal_objects` exportable images: LDR colour
(`rgba8`), terrain motion (`rg16f`), reversed-Z depth (`d32f`), and an R8 reactive mask. The mask is
generated immediately before copy-in by `MetalFxReactiveMaskPass` from the same
scene-depth-minus-G-buffer-depth predicate as the engine reconstruct: near first-person pixels are
1.0 (ignore temporal history), while other translucent overlays are 0.5 (favour current animated
water/glass without discarding all accumulation). Descriptor/scaler selectors for reactive masks are
checked at runtime before use; MetalFX versions predating macOS 14.4 fail closed to TAAU.

Cross-API ordering uses one exported Vulkan timeline semaphore / `MTLSharedEvent`: Vulkan copy-in
signals `v`, Metal waits `v`, encodes and signals `v+1`, Vulkan waits `v+1`, copies the unsharpened
native result directly into `SceneHistory.writeSlot`, then signals `v+2`. The steady-state path has
no host wait. Resize waits once for the last `v+2` value before destroying the whole interop image
set; it does not call `vkDeviceWaitIdle` per image. Interop images use transfer-optimal layouts for
the Vulkan copies and return to `GENERAL` for Metal, with transfer/graphics access masks instead of
the original all-commands/all-memory barriers. A `fornax.metalfx.hardSync` escape hatch retains the
host-serialized diagnostic path.

The raw MetalFX result becomes scene history before presentation sharpening, matching the
reconstruct feedback rule. `ReconstructPass.presentSharpened` reuses `post/reconstruct_sharpen.fsh`
to write the native target with the same render-ratio-scaled floor; the sharpened presentation never
feeds MetalFX/SSR history. Jitter and motion-vector conversion is centralized in
`MetalFxConventions`: NDC-up jitter becomes input-pixel-down coordinates, and `currentUV -
previousUV` motion uses negative input dimensions so MetalFX reconstructs the previous pixel.
Synthetic unit tests pin both signs; the legacy `jitterFlipX/Y` and `mvFlip` flags remain explicit
diagnostic overrides rather than the only way to validate the convention.

**MetalFX frame generation.** `FrameGenPass` sits one seam past `MetalFxUpscalePass`: the mixin seam
(`GameRendererMixin.fornax$reconstruct`) calls it immediately after `MetalFxUpscalePass
.runIfEnabled` returns `true`, so it is only ever reachable behind a successful MetalFX upscale for
the frame, a structural coupling, not a runtime check. It runs an `MTLFXFrameInterpolator` over a
three-image native-resolution colour ring (`prevColor`/`curColor`/`generated`) it owns outright, on
its own `VulkanMetalInterop.SharedTimeline` with its own independent counter, entirely separate from
the upscale pass's timeline. It reuses that frame's already-populated render-resolution depth/motion
interop images via two package-private accessors, `MetalFxUpscalePass.depthInterop()`/
`motionInterop()`, rather than re-copying them. It never touches `SceneHistory`: the upscale pass
already wrote the frame's unsharpened result into the write slot before this pass runs, so the
sceneHistory write-phase rule above doesn't apply here; there is nothing to get backwards.

Per frame: a Vulkan copy lands the upscale pass's native colour into `curColor`, signaling `v` on
the pass's own timeline. Once two frames of history exist and `FrameClock.ready()` (EMA-smoothed
wall-clock delta, hitch-clamped), Metal GPU-waits `v`, encodes the interpolator against
`prevColor`/`curColor` plus the reused depth/motion textures, writes `generated`, and signals `v+1`,
zero host stalls in the steady state, the same discipline `MetalFxUpscalePass` uses.
`prevColor`/`curColor` then pointer-swap (no copy) for the next frame's ring position. Arming
requires all of: `-Dfornax.framegen=true`, no prior failure this session, `AaMethod.METALFX` active,
and `MetalFxSupport.isFrameInterpolationAvailable()`; any encode failure fails the pass closed for
the rest of the session (`markFailed`), like every other MetalFX path.

`FrameGenPass.copyGeneratedInto(dest)` is the only way a generated image leaves the `metalfx`
package (`VulkanMetalInterop.InteropImage` stays package-private): it waits the pass's `v+1` and
signals `v+2` around a Vulkan copy `generated -> dest`, mirroring the upscale pass's own copy-back.
`-Dfornax.framegen.debugView=true` calls it at the end of every armed `runIfEnabled`, replacing the
presented frame with the generated one each time, a way to visually verify the whole Metal path
(coherent, roughly 1-frame-delayed motion) before any present-path integration exists to actually
splice generated frames into the output cadence.

**UI-layer capture.** A generated (interpolated) frame is assembled entirely from
`MetalFxUpscalePass`'s already-produced native colour, upstream of vanilla's HUD draw, so it never
carries a HUD of its own. `GuiRendererCaptureMixin` (`mixin.vanilla`) brackets the single
`GuiRenderer.render()` call inside `GameRenderer.render` with one `@WrapOperation` (mirroring
`GameRendererMixin#fornax$endFrame`'s "one atomic method body, not two order-dependent sibling
injects" reasoning) gated on `FrameGenPass.generatedFrameReady()`: when true, it shadow-swaps
`mainRenderTarget` (the exact same field-reassignment technique `fornax$ssaaBeginFrame`/
`fornax$restoreNativeTarget` use) to `UiLayerCapture.uiTarget(realTarget.width, realTarget.height)`
for the duration of the HUD draw, then restores the real target and blends the captured layer back
over it (`UiLayerCapture.compositeOnto`) so the on-screen native frame is pixel-identical to vanilla.
When ungated (the overwhelmingly common case), the wrap has zero effect on behaviour:
`original.call(instance)` runs untouched. `UiLayerCapture` (`pass`) owns a lazily-built RGBA8
`MainTarget` (`ensureSize`, mirroring `SsaaManager.ensureScaledTarget`'s rebuild-then-destroy order)
sized from the caller-supplied width/height, not any independently-derived value; the mixin passes
the real `mainRenderTarget`'s own `.width`/`.height` at capture time, exactly the target vanilla's
HUD draw would otherwise have used. (A `SsaaManager.nativeWidth()/nativeHeight()`-based size was
tried first; on a Retina display it resolved to logical-point dimensions instead of the
physical-pixel size every other target in the chain uses, stretching the composited HUD into a
corner quadrant at roughly half size. This was caught via log/screenshot and reverted in favour of
reading the real target's own dimensions, which cannot disagree with what it was sized for by
construction.) `uiTarget` clears to transparent zero via a clear-only render pass (mirroring
`TargetRegistry.clear`'s convention) every time it activates, and `compositeOnto(dest)` reads back
with a real pipeline blend state (`ColorTargetState`'s `BlendFunction` slot set to
`BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA`, `ONE`/`ONE_MINUS_SRC_ALPHA`, not plain
`TRANSLUCENT`'s `SRC_ALPHA`/`ONE_MINUS_SRC_ALPHA`, since vanilla's HUD draw already blends its own
translucent elements straight-alpha against the transparent-cleared target, leaving the buffer
premultiplied, so `post/framegen_ui_composite.fsh` only samples and outputs the UI texel as-is)
against any destination, any number of times: the capturing mixin's own restore call, and the
present seam below stamping the identical captured layer onto the generated frame before it reaches
the screen, via `UiLayerCapture.activeThisFrame()`/`compositeOnto`.

**Present seam (double-present).** `PresentSeamMixin` (`mixin.vanilla`) injects
`FrameGenPresenter.presentGeneratedIfReady(windowSurface, encoder)` into
`Minecraft.renderFrame(boolean)`, immediately before vanilla's own late-section
`windowSurface.blitFromTexture(...)` call, not before `acquireNextTexture()`, a first version's site
that a code-review pass caught as temporally wrong (bytecode reasoning below) before this fix.

Discovery on the deobfuscated jar (26.2, `javap -c -p` on `Minecraft.renderFrame`) found the
acquire/blit/present triple is not grouped at present time: `acquireNextTexture()` runs early in the
method, before any of the frame's extract/render work; `GameRenderer.render()` (which reaches
`renderLevel()` and, deep inside it, `FrameGenPass.run()`, the call that actually produces this
frame's generated image) runs after that early acquire; `blitFromTexture`/`present()` both run later
still, in the method's own `"present"` profiler section, each individually preceded by its own
`windowSurface.isAcquired()` check. `GpuSurface` itself (also inspected directly) tracks acquisition
with two private booleans, `hasImageAcquired`/`hasBlittedTexture`, exactly one outstanding acquire
and one blit per acquire cycle, no per-call handle, confirming there is no second-acquire path to
exploit.

Injecting before `acquireNextTexture()` (the first version) ran before this same invocation's own
`GameRenderer.render()` had produced this frame's generated image; `generatedFrameReady()` there
could only ever reflect the previous invocation's result, `G(N-2,N-1)`. Presenting that stale image
immediately before real frame N gave presented scene-times that step backward every other frame
(`..., N-1.5, N, N-0.5, N+1, N+0.5, ...`), oscillating judder rather than smoothing, caught by
review and confirmed by bytecode inspection, and fixed by moving the injection to the late site.
Firing instead immediately before vanilla's own `blitFromTexture` call, after this invocation's
`GameRenderer.render()` has already run, means `generatedFrameReady()` reflects this frame's own
interpolation (`G(N-1,N)`, time `N-0.5`), giving a monotonic presented sequence: `..., N-1, N-0.5,
N, N+0.5, N+1, ...`.

The late site remains safe against the single-acquire-slot invariant: vanilla's own earlier
`isAcquired()` check (further up the same "present" section) has already passed by the time
execution reaches this call site, so the surface is guaranteed acquired-but-not-yet-blitted on
entry. `FrameGenPresenter.presentGeneratedIfReady` (`pass`) gates on
`FrameGenPass.generatedFrameReady()`, then on `PresentMode` being `FIFO`/`FIFO_RELAXED` (vsync
required, since frame generation only makes sense self-paced against the compositor's own cadence;
`IMMEDIATE`/`MAILBOX` do nothing with one line logged once, never once per frame). Staging assembly
is split across the frame: `FrameGenPresenter.prepareGeneratedFrame` runs earlier, from
`GuiRendererCaptureMixin`'s HUD-capture wrap (the only point with a scene-only, HUD-free native
colour available), and lazily builds/resizes a native-resolution RGBA8 `MainTarget` staging target
sized off `SsaaManager.nativeWidth()/nativeHeight()`, not the surface's own
`Configuration`, which is the (possibly 2x, Retina) swapchain size and previously stretched the
composited HUD into a corner. It records `FrameGenPass.copyGeneratedInto(staging)`, then
`FrameGenSkyFillPass.compositeOnto(staging, ...)`, the unified real-frame fill, filling any
generated pixel from the current real frame's own colour wherever one of three predicates fires:
(1) **sky** (reversed-Z depth at the far-plane clear value; sky/clouds write no `gMotion`), (2)
**responsive pixels** (the same scene-depth-vs-G-buffer-depth mismatch `MetalFxReactiveMaskPass`
uses to build the temporal scaler's own reactive mask: particles, the first-person hand,
translucent overlays, none of which the deferred G-buffer or its motion vectors ever saw), and (3)
**edge disocclusion** (a motion-gated native-res border band, feathered at its inner edge, plus any
pixel whose backward- or forward-reprojected UV falls outside `[0,1]`); see
`framegen_sky_fill.fsh`'s own header for the exact thresholds and their citations. Still inside
`prepareGeneratedFrame`, `UiLayerCapture.compositeOnto(staging)` then stamps the captured HUD over
the filled result when one was captured this frame. `presentGeneratedIfReady` runs later (the
present seam proper), re-checking `PresentMode` and consuming that already-prepared `staging`:
`blitFromTexture(encoder, staging.getColorTextureView())` into vanilla's already-acquired image →
`present()` (clearing the acquired flag) → `acquireNextTexture()` again for a fresh image, leaving
the surface exactly acquired-not-blitted again, precisely what vanilla's own immediately-following,
completely untouched `blitFromTexture`/`present()` calls expect for the real frame.

Each of the three surface-mutating calls gets its own `try`/`catch(Throwable)` into
`FrameGenPass.markFailed` (failing closed for the rest of the session like every other MetalFX
path), reasoned individually rather than one blanket catch, since `GpuSurface` only flips each
internal flag after its backend call succeeds (bytecode-verified): a staging/blit-G failure leaves
the surface untouched or still acquired-not-blitted, so vanilla's own blit/present run completely
normally, presenting real frame N as if this seam never fired. A `present()` failure (after a
successful blit) or a reacquire failure (after a successful present) both leave the surface in a
state where vanilla's own untouched `blitFromTexture` call, which runs unconditionally right after
this method returns, with no per-call `isAcquired()` guard at that exact call site, will itself
throw uncaught, propagating exactly like any other backend failure in this region of
`Minecraft.renderFrame`, which carries no exception handler at all for either call even in stock
vanilla. This is left uncancelled on purpose rather than synthetically recovered: the only way to
skip vanilla's own following blit/present without touching its indices would be cancelling the rest
of `renderFrame` mid-method, after its `"present"` profiler section is already pushed with no
matching pop reachable from a cancel at this site, trading one loud, log-preceded crash for either
an unverified profiler-stack corruption or (worse, in the post-blit-failure case specifically) a
silent, permanent render-loop freeze on every following frame, since nothing else in `Minecraft`
ever calls `present()` to clear a stuck `hasImageAcquired`. A wedged render loop is this engine's own
standing worse-than-a-crash case (see `VoxelDebugRaymarchPass`'s history), so these two rare windows
are left to crash loudly instead.

Also carried in from this work: `FrameGenPass.runIfEnabled` now clears `generatedReady = false` when
`armed()` is false, not only inside `run()`. Previously, arming flipping false mid-session (a config
change, a prior failure) left a stale `true` from the last armed frame in place forever, which would
have had this present seam keep trying to present a frame the pass never actually produced.

**Adaptive pacing (engage/disengage hysteresis).** The double-present seam above is no longer
unconditional. A live-measured problem drove this: under FIFO/vsync the compositor accepts exactly
one image per vblank (the monitor's refresh interval), and double-present submits two (generated,
then real) every armed frame. This caps the real frame rate at `displayHz / 2` regardless of how
fast the machine could otherwise render. On a 120Hz panel, a scene the hardware could already render
at 90fps was throttled down to 60 real + 60 generated frames; frame generation must never make the
real frame rate worse than doing nothing. `FrameGenPacer` (`pipeline`) fixes this: it only "engages"
the interpolator (and therefore the double-present) when render fps is meaningfully below what the
display can already show natively. Every armed frame, `FrameGenPass.runIfEnabled` calls
`FrameGenPacer.update(CLOCK.emaIntervalNanos())`, the single per-frame decision point both this
method's own arming and the present seam trace back to (see `FrameGenPacer`'s own header), which
compares render fps against the display refresh rate (`Window.getRefreshRate()`, confirmed by
bytecode inspection as `GLX._getRefreshRate`: `glfwGetWindowMonitor` falling back to
`glfwGetPrimaryMonitor` when windowed, then `glfwGetVideoMode(...).refreshRate()`; falls back to
60Hz and logs once if that ever reports non-positive) with hysteresis: engage below `0.40 ×
displayHz`, disengage above `0.48 × displayHz`, hold the previous state anywhere in between. A
single shared threshold would flap engaged/disengaged every few frames for any scene hovering near
that one value, each flap costing a re-engage warm-up gap and a visible generated-frame pop-in/out;
the band absorbs that jitter.

**Both thresholds sit strictly below 0.5. This is load-bearing, not a tuning preference** (a
code-review catch against the first version of this pacer, which used a straddling 0.45/0.55 band):
while engaged, this pass's own double-present pins the measured render-thread loop, the exact
`emaIntervalNanos` this decision reads, at very close to `displayHz / 2` (the mechanism the opening
paragraph above describes: FIFO accepts one image per vblank, engaged submits two per loop
iteration). That measured rate is a ceiling while engaged, not a typical sample; `renderFps`
asymptotically approaches `0.5 × displayHz` from below and can never exceed it. A disengage
threshold at or above 0.5 is therefore mathematically unreachable once engaged: `renderFps >
DISENGAGE_FRACTION × displayHz` is permanently false, latching the engaged state for the rest of the
session regardless of how much real headroom the machine has; a transient dip that triggers one
engage never recovers. `0.40`/`0.48` keep the whole band under that 0.5 ceiling so `0.48 ×
displayHz` sits below the roughly `0.5 × displayHz` engaged measurement and the disengage branch
stays reachable; a scene rendering genuinely below `0.40 ×` displayHz (measured from the disengaged,
uncapped state, so this threshold is not subject to the same self-throttling) still correctly
engages and stays engaged.

While disengaged, `runIfEnabled` returns before calling `run` at all: no Vulkan copy into
`curColor`, no `MTLFXFrameInterpolator` encode, `generatedReady` forced false, so a disengaged frame
is a zero-framegen-cost single-present, and both `GuiRendererCaptureMixin` and this present seam
take their normal ungated ("no generated frame") branch, exactly as if generation were unarmed; no
separate disengaged-case code exists in either. The one exception is `CLOCK.markFrame`, which still
runs unconditionally first, every frame; render-fps measurement can never stop, since it is the only
signal `FrameGenPacer` has to decide when to re-engage. On the true→false (engage→disengage)
transition only, `hasHistory` is force-cleared and `pendingReset` set (mirroring `ensureResources`'s
own resize path). Of the two designs considered for what to do with the colour ring while
disengaged (keep copying into it every frame to avoid any warm-up gap, versus drop it and re-warm on
return), this picks the simpler, strictly-cheaper option: without the clear, the first re-engaged
frame would interpolate against a `prevColor`/`curColor` pair frozen from whenever disengagement
began, ghosting scaled to however long that window lasted. Clearing it instead costs exactly one
frame of no-generation on re-engage (history rebuilds over two frames, same as a fresh arm),
regardless of how long the disengaged window ran. `FrameGenPacer.reset()` (disengaged, counters
zeroed) is called from `FrameGenPass.deactivate()` so a later re-arm always starts from a clean
measurement.

Instrumentation: the cadence log line (`-Dfornax.framegen.log=true`, `FrameGenPresenter`) gains
`paced=engaged|disengaged` (the current state, every roughly 5s line) and `frames(engaged=…,
disengaged=…)` (a tally over that same window, extending the existing `skips(...)` counters; both
counter sets are read-and-reset together on the same cadence). The threshold values themselves
(`0.40x`/`0.48x`/the detected `displayHz`) are logged once total, not every line, the same one-time
convention `loggedNonFifoSkip`/`FrameGenPass`'s own `loggedConventionsOnce` already use.

**Known limitations.** (a) Residual thin-line/high-frequency optical-flow artifacts on generated
frames are an accepted floor of this experimental interpolation tier: none of
`FrameGenSkyFillPass`'s three fill classes (sky, responsive pixels, edge disocclusion) target them,
since they arise from `MTLFXFrameInterpolator`'s own motion estimation on thin/high-frequency
geometry rather than from any gap this engine's own fill coverage leaves. (b) The 1x1 R32_FLOAT
auto-exposure target (pack graph `exposure`, with history) is not wired into the interpolator:
`MetalFxSupport.logFrameInterpolatorSelectors` only probes whether `MTLFXFrameInterpolator` responds
to `setExposureTexture:`/`setExposure:` (diagnostic-only instance selectors, logged once at
startup). Exporting the exposure target through the same Vulkan/Metal interop this pass already uses
for colour/depth/motion is a separate, larger change gated on that probe's verdict from a future
launch log, not attempted yet.

**sceneHistory write phase (a rule, not a preference).** The copy writes the post-swap history slot
(`SceneHistory.writeSlot`), never `current`. `TargetRegistry.swapHistory()` runs at the end of
`GraphRunner.finish()`, which is mid-`renderLevel`, before the return-time copy, and next frame's
graph passes read `sceneHistory.history` before next frame's own swap. Writing `current` (the
intuitive choice) parks the frame's colour where readers only see it after one more swap:
two-frames-stale reflections in steady state, and a still-black history on the second frame ever.
The rule has a read side with the opposite answer per phase: a pre-swap reader (any pack pass, SSR
or resolve) reads `sceneHistory.history` for one-frame-old colour, but a post-swap reader (the
engine reconstruct pass, at `renderLevel` RETURN) must read the post-swap current slot
(`SceneHistory.reconstructReadSlot`), because the swap has already moved last frame's copy there. Naively
mirroring the pack passes' `.history` read post-swap serves two-frame-stale colour; under a 0.9
temporal blend that is a full frame of velocity-proportional trailing on every camera move (the
"drunk walk" reconstruct bug, caught in testing). Under TAA/TAAU the write side is the reconstruct's
accumulation pass itself (`SceneHistory.writeSlotView`, the same slot the copy names); the copy
remains the writer under OFF/SSAA. `SceneHistoryPhaseTest` simulates the swap→write→read cycle frame
by frame, including a full two-frame, three-consumer timeline (SSR pre-swap read, reconstruct
post-swap read + write), and fails on the wrong slot for any of them. `SceneHistory` declares itself
`output`-basis (see §5's target model), so it always sizes off native output resolution regardless
of what resolution the graph itself ran at; the copy still clamps its width/height to `Math.min` of
both textures, the same defensive convention `CopyRunner`'s own copy passes use, so a transient size
mismatch (a resize race, or a rebuild landing mid-frame) never produces a GPU out-of-bounds copy.

**Apply semantics and the render-state latch.** Whether the pack graph is currently driving
rendering is tracked by a single latched flag, kept separate from the live config on purpose
value. Everything that decides the shape of a compiled terrain pipeline, which shader source
compiles, whether a deferred output constant is added, how many colour attachments a pipeline
declares, reads only this latch, never `shadersEnabled`/`activePack` live. The latch advances at
exactly one boundary: Sodium's own renderer-recreation path, which runs synchronously between frames
(never mid-draw) on both of the paths that reach it (Sodium's own forced-reload flag handling, and
this engine's own explicit reload request). That same boundary also clears Sodium's process-wide
compiled-pipeline cache, since recreating the renderer alone does not recompile anything; the cache
would otherwise happily keep serving pipelines built under the previous pipeline shape.

Live config reads are forbidden anywhere pipeline shape is decided, because a config toggle and a
recompiled pipeline are not atomic: a toggle applied while a frame is mid-flight could flip what a
live read returns before the pipelines actually compiled under the new shape exist, and Sodium's own
pipeline cache would keep serving the stale ones regardless. The observed failure is a runtime
render-pass error demanding that the number of bound colour attachments match the compiled
pipeline's declared colour-target-state count, the two having silently drifted apart for exactly one
torn frame. Latching removes the possibility of that frame ever occurring, at the cost of one extra
level of indirection everywhere pipeline shape is decided.

Not every field needs this protection. `debugView` is read live, every frame, because it only
changes which branch a single already-compiled shader takes; it never changes what a pipeline
declares. `ssaaPreset`/`taauRatio` are applied at one fixed point per frame (the top of level
rendering, via `SsaaManager.applyCurrentScale()`) rather than latched, since resizing a render target
has no equivalent attachment-count hazard, and each only takes effect under its own method
(`ssaaPreset` under `SSAA`, `taauRatio` under `TAAU`); every other method pins the scale to 1.0
regardless of either stored value.

**Config surface.** Every engine knob now funnels through one YACL-hosted screen
(`screen.FornaxSettingsScreen`), reachable three ways that all resolve to the identical screen: a
"Fornax" button injected into the pause (esc) menu and the title screen alike
(`mixin.vanilla.PauseScreenMixin`/`TitleScreenMixin`), the `key.fornax.open_settings` keybind, and
Sodium's own video-settings screen. `SodiumConfigEntry` registers a single external "Fornax
Settings…" page there, no more duplicated "Engine" page, whose `setScreenConsumer` opens
`FornaxSettingsScreen.create(videoSettings)` with the video-settings screen itself as parent so Done
returns there; the keybind opens the identical factory the same way.

`FornaxSettingsScreen.create` builds a YACL screen with two categories. "Engine," grouped
Anti-Aliasing & Scale / Debug, covers `aaMethod`/`ssaaPreset`/`taauRatio`/`profilerOverlay`/
`debugView`; the master `shadersEnabled` toggle no longer lives here, since it moved onto the Shader
Packs tab below (pack option pages, a pack's own `screens.toml` content, stay
`PackSettingsScreen`-hosted). "Shader Packs" is a custom tab, not an options category:
`screen.FornaxPacksTab` implements both `ConfigCategory` (empty groups, so it still slots into
`YetAnotherConfigLib.Builder.category(...)`) and YACL's `CustomTabProvider`, which YACL's tab
dispatch checks for before building a normal `CategoryTab`, handing back this class's own vanilla
`Tab` laid out across the whole tab-content rectangle instead (a custom tab owns its whole footer;
save/undo buttons stay `CategoryTab`-only). That tab has three zones, all reading the pure
`screen.PackListState` live every frame so a post-Apply `PackListState.refresh` converges the whole
UI with no widget rebuild: a header staged master toggle (`PackRow.Cycle`, wired with a live
`withValueSupplier` re-read of `state::stagedEnabled`), a scrollable `PackRowList` ("None" plus one
row per `pack.PackDiscovery.discover()`-found pack, the staged row highlighted, the loaded pack
marked "Active," and an inline settings glyph on the staged==loaded (not None) row opening
`PackManageScreen` for the loaded pack), and a footer of Open Pack Folder / Pack Settings (same
enablement as the inline glyph) / Apply (enabled only while `PackListState.isDirty()`) buttons.
Apply runs the state's ordered plan directly against `pack.ShadersEnabledFlip`/`pack.PackSwitch`,
entirely outside YACL's option save cycle; the tab never touches `FornaxSettingsScreen`'s save
callback or `SettingsApplyRouter` for either of those two fields.

Every sub-screen either category opens (pack settings, or, recursively, a fresh copy of this screen
itself) follows the fresh-parent rule: its own return target is always `create(parent)`, a
newly-built `FornaxSettingsScreen` from the original screen this one was opened with, never the
currently-open YACL instance or the now-spent packs tab. YACL options snapshot their pending values
at construction and force-write them back through the raw binding on save; returning to the same
stale instance after editing state on a sub-screen (for example flipping the active pack there) can
silently revert what the sub-screen applied, without that apply path's own chained renderer
reload ever re-running. `FornaxPacksTab.apply()` builds `Screen freshParent =
FornaxSettingsScreen.create(this.parent)` up front and passes it as `PackSwitch.apply`'s alert
parent, and `openLoadedPackSettings()` (the one method both the cog and the footer button route
through) passes that same freshly-built settings screen into `PackManageScreen.create` as the manage
screen's parent. `PackManageScreen` re-applies the rule one level deeper: its Shader Options button
opens `PackSettingsScreen.open(PackManageScreen.create(parent, pack), pack, "main")`, and the
chrome-injected Defaults button's confirm (see below) returns to `PackManageScreen.create(parent,
pack)`, both freshly built from the original parent, never the open YACL instance.
`PackSettingsScreen` itself carries no special-casing for any of this; it faithfully returns to
whatever `exitScreen` its caller handed it on Done or Escape-at-root, so the rule's enforcement point
is entirely at the `FornaxPacksTab`, `PackManageScreen`, and `mixin.yacl.CategoryTabMixin` call
sites. The cost is that unsaved Engine-category edits are discarded when jumping to any sub-screen,
stated in each such button's own tooltip.

`PackManageScreen` is the pack-agnostic YACL entry point the cog and footer now open (title = the
pack's own display name), built around a `List<Supplier<ConfigCategory>>` growth seam: one shared
`PackEditSession` (constructed once per `create` call) backs every category on the list, so staging
an edit on any migrated page and every other migrated page's rows, plus the "Manage" bridge below,
all commit through the same single latched `apply()` (one rebuild-or-resync per Save, never per
category). The "Manage" category (the `ButtonOption` bridge into the legacy `PackSettingsScreen`,
for every option page not yet migrated) is always first; after it, `create` appends one
`YaclPackRows.category(session, page, screens)` per `[yacl].pages` entry the loaded pack declares
(the pack's own Quality page is the first such entry any shipped pack actually uses). A page id with
no matching `[screens.X]` table is silently skipped here (defensive only; `MetaValidator` already
makes that fatal at load, long before this screen is ever built). `YaclPackRows.category` renders
each `ScreenSpec.elements()` token as one native YACL row: a plain `ScreenElement.Option` becomes a
tick (boolean), `FloatSliderControllerBuilder` slider (runtime numeric in `sliders`), or
`CyclingListControllerBuilder` cycler (enum/labelled-numeric, never a dropdown, which renders
transparently over neighbouring rows on this MC version, the same rule `FornaxSettingsScreen`
follows); a `ScreenElement.MetaRef` becomes one meta cycler over `MetaBinding.displayValues` (its
declared tiers plus a leading `"Custom"` sentinel); a `ScreenElement.GroupHeader` (`<group:Title>`
element token) closes the accumulating `OptionGroup` and opens a named one. Sections start open, and
`<group:Title|collapsed>` opts a busy section back into starting folded (any other modifier after
the pipe is a fatal load error, since it would otherwise silently join the visible title; the
legacy `PackSettingsScreen` ignores group headers entirely, rows render ungrouped there). Both group
headers and plain option tokens also accept `|requires:NAME` (modifiers combine in any order on
groups): the named option must exist and be two-state or the pack fails to load, and on a YACL page
every gated row starts greyed per the session's stored value and, when the governor is itself a
tick-box row on the same page, greys/ungreys live via `Option#addListener` → `Option#setAvailable`
as the box is ticked (`YaclPackRows.category`'s dependents wiring). An element-level `requires`
overrides its group's; a governor never gates itself; the legacy screen ignores gates along with the
groups that carry them. Every row's binding follows the same setter/listener split for the identical
ring-safety reason: the listener (`.listener(...)`, fired once per user click/drag, one option or
one meta's whole tier at a time) is the only path allowed to live-preview a runtime write, via
`PackEditSession#stage`/`MetaBinding#select` → `PackEditSession#stageAll`; the binding setter (fired
once per changed option, inside YACL's own one synchronous `finishOrSave` apply-value loop over
every changed option in a Save) must never itself rotate the `GraphRunner#updateRuntimeValues` ring
buffer, so it routes through `PackEditSession#stageQuiet` (a single option) or
`MetaBinding#selectQuiet` → `PackEditSession#stageAllQuiet` (a meta's whole tier), both recording
into `staged` with no GPU write at all. This exists because the ring only has 3 slots: a Save
carrying 3+ changed runtime options or meta rows through the live-preview path
(`stage`/`stageAll`) in that one synchronous loop wraps the ring mid-frame and throws
`IllegalStateException: Cannot wait on a fence for the current submit` (a real production crash
caught in testing, not a theoretical one); `stageQuiet`/`stageAllQuiet` skip the write entirely, and
`PackEditSession#apply()`'s own single combined resync at commit time is the only runtime write such
a burst ever produces. A meta row's getter (`MetaBinding#current`) re-derives its displayed tier
live off the session every frame via `MetaMatch#matchingTier` (a hand-edit on any granular row the
meta also assigns flips the meta's own row to "Custom" on the very next frame, with no extra
wiring). Import / Export / Defaults are not options on the category list; they're chrome, injected
into YACL's own right-side button cluster (beside search/Reset/Undo/Done) by
`mixin.yacl.CategoryTabMixin`, one `Inject` at the tail of `YACLScreen$CategoryTab`'s constructor
(that constructor computes every position, including the search field's, exactly once; `doLayout`
only ever repositions the option list) plus one at the tail of `visitChildren` so the three buttons
reach the screen the same way `optionList`/`searchField`/etc. do. The injection is scoped through
`screen.PackChromeActions`, a `WeakHashMap<Screen, Context>` that `PackManageScreen.create`
populates (pack + original parent) before returning its built screen; an unregistered `YACLScreen`
(the plain Engine settings screen) leaves the mixin a no-op, so stock chrome there is untouched. All
three buttons delegate to `screen.PackValuesActions`, a session-free helper: Import and Defaults each
run a throwaway `PackEditSession` (`stageAll`/`stageDefaults` then `apply()`) so the "persist the
values file, then at most one `GraphRunner.rebuild` on a compile change or one runtime-buffer resync
for sliders, plus one renderer reload where pipelines are affected" semantics stay byte-identical to
the bespoke screen's own Apply path, never a second hand-rolled apply (the same latch rule). Export
writes `PackSettingsSupport.mergedValues` (on-disk file merged with defaults) with no rebuild.
`descriptionWidget`'s pane shrinks by the injected row's height so nothing overlaps;
`CategoryTabMixin` replaces its dimension supplier (via `OptionDescriptionWidgetAccessor`) rather
than resizing the widget directly, since it re-reads that supplier every frame and would silently
undo a one-off resize on the next one. Every mixin-side lookup here is wrapped so a missing/renamed
YACL internal logs one warning total and leaves stock chrome intact rather than crashing the screen
(the same fail-soft rule; the `@Shadow`/`@Accessor` declarations themselves still hard-fail at
mixin-apply time, same as any other mixin). These three surfaces were removed from
`PackSettingsScreen` (one surface per action) in an earlier pass; its option pages are otherwise
untouched.

Because YACL applies every pending option's binding before its single top-level save callback runs
(`YACLScreen.finishOrSave`: `applyValue()` for every changed option, then `saveFunction.run()`), that
save callback can't rely on per-option storage handlers the way Sodium's old Engine page did.
Instead `config.SettingsApplyRouter` diffs a pre-open `FornaxSettings` snapshot against the
now-applied live settings and reports which of `{SAVE_ONLY, PACK_REAPPLY}` fire (a `Set`, since one
save can carry independent changes at once, for example `profilerOverlay` and `aaMethod` together).
The callback then dispatches to `PackReload.reapplyActivePack()` for `PACK_REAPPLY`, never a fork of
that logic. `SAVE_ONLY` fires whenever anything changed at all (including alongside `PACK_REAPPLY`,
since `PackReload` itself never persists to disk), covering the `profilerOverlay`/`debugView`-only
case on its own. The master toggle and active-pack selection left the router entirely along with the
old "Active Pack" category: the router never sees `shadersEnabled` or `activePack` changes at all
now, since `FornaxPacksTab` self-applies both through `ShadersEnabledFlip`/`PackSwitch` outside this
save cycle before the router ever runs.

## 11. Debug views

Eleven values, ordinal 0 through 10: off, normals, albedo, the raw material/specular channel, motion
vectors, ambient occlusion (raw and blended/SSAO), the TAA history buffer, block-light emission, the
raw screen-space-reflection buffer, and the resolved material-category ID (hashed to a distinct
colour per category, uncategorized shown as black). Selection rides the same generic per-pass-params
mechanism every fullscreen pass uses (see §6); the resolve pass alone receives the current
debug-view ordinal as one of its two generic per-pass scalars, decoded shader-side into an integer
branch. There is no dedicated debug-view uniform; it is one value of a mechanism built for something
else entirely.

## 12. Known laws

- **The sprite-bounds grid's resolution is not fixed.** The engine sizes it to the pack, since the
  same grid sets how tightly a paged stitch may place sprites. A shader that derives the cell index
  from its own constant reads the wrong cell, with no error: the rect-contains-uv test fails and
  whatever used the rectangle switches off. Packs read the grid through `fornax_spriteGridCell` in
  `fornax:block_atlas.glsl`, which takes the size from the texture.
- **std140 scalar-after-vec3.** A scalar immediately following a vec3 member must land at the
  next 16-byte boundary, never in the vec3's spec-legal trailing 4 bytes. The pack-options layout
  builder enforces this as a hard rule rather than relying on any particular driver's packing
  behaviour.
- **Reversed-Z depth.** The depth buffer clears to 0.0 ("far") and compares as greater-or-equal.
  Any code touching depth (clears, discards, reconstruction) must respect this convention, not the
  forward-Z one.
- **Colour-target-state count must equal render-pass attachment count.** A compiled pipeline's
  declared colour-target-state count and the render pass it is bound against must agree exactly, or
  the render pass rejects the pipeline at draw time. This is currently guaranteed by construction
  (the deferred pipeline mixin and the deferred render-pass mixin are hand-kept in lockstep, both
  gated on the same render-state latch), not by any runtime assertion.
- **A mixin absent from the mixin config fails silently.** A mixin class that exists in source
  but isn't listed in the config is never applied, with no error, warning, or log line of any kind
  distinguishing that from an intentional removal.
- **A YACL-bound `FornaxSettings` field must appear in `SettingsApplyRouter.route`'s diff, or its
  change is silently lost.** YACL writes a changed option's value straight into the live
  `FornaxConfig.get()` before the save callback runs, so the setting visibly takes effect
  immediately regardless of what the router reports. If the router's before/after diff doesn't
  compare that field, `SAVE_ONLY` never fires, `FornaxConfig.save()` is never called, and the
  in-memory change reverts on the next load/config reload with no error, log line, or other trace.
  Found via `sunPathRotation`, which had a live slider (`FornaxSettingsScreen`) but was never
  compared in `route()`.
- **Some Vulkan backends do not zero-fill new VRAM.** A freshly allocated texture can contain
  arbitrary previously-resident memory rather than zeros; every engine-managed target is cleared
  explicitly at allocation rather than relying on any backend's allocator behaviour.
- **A GPU-resource builder that creates several handles in sequence must hoist them above its
  `try` and free whatever succeeded on a mid-sequence failure, or the leak compounds.** Several of
  this engine's pass runners rebuild their pipeline on every frame until the build succeeds
  (`GraphRunner.ensureRunnersBuilt`), so an unguarded builder does not leak once -- it leaks again
  every frame for as long as the pack stays broken. `ParticlePipelineBuilder.build` and
  `ComputePipelineBuilder.buildWithDescriptorLayout` both follow this shape (hoisted handles,
  `catch (RuntimeException)`, a null-checked `destroy`); a new builder on this path should too.
- **An unresolvable shader include fails silently downstream, so this engine validates eagerly.**
  The underlying shader-composition mechanism splices an error string into the composed source for
  a missing include rather than failing the load, which would otherwise surface only as a broken
  pipeline compile deep inside a render frame. Every pack load instead validates every include up
  front and fails loudly with the offending file and include name.
- **Block/item tags are not bound at the point a pack first loads.** Querying an unbound tag
  throws; category resolution tolerates exactly that exception during initial load and re-resolves
  once a lifecycle event confirms tags are actually bound, requesting a terrain remesh at the same
  time so early-meshed geometry doesn't keep a stale, pre-tag material assignment.
- **`Map.of` iteration order is not a declaration-order guarantee.** It is salted per JVM run.
  Anywhere a test needs to pin declaration order (dense-ID assignment, cross-file option merge
  order), it builds fixtures with an explicit ordered map instead, precisely to avoid a test that
  passes or fails depending on JVM hash-seed luck rather than the code under test.
