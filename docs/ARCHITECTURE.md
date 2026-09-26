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
| `.metalfx.rt` | Metal RT sun visibility beside MoltenVK. Exports a centered finite domain from the existing brick-grid source (diameter at most 17), using compact toroidal destinations and source-slot BLAS ownership. One shared 64-slot copy/build batch keeps metadata coherent. Dense sections grow and re-expand; incomplete geometry is invalid for replacement. `MetalRtShadowPass` orders Vulkan depth/normal/atlas copies, Metal expansion/BLAS/TLAS/trace, and Vulkan result copies with its shared timeline before graph consumers. Cutout intersections use current atlas alpha and certified baked CROSS models carry exact positions, UVs and offsets; unsupported geometry or mappings invalidate only the affected rays for raster fallback. |
| `.pack` | Pack manifest records (`PackModel`, `GraphSpec`, `PassSpec`, `TargetSpec`, `BlocksSpec`, ...), TOML parsing, discovery, shader-import validation, pack reload (`PackReload`). Also `ShadersEnabledFlip`, the shared master-toggle-only apply path (render-state latch), and `PackSwitch`, the shared active-pack-selection apply path (same rule): both are invoked only by `screen.FornaxPacksTab`'s self-applying Apply flow (the Engine save callback routes only `{SAVE_ONLY, PACK_REAPPLY}`, never these) |
| `.pack.graph` | The graph interpreter: `GraphRunner`, target allocation (`TargetRegistry`/`TargetPlan`/`TargetInstance`), per-pass-type runners, VRAM (the graphics card's own memory) reporting |
| `.pack.layout` | Shader source rewriting (`DefineRewriter`), the `u_PackOptions` layout/buffer, the synthetic `RuntimeShaderPack` |
| `.pack.material` | blocks.toml -> dense material IDs, block/tag resolution, the generated material GLSL include |
| `.pack.option` | Annotated-`#define` option grammar parsing and cross-file merging |
| `.pass.ssaa` / `.pass.taa` / `.pass.reconstruct` | The general render-scale target lifecycle: SSAA (render bigger, then shrink down for smoother edges) box downsample, TAA/TAAU temporal reconstruct (spreading anti-aliasing work across several frames using motion history) plus presentation sharpen, and the camera jitter sequence |
| `.profile` | GPU per-pass timing (`PassTimer` for graphics-encoder work and `ComputePassTimer` for raw compute-queue dispatches, both ring-buffered across frames-in-flight) feeding a pure-JVM rolling-stats aggregator (`FrameProfiler`); `ProfilerOverlay` (top-left HUD) and `ProfilerLogDump` (full-table log dump) read it, both graded against the 11.1 ms / 90 FPS budget |
| `.pipeline` | Shared per-frame state: `GBuffer`/`GBufferManager` (the G-buffer is the set of full-screen images, such as surface colour, normal direction, and depth, that the deferred step stores before lighting runs), `FornaxChunkVertex`, the render-state latch, push-constant layout, previous-frame camera transform, per-thread material ID, the engine-guaranteed `SceneHistory` target, `FrameCameraState` (this frame's inverse projection*modelView matrix as a plain float array, for the Metal ray-tracing pass) |
| `.rt` | Platform-neutral ray vocabulary and the cascade that walks it: `RayTier` (which traversal answered a ray and over what geometry; the ordinal is the wire value carried in a result's G channel and in hit-record word 7), `RayQueryKind` (visibility versus closest hit, which decides how much of a record a provider fills and how early it may stop), `RayProvider`/`RayReadiness` (one traversal, and whether it can answer this frame), `CelestialFill`/`BufferQuery` (the two request shapes) and `RayRouter` (static, render-thread confined: installs providers, sorts them best-first, evaluates readiness once per frame, and runs each over what the tiers above left unanswered). The Metal traversals themselves live in `.metalfx.rt` |
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
  RETURN -> GraphRunner.finish(matrices, x, y, z), deferred until solid features when needed
             publish current-frame Metal RT masks before graph consumers
             record graphics-read engine buffer uploads (transient command buffer, no flush)

GameRenderer.renderLevel(...)
  RETURN -> restore native target
            copy scene history
            present graph/debug consumers
            present the already-produced RT sun mask or scene debug if selected
            record graphics -> next-compute timeline signals
            advance camera jitter
```

Nothing runs for the translucent layer group.

**`GraphRunner.prepare()`** does nothing immediately unless a pack is active (the latched render
state, not live config; see §9). Otherwise it reads the current `mainRenderTarget` size, ensures
the G-buffer and every graph target are sized for it, and finishes any pending storage texture
setup before frame bindings or compute work. It then lazily builds the GPU-backed pass runners
and options buffer once a device exists (pack activation itself runs before any device is
guaranteed to exist), sizes any mip-chain targets, and clears the G-buffer depth attachment to the
reversed-Z far value. It then submits the independent voxel-light producer chain
(`light_inject`, `light_propagate`, `light_list_reset`, and `light_list_build`, subject to compile
options) to the shared compute queue in graph order. Only the last runnable producer signals a
compute-to-graphics semaphore (a GPU-side signal one queue raises and another waits on), whose wait
is inserted before opaque terrain begins. Queue order makes that one signal cover the complete
producer chain while avoiding a wait inside an active Apple tile render encoder.

Cross-queue storage-image reuse has a second, reverse edge. Each compute runner whose storage
output can also be touched by an enabled graphics pass owns one timeline semaphore. Its compute
submission waits at `COMPUTE_SHADER` for the value recorded after the preceding frame's final
graphics reader; at `GameRenderer.renderLevel` RETURN, after scene-history copies and both debug
presenters, `GraphRunner.recordGraphicsStorageReadsComplete()` appends the next signal value to
Blaze3D's persistent graphics encoder. The method records only—it neither submits nor waits on the
host—so the former per-dispatch graphics-queue idle is gone. Storage images still use concurrent
queue-family sharing where needed, but that only removes ownership transfers; it does not establish
the execution dependency that prevents next-frame compute writes from racing prior graphics reads.
The existing binary semaphore remains the opposite, same-frame compute-write to graphics-read edge.

Uniform buffers shared by graphics and raw compute also need legal queue-family access.
`VulkanGpuBufferSharingMixin` applies `VulkanBufferSharing` to the final `VkBufferCreateInfo`
argument of `VulkanGpuBuffer.Direct`'s VMA allocation: uniform usage selects concurrent sharing
between distinct graphics and compute families. Same-family allocations and non-uniform buffers
retain their original policy. This allocation capability is independent of pack activation because
long-lived uniform buffers can be allocated before a pack is loaded. It changes neither buffer
contents nor synchronization; upload visibility, semaphore ordering and ring retirement remain
separate obligations.

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
never composites it to screen. Immediately before the loop, `SkyReprojection.commit(...)`
publishes this frame's unjittered sky transform, so a temporal pass in that same loop consumes the
transform of this frame rather than the one sent last frame. Next, still
outside any render pass and before the loop, `GraphicsBufferUploads.record(...)` writes each
graphics-read engine buffer's data for this frame into a short-lived command buffer. The first pass
that binds one of those targets then sees this frame's data, not the zeros the buffer held when it
was made.
After the pass loop, a pack that declares no depth copy-back pass
gets a hardcoded fallback copy of G-buffer depth into the main render target's depth texture, so
translucent draws afterward always see correct depth.

`ComputeGraphicsWaits` leaves the first compute-to-graphics handoff in `finish()` where it always
ran. Later handoffs can stay open across fullscreen, mipchain, copy and consolidate passes that do
not touch the same data. A conflict table, built each time the pack rebuilds, checks each pass's
writes against every other pass's reads and writes, and its reads against every other pass's
writes; a history buffer and the two shadow-sampler names count as the same resource. The next
compute dispatch, particles pass, temporal pass or engine output drains any open waits, even one
whose intended reader turns out to skip. A deferred wait uses `ALL_COMMANDS`, since it must also
cover a capture's transfer step and its attachment reads and writes, not only its shader. Leaving
the scope drains every wait still open before history swaps and later geometry run. A cached kernel
still sends its handoff; the waits before opaque work, and the timeline running the other way
(graphics to compute, through a storage image), do not change. The result: independent passes can
overlap where the graph allows it, but the very first handoff still happens where it always did.

The profiler gives each frame one ID number at the start of `GameRenderer.renderLevel`, before
scale setup and world preparation. `GraphRunner.beginProfileFrame()` opens the Vulkan graphics
`frame` bracket; `endProfileFrame()` closes it after the AA/history tail. This span covers terrain,
shadows, the graph, and translucent/hand work, but not the HUD or presentation. It is how much time
passes on the GPU: that can include idle time and waits, not only the time spent doing work.
`graph graphics` is a separate bracket around just the graph loop. `geometry dwell` names only the
graph loop's geometry slot; each graphics pass still gets its own row.

Graphics query pools take turns across `FramePacing.FRAMES_IN_FLIGHT` (3) slots. A slot is reused
only once every earlier query it holds has an answer, including queries from a frame whose brackets
did not nest correctly. If any are still pending, the current frame goes untimed and the slot is
left alone until its next turn. The profiler never waits on a fence. Stored results are read out
before new writes happen, because Blaze3D's write path resets each query slot on the host as soon
as it writes to it. Each late-arriving query carries its frame's ID with it. A bracket pair that
does not match drops that frame's rows; a device that cannot report timestamps turns off collection
without changing what gets rendered.

`FrameProfiler` keeps 240 samples per label, plus a GPU history of up to 240 frames capped at 256
intervals per frame. Reset clears every measurement and never reuses frame IDs, so old results
still coming back from a query ring in flight are turned away. Counters track how many intervals
were dropped or turned away. `ProfilerOverlay` refreshes its cached copy about 4 times a second;
its filters only change what the HUD shows. The **Dump Frame Profile to Log** key, which has no
default keybind, writes out real AVG/P95/sample counts, the pack's current option values, screen
sizes, CPU numbers, counters, and up to 30 frame IDs that have results. Coverage is never complete:
some queries may still be waiting on an answer. Two timer groups can share one real GPU queue, and
their clocks are not lined up, so never assume their times overlap or add their percentiles
together. The graphics counter width is unknown (reported as 0) through Blaze3D; compute reports
the width it actually queried. Metal's own GPU time, and the moment a frame reaches the screen, are
both left unmeasured.

CPU rows measure time spent recording the world, the gap between one frame's start and the next
(cadence), waiting for an old compute slot to free up, taking a lock, submitting work, and waiting
on a dependency, plus the real and generated surface acquire/present calls. Cadence includes any
pacing delay and other engine work in between, so it is not the same as GPU execution time or the
FPS shown or generated. Presentation counters count present calls that returned successfully; that
is not proof the frame reached the screen. Collecting these numbers never adds a submit, a flush,
or a wait of its own. Writing a dump builds a string and writes to the log, so let that frame's
numbers age out of the window before measuring again.

Periodic G-buffer readbacks need `-Dfornax.debug.gbufferReadback=true`; the profiler overlay being
on or off makes no difference. F10 is still the separate on-demand attachment capture. Neither
readback mode belongs in a timing baseline.

`PassTimer` cannot wrap raw `ComputePassRunner` submissions, because those are recorded into their
own command buffers and sent straight to the compute queue. So each compute runner owns its own
raw Vulkan timestamp pool, with two queries for each `FramePacing.FRAMES_IN_FLIGHT` slot. The
slot's command buffer resets its own pair of queries and writes both timestamps at
`COMPUTE_SHADER`: the start right before `vkCmdDispatch`, the end right after it and before the
release barrier. The start shares the graphics-to-compute image-reuse semaphore's wait stage;
`TOP_OF_PIPE` can timestamp before that wait completes and include it in the pass interval.
These are approximate compute-stage intervals, not exclusive kernel execution time: timestamp
writes depend on earlier commands but do not prevent later dispatches overlapping a delayed start,
and implementations may latch at a later stage. See the Vulkan specification for
[`vkCmdWriteTimestamp`](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdWriteTimestamp.html)
and [submission wait scopes](https://docs.vulkan.org/spec/latest/chapters/cmdbuffers.html#devsandqueues-submission).
No profiling barrier or additional submission serializes the workload. A pair is only reported to `ComputePassTimer` once `vkQueueSubmit` succeeds; once that slot's fence later
succeeds, its pair is read before the command pool resets. Before making the pool, the runner asks
the physical device for the chosen compute queue family's `timestampValidBits`: zero bits turns off
timing, and a counter with only some bits masks the readings and works out elapsed ticks modulo
that width (wrap included; 64 bits uses plain subtraction's own modulo behavior). An unsupported
timestamp period, a failed query allocation, or a result that never becomes available all fall back
to untimed compute without changing what gets dispatched or how it stays in sync. The measured GPU
duration is filed under the pass's own name. A separate, older CPU-side wait for a dependency fence
is reported on its own as a latest-value number under `compute wait <pass>`, so it can't be mixed
up with the rolling GPU AVG/P95 numbers.

The graphics span and the raw compute intervals are separate measurements and stay that way.
Compute rows can help compare settled runs with matching workloads, but collapsed or strongly split
intervals require checking the timestamp behavior before attributing cost to a kernel. A smaller
interval after a boundary change is not a speedup. Adding compute rows to the graphics span does
not tell you the true critical path.

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
   jar-in-jar) into five typed specs: `pack.toml` (name/version/format), `graph.toml` (targets and
   passes), `blocks.toml` (material categories, optional), `screens.toml` (settings-UI layout and
   quality profiles), and `biomes.toml` (exact biome IDs, optional). `pack.toml`'s `format` field is checked against the one format version this
   build understands; a mismatch fails load immediately. All TOML tables are parsed
   insertion-order-preserving, since category and option declaration order becomes dense-ID order
   and uniform-block layout order downstream.

   `biomes.toml` holds one `[biomes]` table of biome names to whole numbers, such as
   `"minecraft:plains" = 1`. `BiomesTomlLoader` reads it through `PackTomlLoader.loadBiomes`, and
   `PackModel.biomes()` keeps the result as a `BiomesSpec` that cannot be changed. Each ID must be
   used once and sit in `1..16777216`, the range a float still counts in whole steps. 0 is kept
   for a biome the pack gave no ID. A missing or empty file gives an empty list. A bad name, a bad
   type, a repeated ID or one out of range stops the load and names the file and the key. The
   order lines are written in is kept, but it never sets an ID. A name this game does not have
   does no harm. Lookup goes by the biome's name, never by the number the game hands out that
   run, which moves. Every pack reload reads the file again. The table only adds, so pack format
   stays 1 and no shader macro is written for it: the pack owns these numbers and what they
   mean.

   An optional graph-level `[ray_traced_shadows]` table becomes
   `GraphSpec.rayTracedShadows()`, a nullable `RayTracedShadowSpec(enabledIf, distanceOption,
   blocksPerUnit, filterGuardTexels)`. Absence keeps raster ownership; both shorter `GraphSpec` constructors preserve
   that default. The table requires nonempty `enabled_if` and `distance_option` strings, accepts a
   positive integer `blocks_per_unit` (default `1`) and finite nonnegative `filter_guard_texels`
   (default `0`), and rejects unknown fields. `GraphValidator` checks the gate with the same compile-expression rules
   as pass gates, and requires the named distance to be a numeric runtime option with finite values.
   The declaration carries option names supplied by the pack; the engine owns no option spelling.

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

A target may use `rgba32f` for four 32-bit floating-point channels. `TargetFormat` accounts for
16 bytes per pixel, and `TargetRegistry` allocates `GpuFormat.RGBA32_FLOAT` through the same
texture, storage, history, and resize paths as the other color formats.

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
texture, and its history twin if any, gets a transparent-zero clear recorded before it is
installed, unconditionally, as a target-model-level guarantee rather than something left to
whichever pass happens to write it first. This exists because at least one supported Vulkan
backend (MoltenVK, which translates Vulkan calls to Metal) does not zero-fill newly allocated
VRAM: a target built and
sampled before its first real write reads back whatever was previously resident in that memory.
Making the clear structural for every target, rather than an ad-hoc responsibility of individual
passes, removes an entire class of "why is there garbage on screen for one frame" bugs.

A new or reallocated storage texture marks the registry's init batch as pending. Right after
registry sizing in `prepare()`, before frame bindings, runner setup, or pre-opaque compute, the
engine makes a graphics fence, submits the recorded batch, and waits for the fence to complete.
This makes sure the layout change and clear finish before raw compute writes the texture. All
storage allocations from one sizing pass share a single wait; a frame with no new allocations, or
only ordinary raster allocations, submits and waits on nothing. The rare startup or resize frame
can stall the CPU until graphics finishes. A timeout is fatal, and the pending flag only clears
once the fence completes. Every compute runner also refuses to run while initialization is
pending, instead of trying to flush mid-frame.

### Buffer targets (`kind = "buffer"`), and who sizes them

A target may declare `kind = "buffer"` instead of the default `texture`, making it a raw SSBO
(shader storage buffer, a flat block of GPU memory a shader can read and write arbitrarily) with no
format, scale, basis, history or filter, all of which are refused on it as unknown keys. It has
exactly two possible owners, and which one applies is decided by name, against
`GraphValidator.ENGINE_BUFFERS`:

* **Engine-owned**: `voxelBrickIndex`/`voxelOccupancy`/`voxelPayload`/`voxelFaceSeal`/
  `voxelPalette`/`voxelLightVolume`/`voxelBrickSummary` (`BrickGridUpload`), `voxelWaterRefl`
  (`VoxelWaterReflBuffer`), `analyticLightList` (`AnalyticLightListBuffer`), `precipClipmap` and
  `precipCoarseClipmap` (`PrecipClipmapBuffer`/`PrecipCoarseClipmapBuffer`), `surfaceFluidClipmap`
  (`SurfaceFluidClipmapBuffer`), `waterActors` (`WaterActorBuffer`) and `entityOccluders`
  (`EntityOccluderBuffer`). Their byte counts come
  from runtime quantities the graph cannot express (voxel window diameter, render resolution, or a
  fixed engine data grid), so their own engine call site drives `TargetRegistry.ensureBufferSize`.
  A pack declares the target purely so the name is referenceable, and must not give it a size; the
  engine would overwrite it anyway.
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

`precipCoarseClipmap` is the deliberate narrow exception to the general read rule. Its uploader
records transfer visibility only to the compute queue, so `GraphValidator` accepts it solely as an
input to an enabled `compute` preprocessing pass. That pass must produce the texture sampled by
later graphics passes; no fullscreen, particle, geometry, copy, or mipchain pass may bind the raw
field. It is a 128 x 128 toroidal grid of four-block cells, covering a 512 x 512-block window whose
origin follows the player body in sixteen-block snaps. Each cell is four 32-bit words, one
`ivec4`, the alignment rule `surfaceFluidClipmap` set. Word 0 stores the representative column's
`NONE`/`RAIN`/`SNOW` value in the low byte, a sampled-valid bit at bit 8, and bounded eight-bit X/Z
tile tags in the high sixteen bits. Word 1 stores the height-adjusted surface temperature that
classification thresholded (signed 16-bit, 1/256 steps), downfall in 0..255, and a tag byte of biome
categories (hot, cold, wet, dry from the shared convention tags; ocean, jungle, badlands, mountain
from vanilla's). Word 2 holds the biome's own heat in its low sixteen bits; the rest is kept back
and written zero. Word 3 holds the pack's ID for the biome at the top block from `biomes.toml`, or
zero when the pack gave it none. It looks the ID up the same way `u_CameraBiome.x` does, but at the
top block rather than at the camera, so the two can differ. The heat and rain reads reach private game members through the access widener
(§8). A zero word 0 is therefore explicitly unknown, including where a valid dry cell's tag would
otherwise also be zero. Tags reject ordinary stale toroidal slots but repeat every 131,072 blocks on
either axis, so they are a local validity check, not an unbounded world identity.

The engine samples only loaded chunks, at the centre-side column of each four-by-four cell and the
`MOTION_BLOCKING` surface height. Unloaded cells remain unknown; the engine never guesses that they
are dry. Uploads ride `EngineBufferUploadQueue`: they are recorded into the first consuming compute
pass's own command buffer ahead of its dispatch, so nothing submits a queue or waits on a fence on
the render thread. A first window, level change or discontinuous recenter queues a clear and then
the complete 256 KiB refill in ranges of at most 64 KiB, the queue's inline-update limit; the
window is published the moment both are queued, because no dispatch can
precede them in that buffer. This reset is the required defence against the bounded tag period, and
until it is queued `GraphRunner` withholds the graph's passes for that frame rather than expose old
slots under a new window. Normal frames update eight rows (16 KiB), complete a sweep in sixteen
frames, and retain an already-valid same-cell record when its chunk is temporarily unavailable.
`precipClipmap`, the per-block-column field, uploads the same way: eight 512-byte rows a frame.
A level change or a whole-window jump queues a clear ahead of them, since the tag identifies a
column, not a world.

`GraphRunner.closeCurrent()` calls `PrecipCoarseClipmapUpload.reset()` before it drops a pack. The
reset wipes the copy held in main memory, the plan of what to send next, and anything still waiting
to be sent, so IDs from the old pack cannot live on in half the rows. The next reader gets a full
wipe and refill. Losing the world or the player does the same reset.

This is a raw world-data ABI, not a cloud policy. The engine does not smooth biome boundaries,
extend the field, classify storm shapes, or darken the sky. The required compute preprocessor owns
those decisions and writes a pack texture for later cloud, sky, shadow, and reflection consumers.

### Entity occluders (`entityOccluders`)

The voxel grid is built only from block states, so a march reading it alone never sees a player, a
mob, or an item standing inside a light's radius. `entityOccluders` sends the GPU where each nearby
body is, how big it is, which way it faces, and what kind it is. A pack's coloured-light shadow
march decides by itself how that shapes a shadow. Nothing here sets the soft edge of a shadow, how
fast it fades, or which kinds a light casts against.

Layout, std430, all 32-bit floats. A pack reads them as `R32_UINT` texels and turns the bits back
into floats:

```
vec4 header;                  // x live count, y frame counter (wraps at 2^24 so it stays
                              //   exact as a float), z RANGE_BLOCKS, w reserved
struct {
  vec4 boundsMin;             // xyz AABB min, camera-relative, w kind
  vec4 boundsMax;             // xyz AABB max, camera-relative, w body yaw (radians)
  vec4 motion;                // xyz how far the centre moved since the frame before, w eye height
} occluders[MAX_OCCLUDERS];
```

Slot 0 is always the local player when the player is sent, first person included. It is the player
entity itself, not a root vehicle it may be riding: a body occludes its own held light, not whatever
it is riding, so a pack tests `kind`, never slot, to find it. Every other slot holds a different
body, nearest first by how far each box centre sits from the camera, so a full list drops the bodies
least likely to be looked at. A spectator, an invisible body, a dead body, or one whose position is
not a real number is never sent. Passengers are published, unlike `waterActors`'s boats: each body
casts its own shadow, while a boat and its rider raise only one shared wake. Positions are
camera-relative, subtracted in `double` and only then cut down to `float`, for the same reason as
`waterActors`: a march centred on the camera needs a distance from its own centre. Two absolute
world coordinates subtracted in `float` would throw away the low bits of the quantity that matters
once the player is far from the world origin.

Only an enabled fullscreen or particles pass may read this target. A compute reader or writer is
refused at load, the other way round from `precipCoarseClipmap`, which only compute may touch. This
buffer's upload can be seen from the graphics queue and nowhere else. The data goes through
`EngineBufferUploadQueue` like every other engine buffer, but `GraphicsBufferUploads` records it,
from `finish()` before the pass loop, not `ComputePassRunner`. See the "single queue its readers run
on" law in §12.

`EntityOccluderStrideContract` fails load, naming the file and the declaration, when a pass that
reads `entityOccluders` declares header, cap, or record word counts different from
`EntityOccluderBuffer`'s own. It is the same load-time check `PaletteStrideContract` gives the
brick-grid palette.

### Builtin resource names (`builtin.*`)

`builtin.normalAtlas` and `builtin.materialAtlas` resolve to their device-owned semantic-neutral
textures while sidecar generation retirement leaves no atlas published. This applies to both view
and raw-texture resolution, so compute and fullscreen consumers remain valid during the same
multi-poll rebuild window already handled by geometry bindings. Each resolution reads the current
atlas again; publishing the replacement restores its handles immediately. Other unallocated or
undeclared targets still fail resolution.

`builtin.blockAtlasPages` and `builtin.materialAtlasPages` expose the existing overflow allocations
as `sampler2DArray` inputs, with zero-based layers. During retirement or on an unpaged atlas they
resolve to device-owned 1x1x1 neutral arrays, retaining the array view type. Consumers that require
real source pixels must reject that extent and a requested layer outside the published depth.
Static source faces carry their 1-based page in face-header bits 27..28, with zero meaning base
atlas. Their UVs retain the existing ghost coordinates: page `k` remaps to full-page UV through
`(uv - ((k-1)/4, 3/4)) * 4`. Albedo and material pages may differ in pixel dimensions; their UV
layout matches. Animated ghosts have no full-copy page and remain unsupported static sources.

Before any graph compute or terrain work, `GraphRunner.prepare` resolves every atlas builtin declared
by a compile-enabled compute pass, including the potential neutral fallbacks even for published lanes, then completes the recorded graphics
batch once when those view identities change. The fence is created before submit, and compute admission
remains closed on timeout or completion failure. Unchanged identities incur no submission or wait;
checking each albedo, sidecar and overflow view independently also covers resource reloads that reuse
sidecar content while replacing albedo pages. Compute entry rejects any identity published after prepare.
This orders initial and replacement uploads; it does not synchronize animation writes to the same image.

The registered Vulkan texture-allocation mixin applies concurrent graphics/compute family access to
all `TEXTURE_BINDING` or Fornax `STORAGE` images when the device uses distinct families. This includes
vanilla base atlases, engine sidecars, overflow arrays and their neutral fallbacks from first allocation,
without relying on a loaded graph or resource labels. A single shared family retains exclusive mode.
Concurrent sharing supplies legal queue-family access; the prepare fence above still supplies upload
completion before raw compute reads.

`GraphValidator.BUILTINS` is the complete set of engine-resolved names a pass may reference without
declaring a target: `builtin.depth`, `builtin.blockAtlas`, `builtin.materialAtlas`,
`builtin.normalAtlas`, `builtin.lightmap`, `builtin.output`, the G-buffer attachments
(`builtin.gNormal`/`gAlbedo`/`gMaterial`/`gAo`/`gMotion`), `builtin.celestials` (the vanilla
sun/moon-phase atlas; see `CelestialSprites`), and `builtin.noise` (see below). `gAlbedo`'s alpha
channel carries the vanilla sky-light level (0-1, the lightmap Y coordinate) rather than the source
texture's own alpha; the pack-side terrain shader repurposes that lane once ALPHA_CUTOUT has already
consumed any meaningful alpha-test value, since no downstream resolve consumer reads the resolve
pass's own output alpha. `gbuffer_resolve.fsh` decodes it to gate SSR's sky-miss fallback, the
sun-shadow distance fade, and atmospheric fog's cave damping. Several further engine-owned names are
validated separately, each carrying an extra structural rule `GraphValidator.checkInputRef` enforces
on top of plain resolvability: `sunShadowMap` (`ShadowMapManager.TARGET`, no history slot),
`sceneHistory` (`SceneHistory.TARGET`, previous-frame-only, must be referenced as
`sceneHistory.history`), `builtin.depth_opaque` (`OpaqueDepth.NAME`, an engine-owned,
self-managed D32 copy of the opaque G-buffer depth, not a `TargetRegistry` target since
`TargetFormat` has no depth format; captured at the finish-opaque boundary and cleared to the
reversed-Z far value at allocation; see its own subsection below), and `rtTerrainShadowDepth`
(`TerrainShadowResult.TARGET`, recognised by `TerrainShadowResult.isRef`, same no-history-slot rule
as `sunShadowMap`). The screen-space `rtSunVisibility`/`rtSunValid`/`rtSunDepth` names and the
`RtShadowResult` class that owned them are gone; the cascade's one image replaces all three, and a
pack naming any of them fails load on the ordinary unknown-input error. Every name resolves
through `GraphInputResolver`'s two switches (`resolveBuiltinView`/`resolveBuiltinTexture`), null-safe
against a resource that hasn't been captured/allocated yet. `ShadowMapManager.close()` runs inside
`GraphRunner.closeCurrent()` after its single device-idle-before-destroy boundary, so the D32 map
and dummy colour attachment are released on pack unload/switch the same way as `OpaqueDepth` and
water-prepass targets.

Packs declaring `sunEntityShadowMap` receive an independent forward-Z D32 entity/player depth
map with the same light projection and comparison sampler as `sunShadowMap`. It is requested at
graph rebuild, allocated with the shadow map, and cleared every frame before disabled-shadow
returns. Existing prepared shadow draws replay their already-uploaded buffers into this target;
there is no second player submission or CPU geometry extraction. Terrain never writes or tests
against its depth. `ShadowMapManager.close()` retires both maps at the existing idle boundary.
The combined map remains available unchanged; choosing how to combine the independent map with
alternative terrain visibility belongs to the pack.

`RtShadowResult` owns render-resolution R8 `rtSunVisibility` and `rtSunValid` targets. They always
resolve for an active graph. `GraphRunner.finish()` dispatches RT after the required G-buffer
writers and before graph consumers; end-of-frame code only presents the completed debug mask.
Both targets clear to zero on allocation and on unavailable/inactive transitions. Validity is
binary: zero selects the pack's raster fallback, one certifies a current trace in the finite
centered export domain. It does not certify visibility beyond that domain.

`MetalRtGeometry` maps source slots into a centered radius-at-most-eight domain with stable
absolute-coordinate modulo destinations. Source owner changes invalidate BLASes; the instance map
contains compact export indices for palette/face-texture reads. Copying and rebuilding use the
same selected batch, so a deferred slot's old primitive indices cannot read new palette metadata.
Missing, queued, read-but-not-committed, or RT-unpublished domain geometry invalidates replacement.
Known-empty committed sections qualify; distant work outside the finite domain does not block it.

`MetalRtAcceleration` starts each slot with `RT_INITIAL_TRIS_PER_SLOT` capacity, reads the complete
GPU expansion count, grows only overflowing slots to that requirement, and re-expands before
building. It retains grown buffers until eviction. Incomplete or corrupt counts fail closed rather
than publishing a truncated caster set. Cutout shared faces are retained unless opacity establishes
that removing them is safe. Native capacity fixtures exercise production recovery with 24,576
opaque checkerboard triangles and 6,144 cutout triangles, plus reuse; they do not establish live
Vulkan synchronization, visual quality, or FPS.

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

A `[textures.NAME]` declaration also has a volume shape, for a pack that needs a genuine
`sampler3D` (a baked light-propagation grid, a 3D LUT) rather than a flat image. Adding `depth` to
the table switches the whole declaration: `width`, `height` and `format` become required alongside
it, and `file` now names a raw binary asset instead of a PNG, since nothing decodes 3D dimensions
out of an image header the way `NativeImage` does for 2D (`PackTextureSpec`, `depth == null` for the
2D case, non-null for volume; `PackTomlLoader` rejects `width`/`height`/`format` on a 2D declaration
and rejects a volume declaration missing any of them, plus a non-positive `depth`). `format` is one
of `RawVolumeAsset.Format`'s two tokens, `r8` or `rgba8`, case-insensitive.

The raw asset itself (`RawVolumeAsset`) is a fixed 16-byte little-endian header, four `u32`s in
order (`width`, `height`, `depth`, a format tag: `0` for R8, `1` for RGBA8), followed by
`width * height * depth * bytesPerTexel` texel bytes with no further structure: x fastest, then y,
then z, tightly packed. `PackDiscovery.loadFrom` reads that header at pack-load time and cross-checks
it against the TOML declaration byte-for-byte (dimensions and format both), the same fail-loud rule
the 2D probe follows, so a mismatched or truncated volume file is a `FornaxPackError` naming the
declaration rather than a corrupt upload discovered mid-frame. `PackTextureRegistry.load` dispatches
on `PackTextureSpec.isVolume()` before either path touches the GPU; the volume branch re-reads the
same file into a fresh `RawVolumeAsset` and hands it to `Volume3DTexture`.

That volume upload needed a texture class of its own, `Volume3DTexture`, rather than another branch
through the existing 2D path, because Blaze3D has no `VK_IMAGE_TYPE_3D` anywhere underneath it:
`GpuDevice.createTexture` throws outright for `depthOrLayers > 1`, and `VulkanGpuTexture`'s own
constructor hardcodes a 2D image type and folds any depth argument into array layers instead of a
real Z extent. `Volume3DTexture` hand-builds both the image (a direct `vmaCreateImage` with
`imageType = VK_IMAGE_TYPE_3D`) and a `VK_IMAGE_VIEW_TYPE_3D` view over it, keeping Blaze3D's
required superclass construction around only as an inert 1x1x1 placeholder that owns the handles
`destroy()` still expects to free; uploading texels also has to bypass `CommandEncoder`'s
`writeToTexture`/`copyBufferToTexture`, since both hardcode a copy depth of one, in favor of a
hand-recorded `vkCmdCopyBufferToImage` whose region addresses the real depth directly.

Compute descriptors bind pack-declared textures with linear filtering and repeat addressing on
every axis, including a volume's Z axis; mip sampling remains enabled for 2D assets, while volumes
have only their uploaded base level. Graph targets and engine builtins retain nearest filtering and
clamp-to-edge except the comparison aliases `sunShadowMap` and `sunEntityShadowMap`: compute binds
these through `ShadowComparisonSampler`, with hardware depth comparison and linear filtering built
in. Their `Raw` aliases keep plain nearest depth reads. Engine-owned names win over a pack texture
declared with the same name. If no comparison sampler is ready when the descriptor is bound, the
compute pass throws and names its pass and input; it will not swap in a plain sampler for a
`sampler2DShadow` input. World-space volume coordinates leave the normalized unit cube on purpose, so
clamping a volume would stretch its boundary texels across the cloud field instead of tiling the
density data.

`ComputeShaderCompiler` applies shaderc's performance optimization when producing SPIR-V for raw
Vulkan pass runners. Pack sources arrive with their complete import graph flattened; optimizing at
this boundary removes dead helpers and folds the native module before a backend translates it into
a compute function, without moving any visual policy out of the pack.

### Reusing deterministic compute kernels

A compute pass can declare `reuse_when_unchanged = { runtime = ["u_TestValue"],
globals = ["u_SkyState.x", "u_FrameState.z"] }`. This says the listed scalars are the only
runtime/global values that change its output. Leave the declaration out and the pass runs every
frame as before. Runtime names must be declared runtime options, and the pass needs the
`packOptions` input to read them. The global choices are single `x/y/z/w` fields of `u_SkyState`
and `u_FrameState`, and the pass needs the `globals` input to read them. An optional `push` list
can name `u_Param2`, `u_Param3`, or `u_SunDirection.x/y/z`; an empty list (the default) says the
kernel reads no push fields. An unknown or repeated name fails pack loading.

`FrameUniformValues` holds a copy of the exact floats written to those fields, and is cleared in
`prepare()` before terrain writes the next frame's values. The reuse key compares those raw bits,
the chosen `PackOptionsBuffer` values, the chosen push lanes, texel size, dispatch group counts,
which target allocations are bound, and each input's content revision. The compare is exact: no
rounding, no tolerance, no hashing. A kernel that runs successfully saves this key and bumps the
revision of everything it wrote; a failed run saves nothing. A new runner starts with no saved key
after a shader reload, and swapping in a new target clears both its allocation identity and its
content revision.

Only plain compute passes qualify: no per-frame gate, and every output must be a storage image
that pass alone writes and that has no history copy. Inputs can be globals, options, or storage
images written by an earlier reusable compute pass. Mutable builtins, buffers, history targets,
in-place writes, shared writers, and the engine's own pre-opaque dispatches are all rejected. If
an input's producer has a compile gate, the consuming pass must carry that exact same gate, or the
producer must have none.

On a cache hit, only the kernel dispatch and its timestamp writes are skipped. Descriptor
updates, ring recycling, the release barrier, queue submission, and the binary/timeline syncing
all still happen. This keeps the descriptor-retirement rules true, and still lets graphics read
the result on every reused frame before the next real compute run. Two counters,
`compute dispatches <pass>` and `compute reuses <pass>`, track real runs versus reuses; a skipped
kernel does not add a fake zero-time sample. A kernel that reuses often will simply have fewer
timing samples than the rolling window holds. This saves the kernel's own GPU work, not the cost
of submitting it.

### Geometry-pass inputs (`u_GeomInput0..7`) and `builtin.depth_opaque`

A `GEOMETRY`-typed pass, previously a pure placeholder for Sodium's own opaque/cutout terrain draw
(see §3), can declare `inputs = [...]` like any other pass type, resolved onto a small, fixed set of
sampler slots appended to Sodium's shared terrain bind group (descriptor set 0):
`u_GeomInput0..GeometryInputs.RESERVED-1` (`GeometryInputs.RESERVED == 8`). The slot count is fixed
at class-init, before any pack loads, because `ShaderChunkRenderer.BIND_GROUP` is a process-wide
static built once (`ShaderChunkRendererBindGroupMixin` appends the eight slots there); it cannot vary
per pack the way a `TargetRegistry` allocation can.

- **Slot mapping is declaration order.** A geometry pass's *i*-th declared input resolves onto
  `u_GeomInput{i}` (`GraphRunner.refreshGeometryInputViews`, called once per frame from `prepare()`,
  before Sodium's own opaque terrain draw, the mixin bind site's only consumer). An undeclared
  trailing slot, including every slot when no pack or no geometry pass is active, is bound to
  `builtin.noise` as a safe, non-garbage default, never a null or stale view. A slot whose declared
  input transiently fails to resolve (a compile-disabled target, a registry mid-rebuild) falls back
  to noise for that frame the same way rather than propagating the failure.
- **`runtime_enabled_if` gates a pass per frame.** Same grammar as `enabled_if`, checked each frame
  against the world rather than the pack's compile options. One name, `dimension`, whose numbers
  come from `util/DimensionId` and reach a shader as `u_WorldBounds.w`, so a gate and a shader
  cannot disagree. `runtime_enabled_if = "dimension != 3"` stops a pass in the End. The shadow
  driver reads the pack's shadow-caster pass this way: gate it off and the shadow phase is skipped.
- **Geometry passes are keyed by `GeometrySlot`.** A `type = "geometry"` pass names which kind of
  geometry its `program` shades via its `slot` key (`terrain`, `entities`, `hand`, `particles`,
  `weather`, `sky_basic`, `sky_textured`, `clouds`, `beacon_beam`, `lightning`, `damaged_block`,
  `armor_glint`, `spider_eyes`, `lines`, `block_entities`, `end_portal`, `shadow`, and others).
  Omitting `slot`
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
  equality). Mapped today: the entity and item pipelines, block entities, the solid particle arm,
  weather, beacon beams, lightning, clouds, block-outline lines, and the End portal and gateway.
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
makes decals z-fight or vanish. Translucent entity pipelines get their own slot because blended
geometry can never write an unblended multi-target G-buffer, so it stays forward-shaded like
terrain's translucent arm. The constraint is a shading one, not an API one: blend state is
per-attachment. `withColorTargetState(int, ColorTargetState)` takes an `Optional<BlendFunction>` per
attachment and `build()` compares only the blend functions present, so `DeferredGeometryPipelines`
hands all five G-buffer lanes `Optional.empty()` in a loop.
- **`builtin.depth_opaque`** (`OpaqueDepth`) is the one builtin usable only from a geometry pass;
  `checkInputRef` rejects it for every other pass type, since only a geometry pass has the
  SOLID/CUTOUT-vs-TRANSLUCENT sub-draw split the freshness rule below depends on. It is an
  engine-owned, self-managed D32 texture, kept out of `TargetRegistry` on purpose (whose
  `TargetFormat` has no depth format, and whose reconcile clear path is a colour render pass that
  cannot clear a depth attachment): `OpaqueDepth.ensureSize` builds and clears it to the reversed-Z
  far value (`FAR_CLEAR = 0.0`) at allocation, the same MoltenVK garbage-VRAM rule every
  `TargetRegistry` target follows, applied by hand, and `GraphRunner.closeCurrent()` frees it
  on every pack teardown (unload, pack switch, mid-session rebuild), reallocated fresh on the next
  `prepare()` with a pack active. `GraphRunner.computePackReferencesOpaqueDepth`, worked out once
  per `rebuild()` next to `packDeclaresDepthCopyback`, gates both the allocation and the per-frame
  capture below: a pack with no geometry pass asking for this input pays neither the full-size D32
  allocation nor the per-frame copy. `VoxelWaterReflBuffer` and `AnalyticLightListBuffer` allocate
  when a pack asks for them and free when it does not, the same way.
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

### Array targets (a `consolidate` pass's output)

`TargetKind` only has `texture` and `buffer`: no array kind, since `TargetInstance`/
`TargetRegistry.reconcile` hardcode `depthOrLayers = 1`. A `consolidate` pass
(`PassType.CONSOLIDATE`) copies several same-shaped inputs into one layer each of a shared array
texture (`ArrayTextures.copyLayer`, a hand-recorded `vkCmdCopyImage`, see §12), so a later
fullscreen pass reads them through one `sampler2DArray` instead of one `sampler2D` per input.
Metal caps distinct sampler states, not textures: 40 `texture2d<float>` bindings sharing 2
samplers compile; 17 distinct samplers don't.

Its declared `output` is never a `[targets.*]` entry, since `TargetKind` can't express its shape.
`GraphValidator.checkConsolidatePass` refuses a name collision; `GraphInputResolver` resolves it
against `GraphRunner.consolidateTargets()` (a static accessor, like `packTextureRegistry()`/
`opaqueDepth()`) before falling back to `TargetRegistry`, the same way `mipchain` resolves against
`mipchainTargets`. `ConsolidateRunner` owns the array texture outside the registry.

Inputs are one of two kinds, never mixed in one pass:

- Declared `[targets.*]` textures, sized from the first input's `TargetSpec` (every input must
  share format and shape) via the usual `TargetPlan.textureWidth`/`textureHeight` formula.
- The three allowlisted G-buffer builtins in `GraphValidator.CONSOLIDATE_BUILTIN_FORMATS`
  (`builtin.gAlbedo`/`gMaterial`/`gAo`, all RGBA8 at G-buffer resolution), sized directly off
  render resolution via `GBufferManager.ensureSize`. `gNormal` and `gMotion` are excluded: their
  formats differ, which an array texture can't express.

`GraphValidator.checkConsolidatePass` requires: no `shader` (engine-owned, like `copy`); no
`enabled_if` (no declared target for `checkGateConsistency` to check a reader's gate against, so a
gated pass is refused outright); at least one input, exactly one output; inputs all declared
targets or all builtins, never mixed; declared-target inputs agree on format and shape; output
name doesn't collide with a declared target or pack texture asset.

### Engine-shipped shader includes (`<fornax:...>`)

A pack reaches an engine include with `#moj_import <fornax:name.glsl>`; `ShaderImports.ENGINE_INCLUDES`
is the allow-list, and a name absent from it fails pack load naming the file rather than splicing an
error message into the composed GLSL. Three names are served: `globals.glsl`, `block_atlas.glsl` and
`ray_answer.glsl`. `chunk_vertex.glsl` sits in the same resource directory and is deliberately not on
that list; packs receive their own copy through `fornax_runtime`.

`ray_answer.glsl` decodes a ray-traced result. It declares `FORNAX_RAY_TIER_NONE`,
`_SOFTWARE_VOXEL`, `_HARDWARE_VOXEL` and `_HARDWARE_MESH` as the ordinals of `RayTier`, and two
helpers: `fornaxRayAnswered(vec4)` tests the validity channel A, and `fornaxRayTier(vec4)` reads the
tier out of G with a half-step round, since the channel is a float carrying a small integer. It
carries no `#version` directive, because blaze3d splices an include into a file that already has one.
`RayAnswerGlslContractTest` reads the file and the enum together: the two copies of the ordinals have
no compiler between them.

## 6. Uniform contracts

### `u_Globals` (std140, 880 bytes)

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
| `u_HeldLight` | vec4 | 688 | 16 |
| `u_WeatherAnchor` | vec4 | 704 | 16 |
| `u_CameraDelta` | vec4 | 720 | 16 |
| `u_LocalActorPosition` | vec4 | 736 | 16 |
| `u_LocalActorMotion` | vec4 | 752 | 16 |
| `u_LocalActorShape` | vec4 | 768 | 16 |
| `u_LocalActorFluid` | vec4 | 784 | 16 |
| `u_WorldClock` | vec4 | 800 | 16 |
| `u_WorldBounds` | vec4 | 816 | 16 |
| `u_CameraBiome` | vec4 | 832 | 16 |
| `u_PlayerMirrorState` | vec4 | 848 | 16 |
| `u_PlayerMirrorWalls` | vec4 | 864 | 16 |

Total: 880 bytes exactly, the size `UniformBufferManagerMixin` widens the ring storage to. Both
sides apply the same std140 alignment rules (std140 is a fixed, standard packing convention that
lets GPU shader code and CPU-side buffer-writing code agree on where each field sits in memory) to
the same declared type sequence in the same order, so in
principle the offsets can only agree. But they are written in two languages by hand, and a
disagreement produces no compile error, no validation failure, and no log line: only a uniform
silently holding a neighbouring field's bytes. `GlobalsLayoutContractTest` therefore computes the
block size from `globals.glsl` under std140 rules and asserts it equals what
`UniformBufferManagerMixin` allocates, so a divergence fails the build instead of surfacing as a
feature that mysteriously does nothing. A second test checks field order too, reading
`GlobalUniformsWriteMixin`'s `Std140Builder` call sequence off its source and comparing it to
`globals.glsl`'s declared order, type for type. Size alone can match with two fields swapped;
this catches that case.

`u_CameraBiome` is one vec4 of world facts, always sent, read by `BiomeProbe` at the block the
camera is in, at its own height: x is the pack's ID for that biome, y is the biome's heat, z is its
heat at that height using the world's sea level, and w is how much it rains. Heat uses Minecraft's
own scale, not degrees. Heat and rain are still sent when the pack gave the biome no ID (x = 0),
and nothing here is smoothed or read as weather. With no world or no camera every number is 0, and
the probe keeps nothing from the world before. Rain and snow at the camera stay in
`u_CameraSkyLight.y`. Adding this at the end leaves every earlier offset where it was, and a shader
that names only the first part of the block still reads the same buffer.

`u_PlayerMirrorState` is one vec4 after the camera biome, for drawing the player's own reflection.
`x` is 1.0 when a water surface sits within reach of the player's feet and the eye is dry, 0.0
otherwise (no water below, or the eye is underwater, so there is nothing to mirror against from
outside it). `y` is that surface's world height in blocks: the water block's own Y plus the fluid's
rendered height (about 0.889 for a still source). `zw` are reserved and zero-filled. `x` = 0.0 tells
every consumer to keep its no-mirror behaviour, the same rule every valid/enum lane in this block
follows. `WaterPlaneProbe` runs the scan as a pure function, tested with a plain height function
instead of a level, bounded to the feet block plus 8 blocks below (a player who can see their own
reflection floats at most a couple of blocks above it). It shares the eye-in-water flag
`u_WaterState.x` already computes, read beside it in `GlobalUniformsWriteMixin`.

`u_PlayerMirrorWalls` is one vec4 after `u_PlayerMirrorState`, for the vertical wall beside the
player on the X axis and the Z axis, each tracked on its own. Nothing reads this lane yet. `x` is
the X-axis wall's facing: -1, +1, or 0 when no wall is found within reach. `y` is that wall's plane
position, relative to the camera: world X minus the camera's X, subtracted in double before the
cast to the float32 this lane holds. A wall's position can sit tens of millions of blocks from the
origin, where float32 spacing alone is coarser than the mirror pass's own 0.05-block receiver
guard, so the camera-relative value is kept instead of an absolute one. `z`/`w` hold the same pair
for the Z axis. `WallPlaneProbe` runs the scan: from the feet block and the block above it, four
scans outward (+X, -X, +Z, -Z), each up to 8 blocks, for the first block whose visual shape
(`getShape`, the same method the standing-plane scan in `WaterPlaneProbe` uses) is not empty. Per
axis, whichever of the two opposing directions the camera faces wins, its face normal checked
against the camera's own forward vector; the nearer wall breaks a tie. It shares the eye-in-water
flag `u_PlayerMirrorState` uses.

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

`ShadowCasterLists` moves a region's eight box corners through the light matrix once and reads
both answers off the same numbers: whether the box touches the shadow area at all, and whether it
sits wholly inside it. A region that sits wholly inside skips the check on each of its sections.
The public touch test that mesh RT snapshots call reads those same numbers.

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

Every pass whose name starts with `glint_occlusion` gets the shared sun and debug parameters, so an
extra one such as `glint_occlusion_voxel` matches `water_composite` on sun direction, true sun
height and `u_Param2` terrain distance. A name the engine does not know reads reset defaults, with
no error.

The exact `voxel_water_reflection` name and every name starting with `voxel_water_reflection_`
share that same parameter block. Diagnostic variants such as `voxel_water_reflection_probe_trace`
therefore receive the base pass's live sun/moon direction, true sun height and terrain distance.

`water_volume_composite_submerged` gets the same live sun/moon data as the water-volume march and
history passes. Its full-resolution pass adds up direct light, so a reset-zero sun vector would not
match the half-resolution pass. The test that checks this follows shared shader imports as well as
reads in the main file, and counts a read inside an imported function even if one shader variant
never calls it.

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

### Section arrival time (`u_SectionTimeInfo`)

One signed 32-bit integer per section slot, addressed `u_RegionID * 256 + _draw_id`, bound on the
shared terrain bind group for every terrain draw. Sodium owns the buffer and writes each slot once,
at mesh build, as milliseconds on the owning region's clock (region creation is the epoch).
`u_CurrentTime` in the per-region push-constant block is the same clock, written by
`DrawContextVKMixin` with the identical `System.currentTimeMillis() - region.getCreationTime()`
narrowing. Only their difference is meaningful; either side changing its epoch silently breaks
every fade. Negative means settled. The engine never writes the buffer. Gated on the pack graph
being active; with no pack, stock Sodium draws terrain from its own stamps.

## 7. Vertex format

The encoder reads a quad's per-block facts (material id, precipitation, light emission, block
class) from `MaterialIdContext`, live only while that block is meshed. Translucent sorting keeps
quads as vertex objects, splits intersecting ones along a plane, and re-encodes the pieces after
the per-block loop with the context cleared. `ChunkVertexFactsMixin` stamps the destination of
every `copyVertexTo` with `VertexFacts`: from the context on the first copy, from the source's
stamp on later ones. The encoder prefers a stamp and otherwise reads the context. The push path's
scratch vertices are never copied, so they read the context. Without the stamp a split water quad
loses `MAT_WATER`, emission, class and precipitation.

Terrain uses a dedicated 24-byte vertex format, substituted in place of Sodium's own compact format
at its one construction site. Because every real consumer of vertex layout resolves the concrete
format dynamically through an interface, this substitution needs no changes anywhere else; Sodium's
own shaders are never used for terrain while a pack is active.

| Attribute | GPU format | Offset | Size | Contents |
|---|---|---|---|---|
| `a_Position` | RGBA16_UNORM | 0 | 8 | xyz fixed-point position codes at 2048 steps/block with origin -8; w packs emission and block-class facts |
| `a_TexCoord` | RG16_UNORM | 8 | 4 | direct, unbiased atlas UV |
| `a_Color` | RGBA8_UNORM | 12 | 4 | vertex colour already combined with baked ambient occlusion |
| `a_LightAndData` | RGBA8_UINT | 16 | 4 | x = block light (0-15), y = sky light (0-15), z = renderer-internal material/render-layer bits (not the block-material ID), w = draw/region id |
| `a_Normal` | RGBA8_UINT | 20 | 4 | x = flat face index (0-5), y/z = u16 block-material category ID (low byte first), w reserved |

**Position agreement.** XYZ stores `clamp(round((position + 8) * 2048), 0, 65535)`.
The shader reads back the whole number with `floor(a_Position.xyz * 65535 + 0.5)`, then divides by
2048 and subtracts 8. This grid keeps block faces and model steps lined up exactly across 16-block
section edges; scaling the raw attribute straight over 32 blocks instead would leave gaps at
matching edges. The range covered is `[-8, 24 - 1/2048]`; +24 and above clamp to the highest code
instead of wrapping around. UVs keep their own separate 65535-based UNORM encoding, and position W
still carries its packed facts. Every pack-owned position decoder must match this XYZ scheme.
`FornaxChunkPositionTest` checks the real encoder against the engine's own decoder, the example
decoders, and a sibling pack when one is present.

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

All 76 mixins are `required: true`; a mixin listed in the mixin config that fails to apply is a hard
load error, but a mixin not listed at all is never applied, with no warning of any kind. There is
nothing to distinguish "intentionally removed" from "accidentally dropped from the list" except
checking the list itself.

Seven of the 76 are full-method `@Overwrite`s (the highest-risk mixin shape, since it silently
stops tracking whatever the original method does beyond what was true at the time it was written):
`CompactChunkVertexMixin`, `DefaultChunkRendererRenderPassMixin`,
`ShaderChunkRendererDeferredPipelineMixin`, `DrawContextVKMixin`, `DrawContextGLMixin`, and
`UniformBufferManagerMixin` (one file carries two). Every other mixin is additive, injecting,
redirecting, wrapping, or modifying against a specific instruction or call, changing only what
needs to change and leaving the rest of the original method to evolve upstream without needing to
be restated here.

**Sodium-targeting** (23):

| Mixin | Target | Purpose | Shape |
|---|---|---|---|
| `BlockRendererMaterialIdMixin` | `BlockRenderer` | Set/clear the per-thread material ID around each block's model meshing call | Inject (HEAD/RETURN) |
| `ChunkVertexFactsMixin` | `ChunkVertexEncoder.Vertex` | Stamp each copied vertex with its block's packed facts (`VertexFacts`) so quads split by translucent sorting keep them past the context clear | Inject |
| `ClientChunkCacheVoxelLightMixin` | `ClientChunkCache.onLightUpdate` | Queue a voxel world-light refresh for light-only changes, whether or not the section is meshed or visible; does nothing with no pack active | Inject |
| `ChunkBuilderMeshingTaskMixin` | `ChunkBuilderMeshingTask` | Queue each section's voxel-grid harvest the moment Sodium (re)builds it, piggybacking on Sodium's own change detection; the harvest itself runs on a dedicated background thread (`VoxelWindow.queueMeshTriggeredHarvest`), never inline on Sodium's own meshing thread | Inject |
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
| `SectionRenderDataStorageRevisionMixin` | `SectionRenderDataStorage` | Stamp each vertex-storage mutation before it starts; resize/deletion stamp every region slot. `TerrainMeshRevision` exposes process-unique generations, so RT mesh caches cannot alias same-size uploads or recycled storage | Inject (HEAD) |
| `ShaderChunkRendererAccessor` | `ShaderChunkRenderer` | Expose the private static compiled-pipeline cache so it can be cleared on a render-state flip | Accessor |
| `ShaderChunkRendererBindGroupMixin` | `ShaderChunkRenderer` | Append the normal/material sampler slots, the PBR-settings uniform, and the reserved `u_GeomInput0..N-1` geometry-input sampler slots to the shared terrain bind-group layout | WrapOperation |
| `ShaderChunkRendererConstantsMixin` | `ShaderChunkRenderer` | Add a deferred-output shader constant for opaque/cutout passes only, while a pack is active | ModifyReturnValue |
| `ShaderChunkRendererDeferredPipelineMixin` | `ShaderChunkRenderer` | Build the five-attachment colour-target-state set for deferred pipelines, leaving translucent's single-target state untouched | WrapOperation |
| `ShaderChunkRendererShaderLocationMixin` | `ShaderChunkRenderer` | Redirect terrain shader compilation to the active pack's runtime shader (or the engine's built-in fallback with no pack active) | Redirect x2 |
| `SodiumWorldRendererOrchestrationMixin` | `SodiumWorldRenderer` | Bracket the opaque draw with the graph interpreter's per-frame prepare/finish | Inject x2 |
| `SodiumWorldRendererReloadMixin` | `SodiumWorldRenderer` | The single boundary where the render-state latch advances; also clears the static pipeline cache so the next draw recompiles under the new state | Inject |
| `SodiumWorldRendererRenderLayerMixin` | `SodiumWorldRenderer` | Populate shared per-frame render context and refresh PBR settings once per pass | Inject |
| `UniformBufferManagerMixin` | `UniformBufferManager` | Widen the shared per-frame uniform buffer and append the motion-vector/jitter fields; add a second small ring buffer for PBR settings | ModifyArg, Inject x2, WrapOperation |

**Vanilla/Blaze3D-targeting** (22):

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
| `TextureAtlasVoxelLifetimeMixin` | `TextureAtlas.clearTextureData()V` | Before the block atlas closes sprite CPU images, cancel new voxel model reads, drain active leases and retire cached/queued harvests; reload and shutdown share this boundary | Inject (HEAD) |
| `ModelManagerVoxelLifetimeMixin` | `ModelManager.apply(ModelManager$ReloadState)V` | Reopen voxel model reads only after successful model publication and request a stationary-camera resync | Inject (RETURN) |
| `MultiPartModelAccessor` | `MultiPartModel.models` | Read selected children to prove deterministic model geometry; no rendering mutation | Accessor |
| `TextureAtlasReleaseGenerationMixin` | `TextureAtlas` | At `upload` HEAD, select a rebuild scope and hand it to `AtlasGenerationSchedule`: changed block = sidecars + overflow/grid, unchanged block = retain sidecars but retire overflow/grid, changed non-block mirrored atlas = sidecars only, unchanged non-block = no-op. Released resources rebuild only after three render-loop-separated polls | Inject (HEAD) |
| `VulkanRenderPipelineMixin` | `VulkanRenderPipeline` | Declare the widened 60-byte push-constant range on every terrain-family Vulkan pipeline layout | WrapOperation |
| `WindowMixin` | `Window` | Report the supersampled dimensions while a scaled frame is in flight, so downstream size queries stay consistent | ModifyReturnValue x2 |

**Raw-Vulkan-targeting** (6):

| Mixin | Target | Purpose | Shape |
|---|---|---|---|
| `GpuDeviceBackendAccessor` | `GpuDevice` | Expose Blaze3D's private backend so raw compute/interop code can require the Vulkan backend explicitly | Accessor |
| `VulkanCommandEncoderPartialFlushMixin` | `VulkanCommandEncoder` | Add the explicit `VulkanPartialFlush` operation for event-ordered dispatch without advancing full-submit completion or resource retirement | Unique, Shadow |
| `VulkanCaptureBufferUsageMixin` | `VulkanDevice` | Enable COPY_SRC on uniform allocations only with startup capture configuration | ModifyVariable |
| `VulkanGpuBufferSharingMixin` | `VulkanGpuBuffer.Direct` | Give uniform allocations concurrent access to distinct graphics and compute families, including buffers allocated before pack activation | ModifyArg |
| `VulkanCaptureSamplerStateMixin` | `VulkanGpuSampler` | Retain the exact native sampler creation arguments only with startup capture configuration | ModifyArg, Unique |
| `VulkanDeviceExtensionMixin` | `VulkanDevice` | Add `VK_EXT_metal_objects` to the requested device-extension set on supported macOS systems | ModifyExpressionValue |

**YACL-targeting** (3):

| Mixin | Target | Purpose | Shape |
|---|---|---|---|
| `CategoryTabMixin` | `YACLScreen$CategoryTab` | Inject the Import.../Export.../Defaults... chrome buttons into YACL's own right-side button cluster (beside search/Reset/Undo/Done) on a `PackManageScreen`-built screen, scoped via `screen.PackChromeActions`; shrinks `descriptionWidget` by replacing its dimension supplier (via `OptionDescriptionWidgetAccessor`) rather than a one-off resize, since it re-reads that supplier every frame. Fails soft: any lookup/layout failure logs one warning total and leaves stock YACL chrome untouched, never crashing the screen | Inject (TAIL) x2 |
| `OptionDescriptionWidgetAccessor` | `OptionDescriptionWidget` | Expose the private final `dimensions` supplier so `CategoryTabMixin` can wrap it | Accessor |
| `YACLScreenCloseMixin` | `YACLScreen` | Route close/apply through the active pack edit session so staged pack values are not lost | Inject |

### Access widener

`src/main/resources/fornax.accesswidener` (`official` namespace: the 26.2 jars are already named
and Loom runs with obfuscation disabled, so `named` is refused at build time) widens three private
biome members for `PrecipCoarseClipmapUpload`: `Biome.getTemperature(BlockPos, int)`, the memoised
height-adjusted temperature the precipitation classification thresholds; the `climateSettings`
field; and its package-private record type, for `downfall()`. A mixin accessor cannot name that
type from outside its package, which is why this is a widener and not a mixin. Referenced from
`build.gradle` (`loom.accessWidenerPath`) and `fabric.mod.json` (`accessWidener`); a missing
reference in either is silent, so `BiomeClimateAccessContractTest` pins both.

## 9. Material system

`blocks.toml` declares named categories, each listing block IDs and/or block tags
(`#namespace:path`). Categories are compacted into dense integer IDs, 1-based, in file declaration
order; ID 0 is reserved for "uncategorized" (pure LabPBR, no synthesis). IDs are capped so they fit
the 16-bit vertex channel that carries them.

**Voxel source selection.** Optional root `[lighting] voxel = false` makes local voxel lighting
opt-in; a category's `lighting.voxel = true` or `false` overrides that default. Uncategorized
blocks and categories without an override inherit the root value. With neither declaration,
existing packs retain all sources. Only boolean `voxel` is accepted inside either lighting table;
unknown keys and incorrect types fail pack loading. Membership is independent of emissive
synthesis, intrinsic block emission, LabPBR emission, and geometry: opting out removes the block
from `voxelSourceWindow`'s source set, without removing self emission or its ability to occlude.
It does not identify or subtract that source's contribution from Minecraft's merged lightmap.

`MaterialResolution` resolves the default and category overrides into `MaterialScalars`. Each
section harvest stores a separate immutable, two-word `VoxelSourcePolicy` palette mask, even
when source diagnostics are disabled. Overflow or an unmapped cell marks that policy incomplete
rather than trusting an aliased palette index. Raw `VoxelSourceEvidence`, summary and diagnostic
emitter-pool data remain unchanged. `withLightmap` preserves policy identity. A blocks-manifest
reload follows graph rebuild: `closeCurrent()` detaches voxel storage. Category/tag resolution
also has its own `VoxelHarvestLifecycle.publishMaterials` boundary, so a datapack tag reload with
an unchanged `TargetRegistry` immediately retires cached rows and queued old-policy harvests.
It cancels and drains CPU read leases, invalidates voxel storage, then publishes block membership
and scalars together. A successful update reopens harvesting only if models were already available;
resolving tags cannot reopen a retired atlas. Production harvest callers read the scalar snapshot
inside their lease, avoiding an old snapshot being paired with a new generation. The radius-zero
sentinel forces same-camera refill and the next source-generation synchronization invalidates GPU
section states. No harvest lock is held while acquiring the GPU queue lock; callers already holding
either a queue lock or read lease are rejected before a drain. Newly harvested sections carry the
refreshed category policy.

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
| `frameGenMode` | enum (`FrameGenMode`) | `OFF` | Experimental MetalFX frame generation: interpolates one frame between real frames. Requires `aaMethod = METALFX` and vsync (FIFO present mode); adds roughly 1 frame of latency when engaged. `OFF` disarms `FrameGenPass.armed()` entirely. `AUTO` adaptively engages/disengages the actual double-present per frame (hysteresis around render fps vs. display refresh; see "Adaptive pacing" below), so arming this does not guarantee constant latency/cost, only that the machine genuinely needs the assist. `ALWAYS` bypasses that pacing and double-presents every armed frame unconditionally, holding real fps at roughly half the display refresh under FIFO for as long as it is selected: the deliberate escape hatch for a player who has already capped their own frame rate there (see "Adaptive pacing"). macOS 26+ Apple Silicon only; the settings-screen toggle is built only when `MetalFxSupport.isFrameInterpolationAvailable()`, greyed out/hidden otherwise. Live-read every frame by `FrameGenPass.armed()`/`FrameGenPass.mode()` (no pack recompile needed). Transitioning to `OFF`, or switching `aaMethod` away from `METALFX`, releases `FrameGenPass`/`UiLayerCapture`/`FrameGenPresenter`'s interop resources via the shared `FrameGenPresenter.deactivateAll()`, called from two places: `SettingsApplyRouter`'s `FRAMEGEN_DEACTIVATE` action at save time (the before/after field diff, same mechanism as `PACK_REAPPLY`/`SAVE_ONLY`; never from the option's own YACL listener, since YACL applies every option's binding before any listener fires, so a listener can't tell whether this is the transition frame), and `GraphRunner.closeCurrent()` on every pack teardown (frame generation runs independently of pack state and would otherwise keep presenting against GPU state a pack reload just tore down); `AUTO`<->`ALWAYS` does not route that action, since both keep the same resources armed and only `FrameGenPacer`'s per-frame decision differs. A pre-`frameGenMode` config's boolean `frameGeneration` field migrates `true` to `AUTO` and `false` to `OFF` (schema v3 -> v4; see "Migration") |
| `metalHud` | boolean | `false` | Apple's Metal Performance HUD overlay (`CAMetalLayer.developerHUDProperties`, macOS 13+), replacing a manual `MTL_HUD_ENABLED` export with a settings toggle, independent of `aaMethod`. `FornaxPreLaunch` calls `MetalHudEnv.enableIfConfigured()` when true: a native `setenv("MTL_HUD_ENABLED", "1", 1)` before Minecraft's `main()`, since the HUD subsystem must exist before `Metal.framework` loads. Restart-to-apply in both directions under MoltenVK (see "Known laws"): `MetalHudControl.apply(enabled)` sets/clears the layer's `mode` key and logs a verdict from a live save, but only the `CLIENT_STARTED` call (config already true at boot) ever visibly shows the HUD. Toggle built only when `Objc.isLoaded()`. Fails closed: any resolution failure logs once at WARN and does nothing |
| `rayTracing` | enum (`RayTracingMode`) | `AUTO` | Ray Tracing Backend, shown on every platform under Engine/World: Automatic (default), None, Metal RT. Existing JSON names `AUTO`/`OFF`/`FORCE` retain those meanings without a schema rewrite. Metal RT is the only implemented API and is offered explicitly only when `MetalRtSupport.isSupported()` passes. Automatic uses the device's `supportsRaytracing` answer; Apple9 is diagnostic only. `isAvailableFor(packSubscribed)` combines live backend selection with a pack subscription and does not probe when unsubscribed or None. Unsupported devices retain normal pack rendering; this control does not itself enable a shadow feature. |
| `rtDebugMode` | enum (`RtDebugMode`) | `OFF` | Serialized compatibility field; always normalized to `OFF` at load. No scene-debug selector is exposed in settings. |
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
tolerates the null by construction (`null != OFF` still reads as "supersampling was on"). The v3
step reads the boxed `Boolean frameGeneration` field (migration-only; boxed so an absent key stays
distinguishable from a persisted `false`), sets `frameGenMode` to `AUTO` when it was `true` and
`OFF` otherwise, then nulls it; since this config's Gson has no `serializeNulls`, the dead key
stops being written on the next save. A version-ungated guard normalizes a `null` `frameGenMode`
to `OFF` the same way the `ssaaPreset`/`debugView` guards do. Finally `schemaVersion` stamps to
`CURRENT_SCHEMA_VERSION` (4, bumped for the `frameGeneration` -> `frameGenMode` split so an old
file is rewritten on disk exactly once). Gson leaves any field absent from the JSON at
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
and, as literal `#define` lines, prepended to every fullscreen and compute entrypoint by
`GraphRunner.prepareShaderSources`, the source-composition path used by `rebuild`. Facts follow
`#version` and precede imported helpers, independently of runtime-option declarations or a compute
pass's `packOptions` input. The generated `u_PackOptions` block keeps its separate descriptor-binding
rules; geometry, particle and mipchain shaders do not receive this engine preamble. GLSL can use
`#if FX_UPSCALE` directly. A missing fact silently reads as zero in `#if`, so testing a shader with
hand-injected definitions cannot verify delivery. `CameraJitter` (`pass.taa`) re-keys off the same field: `OFF`/
`SSAA` jitter to `(0,0)`; `TAA` keeps the original 4-tap rotated grid; `TAAU` and `METALFX` use a
Halton(2,3) low-discrepancy sequence (`CameraJitter.haltonNdc`), its cycle length driven by
`taauRatio`. `GameRendererMixin`'s projection jitter and `UniformBufferManagerMixin`'s
jitter-uniform-upload gate both read `aaMethod.wantsJitter()` rather than a pack compile option.
Changing `aaMethod` from the Engine settings screen calls `PackReload.reapplyActivePack()`, a full
pack graph rebuild (the same class of action as a pack compile-option edit, since the `FX_*` defines
change which shader text compiles), but on purpose not `RendererReload.request()`: only the pack
graph's fullscreen and compute shaders change, never the terrain pipeline shape `RendererReload`
exists to resync.

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
(`sceneHistory`, `rgba8`, `history = true`) written under every `aaMethod`, including `OFF`,
while the pack graph is active. The master shaders toggle retains its allocation, but
`GraphRunner.sceneHistoryTarget()` returns null while the graph is inactive, so the post-frame copy
records no GPU work. This uses the renderer's latched activity, matching `prepare()`/`finish()`;
an AA method of `OFF` alone still permits history for an active pack. A pack's own SSR/resolve
passes read `sceneHistory.history` as an ordinary input,
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
(`rgba8`), prepared motion (`rg16f`), reversed-Z depth (`d32f`), and an R8 reactive mask.
`MetalFxSkyMotionPass` prepares motion before copy-in: geometry vectors are fetched unchanged,
while far-clear sky pixels use the same current jitter-free `SkyReprojection` homography as the
engine temporal resolve. The input raster coordinate first loses its current projection jitter;
both motion endpoints then share the geometry vectors' jitter-free basis. Behind-eye, off-screen and non-finite sky projections produce a finite
out-of-bounds history coordinate. Its separate render-resolution target is cleared at allocation;
resize retires the old texture only after pending GPU readers complete. The G-buffer is untouched.
This supplies rotational sky motion, not finite cloud parallax or wind. The mask is
generated immediately before copy-in by `MetalFxReactiveMaskPass` from the same
scene-depth-minus-G-buffer-depth predicate as the engine reconstruct: near first-person pixels are
1.0 (ignore temporal history), while other translucent overlays are 0.5 (favour current animated
water/glass without discarding all accumulation). Descriptor/scaler selectors for reactive masks are
checked at runtime before use; MetalFX versions predating macOS 14.4 fail closed to TAAU.

Cross-API ordering uses one exported Vulkan timeline semaphore / `MTLSharedEvent`: Vulkan copy-in
signals `v`, Metal waits `v`, encodes and signals `v+1`, Vulkan waits `v+1`, copies the unsharpened
native result directly into `SceneHistory.writeSlot`, then signals `v+2`. Both event handoffs use
`VulkanPartialFlush.fornax$flushPending`: finish ordinary recording and transient uploads, dispatch
the existing pending submission on the same graphics queue, replace its builder, and begin fresh
transient recording. This avoids the encoder's full-submit retirement wait; queue submission and
native encoding can still block. The normal full submit's `ALL_COMMANDS` completion covers all
preceding partial batches, retaining their command pools and destruction/checkpoint resources until
that normal epoch retires. Transient handles are invalid across a partial flush. Ordinary encoder
fences keep the normal epoch and cannot be completed by partial flushes, so initialization and
explicit fence waits retain full submission, as does the presenter. Resize waits once for the last
`v+2` value before destroying the whole interop image set; it does not call `vkDeviceWaitIdle` per image. Interop images use transfer-optimal layouts for
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

Before copying or interpolating, `FrameGenPass` checks the presenter's shared surface policy:
only `FIFO`/`FIFO_RELAXED` can consume generated presents. An unavailable surface or VSync-off
mode clears generated readiness and history, so resuming seeds one fresh frame before interpolation.
The generated-image `debugView` override can still run without a presentable surface. The overlay
reports `VSync required` or `surface unavailable` ahead of the adaptive pacer's status; `ALWAYS`
bypasses the FPS threshold, not the surface requirement. `frame generation CPU` and
`MetalFX upscale CPU` measure the respective armed calls, including encoding and any blocking
inside native calls; they are CPU elapsed time, not Metal GPU durations.

Per eligible frame: a Vulkan copy lands the upscale pass's native colour into `curColor`, signaling `v` on
the pass's own timeline. Once two frames of history exist and `FrameClock.ready()` (EMA-smoothed
wall-clock delta, hitch-clamped), Metal GPU-waits `v`, encodes the interpolator against
`prevColor`/`curColor` plus the reused depth/motion textures, writes `generated`, and signals `v+1`.
The copy-in and generated-image copy-out use the same partial-flush operation as the upscale pass,
avoiding full-submit retirement waits while preserving the shared-event ordering and teardown waits.
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
display can already show natively: this is the `FrameGenMode.AUTO` policy specifically. Every
armed frame, `FrameGenPass.runIfEnabled` calls `FrameGenPacer.update(CLOCK.emaIntervalNanos(),
FrameGenPass.mode())`, the single per-frame decision point both this method's own arming and the
present seam trace back to (see `FrameGenPacer`'s own header), which under `AUTO` compares render
fps against the display refresh rate (`Window.getRefreshRate()`, confirmed by
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

**`FrameGenMode.ALWAYS` is a separate policy, not a fourth point on this curve.** No threshold
inside the 0.40-0.48 band could ever produce an always-engaged policy: any disengage threshold at
or above 0.5 is mathematically unreachable for the reason above, so `AUTO` can never be tuned into
"stay engaged unconditionally." A player who has capped their own frame rate at or below half the
display refresh (60fps on a 120Hz panel, wanting 60 real + 60 generated) needs a genuinely
different policy, not a retuned threshold: `FrameGenMode.ALWAYS` bypasses `computeEngaged` entirely
and forces `engaged = true` on every call, including before the render clock has warmed up (unlike
`AUTO`, which holds state until `emaIntervalNanos > 0`). The tradeoff is symmetric with `AUTO`'s own
reasoning: `ALWAYS` holds real fps at roughly `displayHz / 2` for as long as it is selected,
correct when the player wants exactly that ceiling, a regression on an uncapped session that could
otherwise render faster on its own.

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

### Uploaded-mesh celestial shadows

A graph-level `[ray_traced_shadows]` declaration plus an enabled `rtTerrainShadowDepth` reader opts
into `MeshMetalProvider`, the cascade's `HARDWARE_MESH` tier. `GraphRunner.rebuild` installs it in
`RayRouter` wherever `Objc.PLATFORM_SUPPORTED` holds, and `closeCurrent` closes the router rather
than the provider by name. The engine defaults to automatic backend selection; None and absent or
disabled subscriptions short-circuit before hardware probing, mesh snapshots or native dispatch.
Metal RT is the implemented backend. The stored `rayTracing` enum retains compatibility, while UI
labels describe backend selection rather than a second pack feature switch.

`SodiumWorldRendererOrchestrationMixin.fornax$renderShadowPass` drives it: `RayRouter.beginFrame()`
at the top of the shadow pass, then, after the light camera is committed, `captureCasters` hands the
provider this frame's `RenderSectionManager` and `RayRouter.phaseOne(CelestialFill)` runs the tier.
The caster source travels by its own type rather than inside the neutral request, because it is
renderer-owned and can only be read at that point; the request carries the light matrices, the
camera in doubles, the resolution, the receiving radius, the warp bias and the filter guard.
Phase one sits here so the trace overlaps the terrain draw that follows.

The provider snapshots accepted SOLID/CUTOUT GPU ranges throughout the loaded light frustum on the
render thread, copies changed ranges to exported buffers, and feeds `MeshShadowTracer`. Packed
positions, quad topology and final atlas UVs come from `FornaxChunkVertex`; no voxel harvest or live
model emission participates. Mutation/relocation stamps prevent equal-size replacements from
reusing stale geometry. BLAS data is cached per mesh revision; TLAS changes only with the mesh set
or its stable grid-relative origins. Conservative packed-model overhang is included in culling.
The trace accepts both triangle faces, matching the raster shadow pipeline, which also turns face
culling off. SOLID and CUTOUT triangles share the raster alpha cutoff; see-through texels let the
ray keep going, and the nearest hit triangle sets the depth. A ray must not count as clear just
because it hit the back of a face, when the raster pass would still block light there. Native
tests with a reversed light, a quad, and a closed cuboid pin this match; they do not explain what
causes a problem seen in a screenshot.

The pack's distance is converted to blocks and capped by the shadow camera extent. It describes a
horizontal receiving cylinder, not a sphere of permitted blockers. Light-map rays outside this
cylinder plus declared filter support skip traversal. Surviving rays traverse their full near/far
span, so a larger distance value never lets back in a distant blocker already excluded for a near
receiver.
Inverse-warp rays outside the captured light-frustum domain remain invalid. The filter guard uses
a conservative inverse-warp Jacobian bound; singular guard neighborhoods retain traversal.

A shared timeline orders Vulkan copies, Metal trace, then Vulkan depth publication. Raw source
arena copies finish before the render thread can relocate/free their source on mesh-change frames.
`RT shadows CPU` measures the whole call, including mesh snapshots, native encoding and waits.
`RT mesh wait CPU` measures each existing mesh-change timeline wait, while
`rt_shadow_dirty_meshes` counts copied mesh revisions for that frame (zero when inactive or stable).
These scopes add no GPU queries or waits; total CPU time and its nested wait samples must not be
added together, and a zero dirty count does not prove that native encoding is inexpensive.
`TerrainShadowResult` owns the separate RGBA32F target: R forward depth, G the answering tier,
A current trace validity. `MeshShadowTracer` passes `RayTier.HARDWARE_MESH` in its constant
buffer at byte 160, and `mesh_shadow_trace` copies it into G in the same store that sets A, so
a texel never carries a tier without validity. Skipped rays and `mesh_shadow_clear` write
neither. The constant struct is 176 bytes: 16-byte alignment from its matrices leaves three
words of tail padding after the tier.
Disabled/unavailable/failed transitions clear validity. Only successful publication sets
`u_ShadowMapParams.y` to the effective receiving distance squared. The complete raster map remains
intact; there is no raster caster clipping or whole-section omission. Unsupported input preserves
raster fallback; submission failures disable the path until pack reload. Asynchronous GPU device
failure is not a recoverable raster handoff.

The pack chooses between independent maps at each receiving world position and unions independent
entity raster depth with RT terrain before filtering. Entity data uses the existing GPU replay;
there is no CPU dynamic-entity extraction. Softness, samples, strength, cloud transmission and active
sun/moon selection remain pack decisions. Legacy voxel RT targets remain separate, and diagnostics
alone cannot activate their pass without a live pack reader. Native and contract tests verify
geometry, subscriptions, resource routing, cache/event behavior and filtering; they do not establish
live frame time, successful runtime mixin injection or visual acceptance.

### Buffer query atlas addresses

A `[pass.ray_query]` table may set `atlas_uv_encoding = "texel_u16"`. Omitted or explicit
`"packed_half"` selects the default hit-word-3 packing: normalized u/v as two binary16 values.
Exact mode instead packs the level-zero atlas texel x in bits 0..15 and y in bits 16..31; readers
use `texelFetch` on that integer coordinate. Exact UV-known hits set flag bit 13
(`FLAG_ATLAS_TEXEL_U16`); packed-half hits, misses and records without UVs leave it clear. Readers
can pick their word-3 decoder from this flag. Both modes share the same nine-word, 36-byte hit
record, UV-known flag and ABI version 4. The encoding travels through `RayQuerySpec`, `BufferQuery` and
constant byte 28 (zero for packed half); no other member or buffer binding differs between modes.

In exact mode, the Metal cutout test and returned address use the same floor-and-clamp coordinate
conversion and direct level-zero read. This stops a hit that passed the alpha test at one texel
from returning another texel's colour after binary16 UV rounding on a large atlas. The interop
checks the bound atlas size before dispatch: each extent must be 1..65536, the range addressable by two unsigned
16-bit coordinates. An unknown encoding in the manifest fails at load; an oversized atlas fails the
query loudly rather than wrapping coordinates. The kernel also leaves unsupported encoding or size
requests unanswered. Opting in changes only what word 3 holds, so a pack that opts in must change
its word-3 decoder in the same edit. An engine without this key rejects the manifest instead of
silently using the wrong encoding. Native tests use a 16384x8192 cutout atlas and trace both modes to pin the
accepted-texel address; they do not prove live foliage stability or frame rate.

### Hardware-voxel fill tier (finite-domain voxel representation)

A graph declaring `rtSunDepth` receives an engine-owned RGBA32_FLOAT image at the exact
`ShadowMapManager` resolution. R is nearest alpha-tested forward shadow depth (1 for a miss),
G/B are finite-domain entry/exit depths, and A is validity. The image is allocated and explicitly
zero-cleared even when ray tracing is unavailable. Disabled, failed, empty-structure and resize
paths preserve A=0 rather than exposing a stale trace. `sunEntityShadowMapRaw` is a raw sampler
alias of the independent entity map; `sunShadowMapRaw` remains the combined raw map. A pack
unions matching texel depths before comparison/filtering and owns softness, strength and fades.
Packs that do not declare `rtSunDepth` allocate no such texture; declared consumers retain a
matching shadow-resolution, zero-invalid descriptor when RT is off or unsupported.

`rt_sun_depth.metal` inverts the current `ShadowFrameState` matrix and radial warp, traces along
increasing light depth, and shares the exact alpha intersection routine in `rt_trace.metal`.
It has no PCF pattern or style controls. Certified CROSS cells carry a direct `SingleVariant`'s
known `SimpleModelWrapper` baked quads and `BlockState.getOffset` at the absolute position.
Harvest never enters the live Fabric model-emission hook: connected-texture processing belongs
to the renderer's own lifecycle, including during chunk rebuilds. Custom models/parts and composite
models remain unknown because collected fallback parts do not certify their final rendered quads.
They are not unwrapped. Per-vertex UVs and individual face winding survive into supplemental
triangles; backface culling preserves distinct front/back alpha mappings. Unsupported partial
shapes and missing current alpha data also remain unknown, with no opaque-proxy claim. Affected
rays retain raster shadows. The supplement does not change the voxel lighting palette.

A section DDA checks only coherent owning sections before
the closest committed hit (the full interval for a miss). Because accepted supplemental geometry
may extend one block beyond its section, it also checks intersected expanded neighbor bounds;
the finite coverage excludes that same outer shell. Missing, stale, pending or unsupported owners
are unknown. Unrelated owner updates do not change a ray's validity. Legacy screen visibility/valid
builtins keep their ABI, but their full render-resolution dispatch and G-buffer copies run only
for a declared legacy consumer or the sun-mask debug view.

The frame captures supplementary section results under the same queue lock as the bounded
64-slot Vulkan metadata copy, passes that immutable capture to the matching 64-slot BLAS build,
and uploads a per-frame readiness buffer after publication. The buffer lives through Metal command
completion without being overwritten by another frame. Both output copy-back paths use the same
Vulkan/Metal timeline handshake, including destruction on resolution changes. Atlas contents are
copied each RT frame because sprite animation changes texels without changing texture identity.
A cleared current atlas invalidates alpha queries even if an earlier exported copy still exists.
Native tests execute the shipped kernels for projection/warp round-trips, closest/miss depths,
unknown-before/behind-hit, unrelated owners, diagonal boundaries, displaced neighbors, exact
mirrored/cropped alpha and front/back culling. They do not establish client appearance or live cost.

## 11. Debug views

`GBufferDebugView` has grown well past a small fixed list as new passes gained their own diagnostic
view (54 constants: off plus everything from raw G-buffer channels through the
tonemap/bloom/exposure terminal-pass branches, a 9-view env-specular series, and the two Metal ray
tracing overrides). Treat
`GBufferDebugView.java` itself as the list; its own doc comment explains which ordinals are resolve
branches versus terminal-pass branches. Selection rides the same generic per-pass-params mechanism
every fullscreen pass uses (see §6); the resolve pass alone receives the current debug-view ordinal
as one of its two generic per-pass scalars, decoded shader-side into an integer branch. There is no
dedicated debug-view uniform; it is one value of a mechanism built for something else entirely.

The legacy `METAL_RT_SUN_MASK` and `METAL_RT_SCENE_DEBUG` enum entries are retained only for
saved-name and ordinal compatibility. Neither settings nor keyboard cycling offers them, and
configuration migration resets saved selections to `OFF` before rendering. The hit/miss, distance,
normal, instance/primitive ID and ray-direction selector is removed. Its `rtDebugMode` compatibility
field is normalized to `OFF` on every load, preventing invisible diagnostic work. Current-schema
files keep these compatibility values on disk until an ordinary settings save; runtime state is
always normalized.

`RT_SHADOW` remains selectable as **RT shadow coverage**. Pack rendering supplies its applied shadow
visibility and coverage diagnostic; it does not request the legacy voxel scene trace. Native legacy
debug classes remain internal and are not user controls.

### One-shot raw pass captures

The Readback Dump key (F10) arms the pass names in `config/fornax-capture.json`, using
`{"passes":["first_pass","second_pass"]}` or `{"pass":"first_pass"}`. One request selects up to four
passes in one graph frame and never repeats. A missing, disabled or failed pass is recorded as a
failure. Configuration must exist before client startup for exact compute capture: uniform buffers
need COPY_SRC allocation usage and samplers need their native creation-state snapshot. Without that
startup configuration these allocation hooks retain normal usage and no sampler snapshot.

`FullscreenCapture` owns selection and writes `fornax-captures/<timestamp>-<uuid>/manifest.json`.
Fullscreen draws retain the texture-only callback capture: base mip, one colour output, no buffer
inputs or exact uniform/shader replay. `ComputeCapture` attaches to the actual compute descriptor
binding operation and brackets only the selected dispatch. It records the resolved GLSL and the
exact SPIR-V module passed to pipeline creation, workgroup counts, main-entry workgroup size decoded
from SPIR-V, push-constant bytes, compile values, and every descriptor's binding index and direction.
Uniform buffers use the actual descriptor offset/range, including the live globals ring slice;
registry SSBOs include uploads recorded earlier in this command buffer. Two-dimensional input images
are copied before the kernel and outputs afterward, with every bound mip described by its raw-file
offset and extent. Native sampler metadata includes all three address modes and the LOD/filter
state; a missing snapshot or unsupported extension chain makes the capture incomplete. The shadow
comparison sampler builds its own `CapturedSamplerState` from the real comparison
`VkSamplerCreateInfo`, under the same startup capture gate. If it used its parent class's snapshot
instead, that would describe the plain handle it threw away, and replay would run with depth
comparison off.

Immutable 3D pack textures retain an owned copy of the exact payload staged during upload. That
snapshot is published only after the upload fence succeeds. Capture archives these bytes and their
load-time file provenance, format, extents and SHA-256; it never reopens a possibly changed source
file or substitutes a regenerated volume. Every artifact records its byte count and SHA-256. Raw
images retain GPU row order with no conversion or vertical flip.

Unselected dispatches perform no readback allocation, copy or wait. Selected readbacks use raw VMA
TRANSFER_DST staging first-used solely on the compute queue, including distinct queue families.
The image-reuse wait includes TRANSFER for capture, and memory barriers order uploads/input copies,
kernel execution, output copies and host reads. A five-second fence wait bounds capture blocking.
Non-coherent mappings are invalidated only after confirmed completion; timeout marks failure and
retains staging on the runner's ring slot until a later confirmed fence permits destruction.
Requests share a 1 GiB byte budget, with at most 512 MiB per resource. Unsupported resources are
reported explicitly, and neither a pending nor an unavailable binding can advertise replayComplete.
Source and pure-data tests pin these contracts; only a client run verifies mixin application, GPU
readback content and driver behavior. The capture frame's timing includes readback overhead.

## 12. Known laws

- **IDs from one pack must never show up under the next.**
  The camera lookup reads the live list straight off the pack, and the coarse top-block field wipes
  its copy and refills it whole every time a pack is dropped. The numbers the game hands out that
  run are not safe to key files on, and a biome the pack left out still has known heat and rain.

- **Validity and tier leave a provider in one store.** A ray result carries its answer in R, the
  answering `RayTier` ordinal in G and validity in A. Writing A without G gives a texel a pack reads
  as answered by tier NONE; writing G without A gives one it never reads at all. Both are silent:
  the image samples cleanly either way. `MeshShadowTracer` passes its tier in the constant buffer
  and `mesh_shadow_trace` writes all four channels in one `output.write`, which is what makes the
  pairing structural rather than a convention. The buffer form has the same law with the tier in
  word 7: an untraced hit buffer reads back zero-filled, so the tier word, not the sign of the
  distance, is what separates an answer from memory nothing ever wrote.

- **A ray provider writes only what is still unanswered.** `RayRouter` walks installed providers
  from the highest tier down, so a provider that overwrites an answered texel or record destroys a
  better answer. Leaving a ray unanswered is the normal signal to the tier below and is not a
  failure: outside coverage, outside the warp domain, a degenerate direction, a section not yet
  certified. Readiness is per frame and re-evaluated; a provider that throws is latched out until
  the next `install`, because a traversal that threw has no state a later frame can trust.

- **A semaphore does not transfer exclusive buffer ownership between queue families.** Shared
  uniform buffers use concurrent allocation when graphics and compute families differ, following
  Vulkan's `VkSharingMode` contract. The same native buffer can then serve graphics and raw compute
  without ownership barriers. The constructor hook is static because `Direct` allocates before
  superclass initialization; touching `this` there is invalid. Native create-info tests and the
  actual allocator bytecode contract pin the policy and hook, not client execution or visual causes.

- **A shadow comparison input needs a comparison-enabled sampler in every runner.** The same depth
  image can have a comparison alias and a raw alias. Sending a comparison alias through the plain
  sampler cache breaks `sampler2DShadow`'s contract with no warning, even though the shader still
  compiles and the image is correct. Compute uses the shared shadow comparison sampler and refuses
  to run without one; the tests here pin the routing, not GPU execution or timing.
- **A timed-out capture fence does not permit freeing its staging buffers.** Keep them on the
  submitted ring slot until its completion is confirmed. Readback also requires allocation-time
  TRANSFER_SRC usage and non-coherent invalidation; a raw Vulkan handle alone proves neither.
- **Dispatching an interop event does not require retiring a full encoder epoch.** A partial flush
  must dispatch the existing pending commands and semaphores on the same graphics queue, then
  restart transient recording. It cannot advance completion or recycle pools/destruction resources:
  the next normal full submit's completion owns that proof. Ordinary encoder fences remain tied to
  that full epoch and require full submission before a CPU wait; partial flushes invalidate transient
  handles even though GPU resource retirement is deferred. Source contracts pin these boundaries,
  but do not establish driver scheduling, GPU lifetimes, frame time or visual quality.
- **A timestamp before a semaphore's destination stage can include the wait in its interval.**
  Raw compute dispatch timing starts at `COMPUTE_SHADER`, matching the image-reuse wait stage.
  Moving a boundary changes what is measured, not the kernel's performance. Timestamp latching and
  overlap still limit attribution; neither a near-zero interval nor a long one proves isolated
  shader cost.
- **A section's local position code must still line up after its section origin is added.**
  Matching faces in neighbouring sections must land on the same position once each section's
  origin is added in. XYZ therefore carries whole-number codes on a fixed grid, and the shader
  turns those codes back into a position before scaling. Scaling the raw UNORM XYZ value instead
  leaves gaps, even when both sides mean the same point in the world. Changing only the encoder or
  only a pack's decoder breaks this with no warning, so change both together and rebuild terrain.
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
- **A per-frame factual transform is published before its first consumer.** A transform committed
  after a graph pass reads it is silently one frame stale even though both producer and consumer
  run once per frame. `GraphRunner.finish()` therefore commits `SkyReprojection` immediately before
  graph iteration; the separate previous-camera snapshot remains after history swap for next-frame
  geometry motion.
- **Colour-target-state count must equal render-pass attachment count.** A compiled pipeline's
  declared colour-target-state count and the render pass it is bound against must agree exactly, or
  the render pass rejects the pipeline at draw time. This is currently guaranteed by construction
  (the deferred pipeline mixin and the deferred render-pass mixin are hand-kept in lockstep, both
  gated on the same render-state latch), not by any runtime assertion.
- **A mixin absent from the mixin config fails silently.** A mixin class that exists in source
  but isn't listed in the config is never applied, with no error, warning, or log line of any kind
  distinguishing that from an intentional removal.
- **An RT expansion overflow is a sizing result, never complete geometry.** Read the full count,
  grow the affected slot and re-expand before building. Copy/build batches and metadata versions
  must agree, and incomplete domain geometry must publish invalid replacement data.
- **A constant quoted in this file is a third copy that nothing reads.** The GLSL declaration and
  the Java allocation are tied to each other by a contract test; the prose here is tied to neither,
  so it drifts on any change that updates both. The wrong number is what someone budgets a pack's
  inputs against or picks a uniform tail offset from, and both land as data read from the wrong
  place rather than as an error. `ArchitectureDocConstantsContractTest` pins the geometry-input
  slot count and the `u_Globals` size and offset table; a numeric claim added here about a surface
  the code also states belongs in that test.
- **A YACL-bound `FornaxSettings` field must appear in `SettingsApplyRouter.route`'s diff, or its
  change is silently lost.** YACL applies the field into the live `FornaxConfig.get()` before the
  save callback runs, so it takes effect immediately regardless of what `route()` reports. Missed
  there, `SAVE_ONLY` never fires and the change reverts on the next config load with no error.
  Found via `sunPathRotation`.
- **`GraphRunner.rebuild` mutates its statics before its final resolve step, so a catch around it
  must call `GraphRunner.unload()` on failure, not just revert config.** A rebuild that throws
  partway through can leave `currentPack`/`registry`/`compileValues` pointing at the half-built
  broken pack even though the caller reverted `activePack` back to the old name; `unload()` rolls
  GraphRunner back to the same inactive state a missing pack leaves behind. `PackReload.reload`'s
  catch does this; `PackSwitch.apply`'s didn't.
- **Some Vulkan backends do not zero-fill new VRAM.** A freshly allocated texture can contain
  arbitrary previously-resident memory rather than zeros; every engine-managed target is cleared
  explicitly at allocation rather than relying on any backend's allocator behaviour.
- **Recording a clear does not finish it.** A storage image's layout change and clear must
  actually finish on the GPU before the first raw compute dispatch touches it. A clear that runs
  late can wipe out a valid cached kernel result even though its reuse key still matches.
  `prepare()` finishes the allocation batch before frame bindings; a compute pass that still has
  pending initialization at that point is a fatal error.
- **A pipeline builder that creates several GPU handles in sequence must free whatever succeeded
  on a mid-sequence failure.** Several pass runners retry a failed build every frame
  (`GraphRunner.ensureRunnersBuilt`), so an unguarded builder leaks again each retry, not once.
  `ParticlePipelineBuilder.build` and `ComputePipelineBuilder.buildWithDescriptorLayout` hoist
  their handles above the `try` and free them in a `catch`; a new builder here should too.
- **An "any enabled pass reads buffer X" gate must admit COMPUTE, PARTICLES and FULLSCREEN, the
  same types `GraphValidator.checkBufferBindable` legalizes for reading a buffer.** COMPUTE-only
  under-reports: a validated FULLSCREEN/PARTICLES reader still needs the buffer allocated, and a
  missed gate leaves `prepare()` skip that allocation while the pass's own runner build still
  requires it, aborting every runner in that attempt and retrying forever.
  `anyEnabledComputePassReadsVoxelGrid`, `anyEnabledPassReadsPrecipClipmap` and
  `anyEnabledComputePassReads` all follow this rule.
- **A raw data buffer with compute-only synchronization has a compute-only graph gate.**
  `precipCoarseClipmap` is not a general buffer-reader exception: validation rejects graphics
  readers and `anyEnabledComputePassReadsPrecipCoarseClipmap` therefore deliberately asks only for
  enabled compute inputs. Its readiness gate suppresses graph execution until a complete current
  window's clear and refill are queued ahead of that compute pass's dispatch.
- **Data put on `EngineBufferUploadQueue` is picked up only by a recorder running on the same
  queue as the passes that read it.** `ComputePassRunner` records for compute bindings.
  `GraphicsBufferUploads` records, in `finish()` before the pass loop, for the allowed targets
  read by enabled fullscreen or particles passes and by no enabled compute pass. A target let in
  for a pass type that has no recorder is made, never written, and reads zeros forever with no log
  line.
- **A pack-conditional exported buffer's reallocation gate must track its enablement, not just its
  size.** `MetalRtGeometry.ensureBuffers` allocates the faceTexture exported buffer only when the
  registry has `VoxelFaceTexture.TARGET` enabled; a gate keyed on diameter alone would leave that
  buffer's allocation state stale across a pack reload that flips the enablement at an unchanged
  diameter. `needsReallocation`/`ensureBuffers` both compare enablement alongside diameter for this
  reason. When the buffer is not enabled, ray-traced shadows run without per-face texture data for
  the rest of that allocation; this is logged once rather than left silent, since a ray-tracing
  feature's world data quietly depending on a pack-side declaration is otherwise indistinguishable
  from a working steady state.
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
- **Any engine feature that presents or submits GPU work on its own cadence, independent of pack
  state, must be deactivated on pack teardown, not just on its own settings toggle.** Frame
  generation kept presenting every frame through a pack reload's `closeCurrent()`, which does a
  device-wide wait-idle and frees a batch of GPU resources; without deactivating first, the next
  present submitted against that torn-down state, crashing MoltenVK with a near-null pointer
  (live-caught). Fixed via the shared `FrameGenPresenter.deactivateAll()`, called from both
  `SettingsApplyRouter`'s `FRAMEGEN_DEACTIVATE` action and `closeCurrent()`.
- **A VRAM-budget estimate must match what the allocator actually builds, not what it once
  planned to.** `BlockAtlasPageBudget.bytesPerPage` priced an overflow page's normal/material
  sidecars at a quarter resolution each, but `NormalMapAtlasReloadListener`/
  `MaterialMapAtlasReloadListener` allocate every overflow layer at full page resolution; no
  downsample exists anywhere. The stale discount underestimated real cost by ~2.7x, letting
  `maxPages` admit far more overflow pages than a machine could actually hold, defeating the
  refuse-at-load guarantee the budget exists to provide.
- **`GpuMemoryEstimator.detectedVramBytes()` (OSHI-based) is known to report 0/unavailable on
  Apple Silicon under MoltenVK.** Prefer `detectedVramBytesFromDevice(GpuDevice)` when a live
  device is available: it reads real device-local VRAM heaps straight from the Vulkan physical
  device (`vkGetPhysicalDeviceMemoryProperties`), accurate on every platform this engine targets,
  bypassing Blaze3D's lack of a VRAM query entirely. Both still return `OptionalLong`; an empty
  result means unknown, never zero VRAM.
- **A once-per-session lazy GPU resource must assign every field together, only after every
  creation step succeeds.** `NoiseTexture.ensureCreated()` assigned `texture` before creating
  `view`; a transient `createTextureView` failure left `texture` non-null while `view` stayed
  null, so the method's own `if (texture != null) return;` guard skipped every later retry and
  `getView()` silently returned `null` for the rest of the process. Fixed by holding the new
  handles in locals and assigning both fields only after the last creation step succeeds,
  freeing whatever partially succeeded on the way out.
- **Every per-frame pass dispatch in `GraphRunner.finish()` must catch its own resolve/run
  failure, or one bad pass crashes the whole render frame.** FULLSCREEN/COMPUTE/PARTICLES/
  TEMPORAL each catch inside their own runner's `run()`; COPY and MIPCHAIN resolve live every
  frame with no such guard and no build-time abort-and-retry safety net either (see
  `ensureRunnersBuilt`), so a target genuinely not yet allocated (a transient mid-reload window)
  threw straight out of `finish()`. Both dispatch cases now catch `RuntimeException` and log via
  `logPassRunFailureOnce` (same log-once-per-pass-per-session shape as `logMissingRunnerOnce`),
  skipping that pass for the frame rather than crashing it.
- **A method creating several GPU handles in sequence must hoist every handle into a local
  initialized to `null`, and free whatever succeeded in a `catch` before rethrowing.** Ten call
  sites assigned straight to a field/map only after the whole sequence succeeded, so a
  mid-sequence failure (VRAM pressure on a resize, a transient driver rejection, a malformed
  sprite) orphaned whatever had already been created, with no reference left anywhere to close
  it: `GBufferManager.ensureSize`, `TargetRegistry.reconcile`, `MipchainRunner.ensureSize`,
  `ShadowMapManager.ensureSize`, `WaterSurfaceManager.ensureSize`,
  `PackTextureRegistry.load`, `ArrayTextures.create`, `MetalFxReactiveMaskPass.ensureSize`,
  `OpaqueDepth.ensureSize`, `BlockAtlasOverflow.build`, `RtShadowResult.ensureSize`. All eleven
  hoist-and-free; a new multi-handle GPU-resource builder should follow the same shape.
- **A real `GpuDeviceLossException` must never be swallowed as a soft, continuable failure.**
  `MetalFxUpscalePass.runIfEnabled`'s catch used to treat every exception the same way (log, mark
  `failed`, fall back to TAAU that frame). On an actual lost Vulkan device — live-caught as
  MoltenVK reporting `VK_ERROR_DEVICE_LOST` while this pass's own GPU-side wait on Metal's
  shared-event semaphore never got signaled, because the Metal command buffer itself failed with
  an IOGPU "Invalid Resource" error — nothing submitted to that device afterward can succeed, so
  falling back just handed the very next unrelated `submit()` the same dead device, surfacing one
  frame later as an unattributed native crash with no Java stack trace. `GpuDeviceLossException`
  is now caught ahead of the broad fallback and rethrown, so the real cause surfaces immediately
  through Minecraft's own crash-report path instead.
- **Every VRAM budget sharing one device derives from the same real-VRAM query, never an
  independent fixed constant.** The block atlas's overflow-page budget
  (`SpriteLoaderPagedStitchMixin`, up to 1/2 of real device-local VRAM) and the two labPBR sidecar
  atlases' budget (`PbrSidecarAtlasScale.effectiveMaxAtlasBytes`) both read
  `GpuMemoryEstimator.detectedVramBytesFromDevice`, each reserving its own documented, coordinated
  share rather than sizing against a constant with no relationship to what the device actually has
  or to what the other budget already claims.
- **`GpuTexture.close()` does not free VRAM.** It enqueues the handle into a 2-slot destroy ring
  that Blaze3D drains on `VulkanCommandEncoder.submit()`, once per real rendered frame; an entry is
  destroyed only on the SECOND `submit()` after it was closed, and a wait-idle before closing does
  not advance the ring. A teardown that allocates a replacement inside the same synchronous call as
  the release reclaims nothing before that allocation.
- **A changed atlas generation waits for real render-loop submits to reclaim the previous one
  before allocating the next.** `TextureAtlasReleaseGenerationMixin` (at `upload` HEAD) selects a
  rebuild scope per location: full for a changed block atlas, overflow/grid-only for an unchanged
  one (that data is not covered by the sidecar fingerprint), sidecars-only for a changed non-block
  mirrored atlas, no-op otherwise. `AtlasGenerationSchedule` defers that scope's rebuild three
  `cycleAnimationFrames` polls (`RETIRE_POLLS`, matching `TargetRegistry.RETIRE_GENERATIONS`'s
  margin over the destroy ring's 2-submit requirement), driven by the existing per-frame
  `TextureAtlasLabPbrAnimationMixin` poll. The rebuild itself is one synchronous call; nothing runs
  off the render thread and nothing splits CPU compositing from GPU upload.
- **A retired sprite grid stays on its neutral fallback until its generation's terminal atlas work
  completes, and its Vulkan view closes before its texture.** `SpriteBoundsTexture.view`/
  `rangeViewOrNull` return a shared 1x1 zero-filled fallback while the block generation is pending
  (`AtlasGenerationSchedule.hasPending`), and publish the real grids only once both texture/view
  pairs exist. `BlockAtlasOverflow` refuses to publish a nonzero overflow page count unless the
  matching sidecar pair exists, so the shader constant and the sampled array-layer counts always
  agree.
- **A sidecar atlas's byte budget prices every layer the allocation actually creates.**
  `PbrSidecarAtlasScale.fits` multiplies by `(1 + overflowPages)`: both sidecar listeners allocate
  one full-size array layer per block-atlas overflow page, on top of the base image.
  `BlockAtlasPageBudget.maxPages` returns 0, not a floor of 1, when the budget cannot afford even
  one page; the caller's own `+1` for page 0 is what keeps a starved device usable.
- **A broad catch around GPU work checks whether the failure is fatal before it logs and
  degrades.** `GpuFatalErrors.rethrowIfFatal(failure)` recognizes `GpuDeviceLossException` and
  `VulkanMetalInterop`'s own `GpuFatalException` (its interop wait/command-buffer failures, distinct
  from an ordinary `IllegalStateException`) and rethrows before any catch's degrade path runs; call
  it first in any catch wrapping GPU work. Applied at the eight sites on the crash path
  (`FrameGenPresenter`'s four present-seam catches, `GraphRunner`'s MIPCHAIN/COPY dispatch,
  `FrameGenPass`'s three catches), not universally: some broad catches elsewhere have their own
  considered retry/latch behavior. `VulkanUtils.crashIfFailure` only wraps `VK_ERROR_DEVICE_LOST` as
  `GpuDeviceLossException`, so a Vulkan OOM still surfaces as a plain `IllegalStateException`;
  `BlockAtlasOverflow.build`'s `ArrayTextures.create` and both sidecar listeners'
  `device.createTexture`/`ArrayTextures.create` calls wrap that specific call to raise
  `GpuFatalException` on any failure instead, leaving unrelated exceptions elsewhere in the same
  method unreclassified.
- **A multi-handle GPU builder hoists every allocation to a nulled local and frees whatever
  succeeded in an outer catch.** `NormalMapAtlasReloadListener.upload`/
  `MaterialMapAtlasReloadListener.upload` create a texture, then an `ArrayTextures.Allocation`, then
  a view, with real throwing operations between each; the outer catch closes
  `texture`/`pages`/any in-flight mip that is non-null before rethrowing, matching the precedents
  this section documents elsewhere.
- **A raw compute pipeline retries once without the persistent cache when cached creation fails.**
  `ComputePipelineBuilder.createWithCacheFallback` first uses the shared cache and repeats the same
  creation against `VK_NULL_HANDLE` only after a non-success result. The uncached result is final;
  an already-uncached attempt is never duplicated.
- **One rejected compute function does not discard every otherwise-valid graph runner.**
  `GraphRunner.ensureRunnersBuilt` catches a non-fatal `ComputePassRunner.build` failure inside that
  pass's own switch arm, logs the exact pass once per pack session, and continues publishing later
  runners. The failed pass's explicitly zero-cleared outputs remain defined empty inputs; fatal GPU
  failures are rethrown before this degradation path.
- **A GPU-owned texture closes only from the render thread, regardless of where the decision to
  resize it is made.** `BlockAtlasPagedStitch.takeover` runs on the reload's background stitch
  executor and only decides the grid size, carried on `BlockAtlasPagedLayout.gridSize`;
  `BlockAtlasOverflow.rebuild` (render-thread-only) is what actually calls
  `SpriteBoundsTexture.useGridSize`.
- **A release hook that clears published state checks whether anything actually changed before it
  releases.** `TextureAtlasReleaseGenerationMixin` computes both LabPBR lanes' fingerprints itself
  before touching `LabPbrAtlasPair`, so an unchanged reload releases nothing and each listener's own
  `existing != null && fingerprint.equals(...)` skip check (14.6s/~24s on a large pack) stays
  reachable.
- **Releasing published GPU state also invalidates whatever else was compiled against that state's
  shape, not just what reads its content.** `BlockAtlasOverflow.releaseCurrent` resets
  `lastPublishedPageCount` and clears Sodium's terrain program cache in the same call that nulls
  `current`, so a reload that never reaches `rebuild()` afterward cannot leave cached terrain
  programs compiled for a page count the actual binding no longer has.
- **A `vkWaitForFences` result is checked before anything treats the waited slot as drained.**
  `ComputePassRunner.fenceWaitSucceeded` logs and returns false on any non-`VK_SUCCESS` result; a
  ring-slot recycle that fails skips `vkResetFences`/`pool.reset()` and returns without dispatching
  rather than recycle a command pool the GPU may still be executing, and teardown logs and destroys
  anyway since it has no retry option.
- **A descriptor bind re-reads its target by name every call and refuses a null lookup by name.**
  `ComputePassRunner`'s and `ParticlePassRunner`'s `STORAGE_BUFFER` branches call
  `TargetRegistry.getBuffer` fresh each frame because the descriptor type was decided once at build
  time from a then-non-null buffer; `TargetRegistry.releaseBuffer` can drop that buffer from lookup
  between builds (e.g. `reconcilePackSizedBuffers` releasing one that fell out of the plan), so both
  runners throw naming the pass and the target instead of dereferencing null.
- **A cross-queue compute-to-graphics handoff needs the correct destination stage bit for every
  reader type, not just the ones a first pass covered.** `GraphRunner.computeGraphicsWaitStages`
  assigns `VERTEX_SHADER` for a `PARTICLES` reader, `FRAGMENT_SHADER` for
  `FULLSCREEN`/`GEOMETRY`/`TEMPORAL`/`MIPCHAIN`, and `TRANSFER` for `COPY` (`CopyRunner` moves the
  target via `copyTextureToTexture`, which executes at transfer stage, not fragment). A reader type
  with no branch contributes 0, which skips both the semaphore signal and the wait entirely — not a
  conservative default, an absent one.
- **A cross-queue wait-stage lookup matches readers by base target name, not exact string.**
  `GraphRunner.computeGraphicsWaitStages` collapses a `.history` suffix via `targetBaseName` before
  comparing a reader's input against a compute pass's outputs, mirroring
  `computeStorageWriteNeedsGraphicsCompletion`'s own reverse-direction match: after the end-of-frame
  swap, next frame's current image is the physical image a reader sampled as history the prior
  frame, so a `.history` reader needs the same handoff a plain reader of the same target would.
- **Concurrent queue-family sharing does not order graphics reads before a later compute write.**
  A hazardous `ComputePassRunner` owns a timeline semaphore and waits its last published graphics
  completion value at `COMPUTE_SHADER`; `GameRendererMixin` records the next value only after scene
  history and graph/debug presentation at `renderLevel` RETURN. `CrossQueueImageReuseSequence`
  refuses a second unpublished write and restores a cancelled submit ticket, keeping skipped/failed
  submissions from advancing a value no queue will ever signal. The release method records no
  submit and performs no host wait; teardown drains the compute ring before destroying the timeline.
- **A hand-picked wait stage for a fixed set of consumers must be re-derived, not hardcoded, once a
  shared function already computes it.** `GraphRunner.runPreOpaqueLightingCompute`'s final producer
  signals the union of `computeGraphicsWaitStages` across every producer in its chain rather than a
  hardcoded `FRAGMENT_SHADER_BIT`, so a pack pointing a `COPY` or `PARTICLES` pass at a lighting
  buffer still gets the correct stage instead of a silently under-synchronized fragment-only wait.
- **A transform on absolute world coordinates runs in double, never float.** `ShadowCamera.compute`'s
  texel-snap needs the true sub-texel remainder of the player's absolute position. Float32 cannot
  represent that remainder past a few million blocks: every sub-texel offset rounds to the same
  float. Only this one transform needs it; every other use of the light view is already
  camera-relative and stays float.
- **An OR-chain of bounds checks over several buffers logs each buffer's own target name, not one
  hardcoded name for the whole chain.** `BrickGridUpload.uploadSlot`/`uploadBatchLocked` check
  occupancy, payload, faceSeal, palette and summary as separate `if`s, each calling `logOobDrop`
  with its own target constant, so an out-of-bounds payload or summary write is not misattributed
  to occupancy in the log.
- **Every async resource-reload chain that ends in `RendererReload.request()` carries an
  `.exceptionally` handler.** `PackSwitch.apply`, `PackEditSession.apply` and `PackReload.reload`
  all chain the same `Minecraft.reloadResourcePacks()`-derived future; a listener throwing partway
  through it must not silently skip the terrain resync. Missing on one of the three re-hides the
  stale-terrain incident this section already records elsewhere for the general case.
- **`CAMetalLayer.developerHUDProperties` is read once by MoltenVK's HUD compositor, at layer
  setup, and never again.** A mid-session `MetalHudControl.apply` call succeeds and its read-back
  proves the OS holds the new value, yet the HUD doesn't change (confirmed live, even with a forced
  window resize). `metalHud` is therefore restart-to-apply in both directions: only the value
  already true when `Metal.framework` initializes (`FornaxPreLaunch` -> `MetalHudEnv`) shows.
- **An array texture can only be GPU-copied through one hand-recorded seam.**
  `ArrayTextures.copyLayer` records a raw `vkCmdCopyImage` into one layer, spliced into the current
  frame's submission via `execute` (no flush, no host wait), not through
  `CommandEncoder.copyTextureToTexture`, which refuses whenever a texture has
  `getDepthOrLayers() > 1`. Barriers stay `GENERAL` to `GENERAL`: Blaze3D keeps every sampled
  texture in `GENERAL` permanently, and nothing here crosses queues, so no layout transition and no
  queue-family transfer. Must run between `GraphRunner`'s declared passes, never inside a
  `RenderPass`. Rendering directly into an array layer stays unsupported; copying an
  already-rendered ordinary target in is the only path this seam opens. `create` zero-fills every
  layer at allocation via the same layer-aware `writeToTexture` the CPU-upload path uses, since an
  array texture can't be cleared through a render-pass clear.
- **A `consolidate` pass's array texture is not VRAM-budgeted.** `GraphValidator`'s VRAM report
  walks `graph.targets()`; a consolidate pass's output is never one (§5, "Array targets"), so its
  real allocation never appears in that report. Pricing it needs either a `TargetKind` array
  variant or a separate accounting path keyed off `PassType.CONSOLIDATE`. Left until a pack
  actually needs it measured.
- **A per-day pack value must not fade by blending across `u_WorldClock.y` (the day fraction);
  it must read `u_WorldClock.z` instead.** A shader has no frame-to-frame memory, so it cannot time
  a fade in real seconds on its own: the same reason `WetnessAccumulator` exists rather than being
  pack code. `DayCrossfadeAccumulator`/`DayCrossfadeState` hold that memory engine-side and publish
  `.z`: 0 the instant the day index changes, ramping via a fixed-duration smoothstep (not an
  exponential ease, which would never actually complete) to 1 over real time, independent of clock
  rate and still advancing while the world clock is paused. A pack blends
  `mix(hash(dayIndex - 1.0), hash(dayIndex), z)`; blending across `dayFrac` instead ties the fade's
  real-world duration to the clock rate (near-instant at a high rate) and, summed with a large
  `dayIndex`, loses the fraction's own float32 precision on an aged world regardless.
- **`WetnessState.reset`/`DayCrossfadeState.reset` must fire on a dimension change or world
  rejoin, or both lanes fade into the new value instead of snapping to it.**
  `GlobalUniformsWriteMixin` already tracks `Minecraft.getInstance().level` identity frame-to-frame
  to reset `WaterSurfaceTracker` on exactly this discontinuity; both accumulators' resets are called
  from that same guard so a portal trip or rejoining a world doesn't read as 20 seconds of drying
  out or a slow mist fade from yesterday's value.
- **A mesh-triggered voxel harvest must never run inside Sodium's meshing task.** The harvest reads
  vanilla's own section storage and sorts the fixed baked block parts it collects; it never asks a
  live block-and-position path to fill in PARTIAL cells. The parts-based geometry rebuild (see
  "Exact opaque partial model geometry" below) is the one allowed way to do that fill: it uses only
  a state's baked parts, never a world or a position, so it carries none of the risk this law
  guards against. `ChunkBuilderMeshingTaskMixin` only puts
  the work in a queue. `VoxelWindow.queueMeshTriggeredHarvest` runs it on its own mesh worker, away
  from backfill and light work. Repeat events for one section share one waiting request, and an edit
  that lands while that request is being read leaves one more behind it. The worker takes the most
  recently asked-for section first, and asking again for a waiting section moves it to the front, so
  joining a world queues a short list of sections rather than thousands of tasks sitting in front of
  live edits. The worker drops its own lock before it reads the world or takes the shared GPU lock.
  Each request carries the storage and model generation numbers, and is thrown away before the
  costly read if either one moved on or the section left the window; a reset cannot let an old job
  wipe out the request that replaced it. That is a promise about queue order and clean-up, not a
  promise that block-model work on other threads is held apart. `DirectSectionReader.read`'s own doc
  states "a bootstrap read and a mesh-driven harvest must come out the same colour".
  `VoxelWindow.needsHarvest()` gates the harvest before it is queued, so the no-op default state (no
  pack active) never queues one at all.
- **Metal reads a Vulkan buffer only if its memory was allocated exportable through
  `VK_EXT_metal_objects` at allocation time.** VMA-allocated targets are not, so the Metal ray
  tracing pass keeps its own exported copies of the brick-grid buffers and copies only the slots
  `BrickGridUpload` marks dirty into them each frame, rather than reading the engine's regular
  voxel buffers directly.
- **A Metal compute or acceleration-structure encoder left open when Java throws past it aborts the
  whole process, not just the pass.** Metal's own validation calls `endEncoding` mandatory and
  aborts the process on `-[_MTLCommandEncoder dealloc]` if an encoder is released without it: that
  is a native `SIGABRT`, not a catchable Java exception, so `MetalRtShadowPass.runIfEnabled`'s
  catch-and-disable can only help if the process survives to reach it. Every encode sequence in
  `metalfx.rt` (`MetalRtAcceleration`'s expand/build/instance encoders, `MetalRtShadowPass`'s trace
  encoder) therefore calls `endEncoding` from a `finally` block covering everything between
  creating the encoder and ending it, so a bad buffer id or a nil pipeline state reaches the
  catch-and-disable path instead of taking the whole process down with it. Any new encode sequence
  added to this package must keep that shape: nothing that can throw runs outside the try, and
  `endEncoding` runs in the finally unconditionally.
- **A screen-space shadow ray's origin bias must follow the surface normal, not the light direction.** Biasing
  along the sun direction shrinks the effective offset toward zero as the sun angle grazes the
  surface, exactly the geometry where self-intersection is most likely. At a shallow sun
  angle the ray starts too close to its own triangle and flickers frame to frame as camera/TAA
  jitter nudges which side of the face the origin lands on. There is no compile error and no wrong
  colour to spot in a single frame; it only shows as noise across several. `rt_trace` biases along
  a per-pixel surface normal instead, and falls back to the sun direction only when that normal
  reads back a zero vector (no real G-buffer bound).
- **A ray-origin self-intersection bias must use the flat, axis-aligned face normal, never a
  bump-mapped shading normal.** `gNormalOut.rgb` carries terrain.fsh's LabPBR
  tangent-space-perturbed `worldNormal`, not the flat face normal it is built from; a textured face
  with real per-texel bump detail can tilt that vector far enough off vertical to shrink the bias's
  clearance below the reconstruction's own floating-point slack, letting the ray self-intersect the
  voxel it started on. The correct bias direction is the mesh's geometric normal, never a value
  sampled through a normal map. The legacy screen-space `rt_trace` decodes it from
  `gNormalOut.a`, whose SNORM16 codes carry exact axis normals or an octahedral general normal.
  The sun-space depth trace starts at the finite light-ray interval and needs no receiver bias.
- **`intersection_query` geometry built `setOpaque:true` auto-commits every candidate with no error
  and no way to inspect it, unless `intersection_params::force_opacity(forced_opacity::non_opaque)`
  overrides that per query.** The acceleration structure's own opaque flag (set once, at build time,
  for the closest-hit convenience API's fast path) silently wins over a manual any-hit-style loop
  otherwise: every candidate reports as already committed, a per-candidate alpha test never runs,
  and the ray behaves exactly as if cutout testing were never added. No compile error, no wrong
  colour in a single frame, just every leaf staying fully solid. `rt_trace` sets this override
  before every `intersection_query<triangle_data, instancing>::reset`.
- **`get_candidate_primitive_data()`/`get_committed_primitive_data()` are plain, non-template
  methods returning `const device void*`, not a templated accessor.** `query.get_candidate_
  primitive_data<uint>()` fails to compile ("does not name a template"); the real signature demands
  an explicit `(device const uint*)` cast at the call site, confirmed against this machine's
  `metal_raytracing` header through the real compiler error, not assumed from another ray-tracing
  API's shape.

### When a voxel section counts as known

A new or resized brick-summary buffer starts at `SUMMARY_PENDING`, through the optional fill word on
`TargetRegistry.ensureBufferSize`; a buffer that keeps its size keeps its data, and other buffers
start at zero. Harvest writes the occupancy and emitter bits over pending, so zero then means
harvested and empty. Direct section reads ask for `ChunkStatus.FULL` with `create=false`, so a chunk
the client has not got stays pending rather than coming back as an empty stand-in.

Storage setup or teardown drops CPU ownership of geometry.
`VoxelWindow.initializeStorage` drops the owner, data and population records before it allocates or
resizes, and detaching or swapping the registry drops them too. Allocation is keyed by registry and
diameter, so asking for the same storage again keeps it. A queued resync task carries the storage
generation it was submitted under, and both the bookkeeping and the upload turn down an old
generation under the shared upload lock. So a reload at the same camera spot cannot leave fresh
pending buffers under stale owners, and a late harvest cannot write into new storage.
A queued section that has moved outside the current window is dropped before any world read, even
when storage has not changed. A dropped entry still counts toward the pending total. The lock still
checks ownership again after each harvest, since the camera can move while a read is in flight. A
backfill result also refuses to overwrite a section that a newer mesh harvest published while that
backfill was still reading the world. Every accepted mesh event bumps a per-slot edit count before
its world read. Backfill notes that count and drops its result if an edit arrived during the read.
A CPU result keeps the count it read, and only a finished GPU upload marks that count as done. A
queued mesh event skips its repeat read and upload only when the same owner still holds the slot
and the newest edit count is already on the GPU; an upload still waiting, or one that failed, does
not count. New storage wipes the counts, and a shell clear drops the done marks, so moving away and
back to the same section cannot reuse an old one. There is one record per slot, so the window
bounds them.
Right before transfer, the batch is filtered once more under the same lock: each entry must still
be marked populated, hold the same result, and be owned by that slot in the current window. This
stops a stale write from going through after a move or a newer harvest, with or without optional
GPU metadata.
A shell clear also drops CPU validity by removing the slot from the populated set, with or without
optional metadata. Moving back to an old CPU owner still means the geometry must be harvested
again. Light ownership is tracked on its own, apart from these queued records.

`ClientChunkEvents.CHUNK_LOAD`, wired up by the client entry point, queues a loaded column's
still-missing in-window sections on the resync executor. This fills in sections that came back
empty on first read, without waiting for the camera to move or terrain to rebuild, and never
overwrites valid geometry. Repeat requests for the same column merge into one within a single
storage/model generation. That merge record clears once reading starts, so a new arrival during a
slow read can queue its own retry. Each task keeps the storage and harvest generation it started
with, checks the window again before reading the world, and drains its full pending count if a
read or transfer fails, for both arrival jobs and ordinary resync. If a read or transfer fails
partway with an unflushed batch, its entries that still match get their CPU owner and data
restored, and their populated/metadata state cleared, without touching a newer owner or a replaced
storage. A successful flush throws away its rollback records. Sections that arrive before the
first window is set are handled by the first resync; sections outside the window are handled once
a later window covers them.

The shell sorts by distance first and by camera facing second, so a light or a blocker close behind
the camera does not wait for a whole ring of far sections in front. The first 24 sections read on
the render thread and the rest read on the resync worker. Batch size follows the measured reset,
packing, submit and commit work against the four-millisecond work target. Time spent waiting for
the shared lock or the GPU fence is left out of that measure: those waits hold other drawing work,
and counting them drives a cheap batch down to eight sections and pays the same waits far more
often. The waits still happen before any buffer is reused or published, so neither ordering nor
light quality changes. The measure sizes the next batch; it does not bound frame time or how long
an upload takes.

Batch voxel uploads reuse one scratch space, command pool and fence, all owned by the registry and
held under the shared queue lock. Each batch reads the current destination handles and sizes fresh,
keeps the same per-slot packing and bounds checks, and waits for the work to finish before
returning. `SynchronousTransfer` stops the pool from being reset or destroyed after a wait fails; a
failed submit never waits on a fence nothing was signaled against. Closing the registry blocks any
new resource request and retires the workspace; a changed light-volume layout also replaces it.
A single mesh publish uses this same batch path too, with or without optional metadata.
A CPU map tracks the owner each slot's light was last successfully uploaded for. Mesh, resync and
light-only updates all check against that map: a newer update for the same owner inherits a clear
that has not gone through yet, and going back to the owner already on the GPU keeps its settled
light. A slot's entry in this map only moves forward once the fence and its required clear both
succeed; a stale, skipped, out-of-bounds or failed transfer cannot mark the debt as paid. The
required light range is checked before any writes run. Clearing this bookkeeping means a missing
owner always asks for a clear on the next accepted upload, since resetting CPU state can leave the
GPU data untouched. Only a known owner that was actually committed keeps its light settled.
The older single-slot upload and clear calls are still there on their own.

### Voxel refill profiling

`VoxelRefillTelemetry` accumulates host wall times and section counts for completed resync and
chunk-arrival harvest jobs. The render thread publishes the retained totals into the profiler's
`refill_*` rows, so an F10 dump after the queue settles still contains the work. Totals span the
process lifetime, including reloads: subtract two dumps to measure a teleport. Active jobs count
executing jobs, excluding queued jobs. Worker-local counters merge once at job completion, including
failed jobs; ordinary mesh harvests and separate light-only refresh jobs are outside this scope.

Read time includes light capture. Upload time includes transfer reset, packing and command
recording, submit, fence wait and CPU commit bookkeeping. These spans overlap: do not add them
together. Shared-queue lock waiting covers result admission and batch upload entry, before taking
the outer lock. Fence waiting is elapsed host time, including scheduling delays, not GPU busy time.
Reentrant jobs restore the enclosing telemetry scope; their elapsed spans overlap too.
Queue time runs from submission to harvest-job entry; for chunk arrivals it also includes removing
the pending request under the shared lock. Summed queue time can overlap between jobs, and maximum
job time is not the wall duration of a whole window refill.

Visited positions, attempted reads, null results, successful reads, already-valid skips, obsolete
work, retired leases and rejected results have separate counts. Null results do not necessarily mean
unloaded chunks. Outside-build-height reads are counted separately. Light cells count fully captured
lightmaps; packed sections have recorded update commands; committed sections have completed the
fence and passed CPU ownership checks. None of these counters asserts complete world coverage.
Timing adds no per-cell clocks, new world reads or GPU waits. It measures existing work inside a
refill job, not all renderer contention or the later render-thread source-pool publication.

### Optional voxel section state and source inventory

`voxelSectionState` is an optional tier-zero buffer of eight scalar uint words (32 bytes) per
wrap-around slot. Words 0..2 hold absolute section xyz as signed-int bits, word 3 the storage
generation, word 4 the geometry revision, word 5 the content revision, word 6 validity, and word 7
reserved zero. Validity zero is pending/invalid, including on allocation; one is committed. Geometry
harvest advances both revisions; a world-light refresh preserves the geometry revision and advances
content. Revisions identify accepted snapshots, not hashes or a guarantee that the world cannot
change. Generation and revision counters fail on overflow instead of reusing a shader identity.

A CPU harvest queues an immutable token. The uploader rejects a token superseded by another harvest,
a slot clear, a recenter out of range or a storage reset before writing any of its ranges. It writes
committed state in the same transfer as the occupancy, palette, world light and source inventory.
CPU `recordHarvest` happens earlier and must not be read as GPU commitment. The separately tracked
committed owner also preserves a required propagated-light clear when a newer queued harvest
supersedes the first harvest for a recycled slot. While a same-owner
update waits in a batch, the GPU record still describes the old GPU payload. Recycled slots are
explicitly zeroed in the occupancy-clear transfer, and a same-size storage reset clears the optional
metadata too. Every mesh harvest goes through the batch transfer now, so having optional metadata
on or off no longer changes whether a successful upload updates light ownership.

`voxelSourceSummary` has an eight-word header followed by an eight-word record for every tier-zero
slot: `(8 + slot * 8 + word)`. Header words 0/1 hold the current material-source atlas generation,
low word first; word 2 is ABI version 1; the rest are zero. Slot records hold:

| Words | Meaning |
|---|---|
| 0, 1 | The harvested material-source atlas generation, low/high |
| 2 | Intrinsic candidate cells |
| 3 | Cells with supported authored-source evidence |
| 4 | Supported authored candidate faces |
| 5 | Nonempty cells with unsupported or unknown authored evidence |
| 6 | Flags: bit 0 palette overflow; other bits reserved |
| 7 | Nonempty cells |

Allocation is `32 + diameter^3 * 32` bytes. Both optional buffers together use 64 bytes per slot plus
one 32-byte header. Source summary writes precede the matching committed section-state record; a
shader must check ownership, committed validity and slot/header atlas-generation equality before
using the inventory. These counts describe source candidates, not radiance, exposed area or a
complete geometric light emitter list.

`MaterialSourceIndex` summarizes original labPBR alpha before filtering: zero is off, 1..254
contains authored emission, and 255 is unprovided. Static sprite identities bind immutable summaries
to an atlas generation. Missing maps are known absent; unreadable, animated, cropped or otherwise
unsupported mappings remain unknown. Section harvesting counts these candidates in its existing
cell walk and caches model evidence per palette entry. The seven-word face-texture ABI is unchanged.
No source colour, integrated energy, exposed-face selection or transport is added here.

`SectionHarvester.Result.sourceEvidence` keeps one CPU word per palette entry when source
diagnostics are on: the raw intrinsic level, plus supported, authored-positive, missing-map, and
unknown masks for the six `Direction.get3DDataValue()` positions. `VoxelFaceTexture.packSources`
marks unsupported or cropped geometry as unknown; a valid UV word alone does not prove a face has
source support. A missing material map alone still allows intrinsic evidence, but any unknown flag
wins over it. An unknown face counts as unsupported even when the source data cannot be read at
all. The masks do not tell apart a texel that is authored off from one that was never provided.
Both cases keep the raw intrinsic reading, on purpose: a later step that checks alpha must filter
out the authored-off ones before using them as light. Material color is not kept or read as light
output here.

The eligible and unsupported face counts build up during the harvester's own cell walk.
`Result.withLightmap` keeps this evidence and both the source and model generations. A structurally empty section result uses one shared, known-empty evidence value. Old constructors, and diagnostics turned off,
share one unavailable value: no extra palette memory, cell scan, or model work happens while off.
`VoxelWindow` keeps evidence with each current snapshot and each queued replacement waiting to
apply. The committed inventory keeps only the two counts, alongside its existing source summary.

`VoxelEmitterCandidates.enumerate` turns a snapshot into a list of plain int keys on demand,
ordered by local cell index then direction. A key is `cellIndex * 6 + direction`, with cell index
`x | z << 4 | y << 8`. The result reports status, the total eligible count, how many keys it
stored, how many it could not fit, and the unsupported count. Palette overflow rejects the whole
snapshot. So does any cell whose palette entry is missing, even below the cap: falling back to
palette entry zero would not give a trustworthy result. Every non-empty face in a rejected snapshot
counts as unsupported. This listing does not run on its own during harvest, and no section keeps
candidate objects around. Its default cap of 4096 comes from the local colored-light design's
overall candidate budget. A caller can pass a different cap; this is a limit, not a shared pool or
a selection rule.

The stored palette data is four bytes per entry, so at most 384 bytes at the current 96-entry cap.
Across a reach-12 window's 15,625 slots that is at most 5.72 MiB total, or 1.91 MiB at 32 entries
per section, not counting JVM object and array headers or short-lived snapshots. A default listing
call keeps at most 16 KiB of key data, and only for the caller that asked for it. Neither the
seven-word face texture nor the eight-word source-summary GPU layout changes.

`voxelEmitterPool` is an optional engine-owned, compute-readable buffer containing a bounded
4096-face diagnostic subset. It requires `voxelSectionState` and `voxelSourceSummary` buffer inputs
on every consumer; graph validation rejects graphics readers and pack writers. Its 64-byte header
and 4096 64-byte records occupy 262,208 bytes. All fields are scalar uint words:

| Header words | Meaning |
|---|---|
| 0..3 | ABI 1, capacity, stored count, flags (bit 0 rebuilding, bit 1 deferred) |
| 4..9 | Eligible, deferred and unsupported counts, each low/high |
| 10..15 | Publication revision low/high, atlas generation low/high, storage generation, reserved |

| Record words | Meaning |
|---|---|
| 0..3 | Signed owner section xyz, storage generation |
| 4..7 | Geometry revision, atlas generation low/high, local face key |
| 8 | Raw intrinsic level in bits 0..3; missing-map flag in bit 4 |
| 9..15 | Existing seven-word face mapping, including tint and valid/cutout flags |

Admission retains references to committed snapshots and one scalar cursor per section, never
per-section candidate arrays. It takes turns through owner-coordinate order with a budget of 4096
cell-index reads per frame, including repeated directional reads. A full pool stops scanning.
Lightmap-only updates preserve admission; geometry replacement, slot recycling, atlas change and
storage reset remove the affected rows. Static overflow faces carry their page in the mapping header;
page-aware consumers sample the original layer. Animated ghosts without a full-copy page remain
unsupported static source mappings.

Only dirty pool snapshots enter `EngineBufferUploadQueue`, in aligned chunks no larger than
65,536 bytes. A queued publication contains the complete zero-padded buffer; its publication counter
means queue acceptance, not GPU completion. Existing transfer ordering and ownership checks apply,
with no new queue wait or fence. Consumers must validate owner, committed state, geometry, storage
and atlas generation before any atlas read. The buffer provides neither integrated energy nor
exposed-face selection: deferred faces remain explicitly counted, and this subset is neither nearest
nor an unbiased transport sample. The pack decides how to evaluate source colour and display it.

`voxelSourceWindow` is an optional engine-owned sparse source-cell inventory for compute
consumers. It covers committed sections throughout the loaded voxel window, with no camera-distance
admission or ordering. Consumers must bind an actual `voxelSectionState` buffer; graph validation
refuses graphics readers and pack writers. Enabling this target enables source harvesting and
atlas-generation synchronization even with the diagnostic summary and reflections disabled. No
additional live-world reads are made.

Its 418,632-byte scalar-uint ABI2 has a 16-word header, 35,937 two-word section ranges, and space
for 4096 eight-word cell records. The range count is the maximum 33³ tier-zero section window;
the cell capacity is one section's 16³ cells. Only source cells with complete eligible evidence
and an enabled palette source policy enter the rows. A full inventory preserves every existing
admitted world identity; newly eligible cells remain deferred until a source retires. This is a
complete eligible source set only when the overflow and unknown counts are zero. These counts
cover committed sections only; pending and unloaded sections remain unavailable even when both
counts are zero.

| Header words | Meaning |
|---|---|
| 0..3 | ABI 2, capacity 4096, admitted committed cell count, storage generation |
| 4..7 | Atlas generation low/high, publication revision low/high |
| 8..9 | Eligible source cells deferred by capacity, unknown source cells |
| 10..15 | Reserved zero |

The range table starts at word 16. Each current toroidal section slot owns two words: the first
cell-row index and the row count. Empty and unused slots contain `(0, 0)`. Rows start at word
71,890 and are grouped by slot, then local Y/Z/X cell order. Compaction changes row indices without
changing admitted world identities; a row index is not a persistent source identifier.

| Cell words | Meaning |
|---|---|
| 0..2 | Signed absolute block xyz |
| 3 | Global palette entry: slot × 96 + local entry |
| 4 | Eligible face mask bits 0..5; intrinsic bits 8..11; missing-map mask bits 16..21 |
| 5..7 | Geometry revision, committed validity (1), reserved zero |

An explicitly excluded entry never enters the source inventory, including unsupported mappings.
The original palette, emission evidence, occupancy and occluder geometry stay unchanged. Included
entries require complete policy mapping and the original source-evidence checks. Incomplete or
overflowed palette policy cannot certify an aliased entry as excluded. Unknown cells are counted,
not admitted; consumers can preserve known independent source contributions rather than reject
the entire estimate. Known-zero evidence also omits a cell. Non-source foliage may be certified
zero despite unsupported geometry only when intrinsic emission is zero and every actual baked
quad has known nonpositive material evidence. An unreadable material map never proves zero.

`VoxelWindow` feeds this inventory from the existing successful section-transfer callback. A new
geometry result scans its 4096 already-harvested palette indices once, then retains compact eligible
and admitted bitsets per section. Lightmap-only commits do not change membership or publication.
Queued replacement geometry suppresses that section's old source rows while reserving admission
for unchanged world identities; successful replacement reconciles the membership. Slot retirement,
failed-batch rollback, atlas retirement and storage reset invalidate the corresponding records.
No camera capture, per-frame voxel scan, additional world read, wait or fence is required.

Only changed publications allocate immutable staging. A complete publication uses seven aligned
inline updates: six of 65,536 bytes and a final 25,416 bytes in the existing compute submission.
Each consuming compute refreshes the publication under its existing queue lock before recording
updates. Queue acceptance is not GPU completion. Derived buffers must carry their own coherent
header, ranges and rows; each source must still validate its current section owner, geometry,
storage and atlas before atlas evaluation or transport. A changed source need not invalidate
unrelated sources. Capacity and unknown counts describe omitted contributions, not an instruction
to zero all known light.

This ABI supports exact full-cube source mappings from the representative fixed-seed palette model.
It does not represent partial lamp emitting surfaces or certify position-randomized render-model
variants. Existing selection boxes do not establish emitting area. The pack chooses influence,
visibility, receiver treatment and evaluation cost; the engine publishes evidence and ownership.

Pack-owned buffers written by compute and read by graphics carry the same reverse
graphics-completion timeline dependency as storage images. Only outputs create that dependency;
concurrent read-only compute/graphics inputs need none. This sits alongside the forward
compute-to-graphics handoff and protects persistent buffer reuse on the next frame.

An unchanged block reload skips sidecar builders. The successful vanilla atlas upload RETURN
therefore rebinds the retained source index before the pending overflow/grid early return. The
schedule accepts only its exact pending preparations. HEAD scheduling, failed uploads and
superseded callbacks cannot publish new sprite identities.

Optional metadata readers run on the compute queue. Upload, reset and clear commands order prior
compute shader reads before their first transfer writes; the existing final transfer-to-compute
barrier makes the new records visible to subsequent compute reads. A pack can publish a status
image for graphics through the graph's existing semaphore and image-reuse chain. These buffer
barriers alone do not authorize direct graphics-queue reads of mutable metadata.

Atlas publication is independent of graph registry lifetime. Before consumers execute,
`VoxelWindow.synchronizeSourceGeneration` compares the current atlas generation against its
synchronized one. A change retires CPU upload tokens, advances storage generation, zeroes both
optional buffers' slot records and writes the new header in one reset transfer. The radius-zero
window sentinel requests a full resync even at an unchanged camera. A harvest captured from an older
atlas cannot publish into this generation. Reset fails loudly if allocated metadata has no upload
backend; an unsuccessful transfer cannot leave an old header silently accepted. No per-section
submission, fence or readback is added.

`VoxelWindow.sourceInventoryStats` reports CPU totals of source summaries only after the existing
batch transfer completion wait succeeds. Replacing a committed slot replaces its contribution;
clearing it subtracts that contribution. Eligible and unsupported face counters follow the same
completion and invalidation rule and show up in the existing F10 source telemetry; a queued or
replaced harvest cannot move them. Committed-upload, stale-upload and cleared-slot counters
reset with storage. These CPU totals cannot establish GPU visibility, visual coverage or correct
lighting in a client.

### Voxel CPU model lifetime

`VoxelHarvestLifecycle` pins model and sprite CPU reads independently of optional diagnostic
buffers. `SectionHarvester` holds a read lease through its model resolution and last texel read;
the resync worker additionally acquires the generation captured when its world work was queued,
before calling `DirectSectionReader`. Both leases end before the GPU queue lock or upload. At the
block atlas's `clearTextureData()V` HEAD, retirement advances the lifetime generation, rejects new
reads and waits for entered readers to finish before any sprite image is closed. This is the CPU
lifetime boundary shared by atlas upload and shutdown; releasing GPU textures cannot protect it.

Only successful `ModelManager.apply(ModelManager$ReloadState)V` RETURN reopens reads. Clear or atlas
upload completion cannot reopen them because models published before retirement may still refer to
retired sprite contents. A failed publication or shutdown leaves reads paused. Retirement and
publication invalidate the window's cached owners and queued storage generation; paused frames
do not consume the radius-zero sentinel, so an unchanged camera requests a full resync after the
new models publish. Consumers must still respect the published window bounds and buffer sizes;
the CPU lifetime gate itself does not gate an arbitrary pack's GPU reads. Every result retains
its harvest generation, including light-only replacements.
Both upload paths check that lifetime even with diagnostics disabled, using volatile state rather
than acquiring a CPU lease while holding the GPU lock.

The hook descriptors and the close/upload-to-clear call relationship were checked against the
Minecraft 26.2 runtime API. Headless tests exercise an allocated native image with concurrent reader
retirement, cancellation, stale-result rejection and rescan enumeration; source contracts pin hook
registration and upload ordering. They do not prove mixin execution in a running client or actual
reload/shutdown timing. This boundary does not change palette colours, lighting or the GPU face ABI.

### Voxel model material coverage

Harvest reads whether a surface is alpha-tested off each baked quad's material layer, so it works
with no `blocks.toml` tag; a tag also counts. A block is read as a cross only when its unculled
cutout quads make both upright diagonal rectangles over the same bounds, so any other cloud of
unculled quads stays a full-cell cutout. Face colours average the unculled quads too, sorted into
the six faces by their baked normal. The layer-zero biome tint multiplies only the quads asking for
layer zero, before the average; other faces keep their atlas colours. Bootstrap reads hand in the
client world's tint view and the section corner, so they colour like a mesh-driven harvest. These
are average colours and rough shapes, not lit surfaces and not the model's triangles.

A PARTIAL cell with an alpha-tested material and at most six boxes carries the cutout bit and the
sprite rect in the same words a full cutout cell uses (a door, trapdoor, glass pane, or iron bars).
A pack alpha-tests the faces of the stored boxes, spreading the rect across each box face, not
across the whole block. A PARTIAL cell needing more than six boxes keeps the solid fallback instead,
logged once per state. The cutout bit outranks the per-face seal mask: the seal mask only says which
faces are covered and says nothing about the material, so a sealed face on a cutout entry is covered
by an alpha-tested quad, not a solid one. A pack checks the cutout bit before letting a sealed face
stop a ray.

### Exact opaque partial model geometry

`VoxelModelShape` sharpens PARTIAL cells when a state's baked model parts add up to solid boxes
lined up with the axes, on the existing 1/16-block grid. Selection shapes remain the fallback: they
can be wider than visible rails and fill the gaps between them. Refinement uses geometry and
material data, never block identities. Cube and CROSS handling remain unchanged.

The rebuild runs once per distinct state per section, not once per cell.
`SectionHarvester.buildEntry` collects a PARTIAL state's baked model parts with `collectParts` and
the fixed harvest seed, the same parts the face colour read already collects for that entry, and
hands them to `VoxelModelShape.reconstruct` with no world and no position. That is why the rebuild
is safe on the harvest thread. It never calls the live Fabric model-emission hook, which a
connected-texture mod hooks with a real world and position. A model whose geometry changes only
inside that hook is not seen this way and keeps its selection-shape fallback. The fence case above,
rails drawn inside the selection slab, is why the rebuild exists. A quad's corners round outward to
the 1/16 grid: low side down, high side up, and the face plane away from the solid. Vanilla signs
are the test case: a post and board at thirds of a 1/16, the board turned 0.0001 degrees so the
game does not cull it. A quad turned by a real angle, bent out of a rectangle, open, or with
unproven alpha keeps the selection shape.

Opposed rectangular faces propose candidate cuboids. Six rendered faces certify a closed body;
a missing face may close only when its entire rectangle lies strictly inside an already certified
body. This admits a rail end buried inside a post without inventing closure from a touching or
unrelated surface. SOLID materials are opaque by layer; CUTOUT materials need complete
original/frame/mip alpha evidence. Uncertain overlays cannot close a cuboid and may be ignored
only where a certified opaque face already covers their entire rectangle.

Proof work is bounded by the existing eight-box ABI. Only containment removal and exact unions
with identical cross-sections reduce the result. More than eight remaining boxes rejects
refinement; nothing is inflated or truncated. The existing palette box words, CUTOUT/CROSS flags,
GPU buffer sizes and shader traversal stay unchanged.

`VoxelPaletteShapes` deduplicates exact results by original state entry and packed boxes within the
existing 96-entry section palette. Original entries remain available for unsupported cells. Each
appended variant copies material/source metadata and recomputes its face-seal mask. Exhausting the
cap logs geometry pressure and keeps the original selection fallback. It never points a cell at some
other shape, and it never throws away evidence that has not changed. The harvester does not call
`VoxelPaletteShapes.refine`. The rebuild above runs once for each distinct state in a section, in
`buildEntry`, so every cell with that state gets the rebuilt boxes from the one palette entry they
share. `VoxelPaletteShapes` is the place to hook a rebuild that needs a different shape for each
cell, one that depends on where the cell is.
Resolvers and geometry keys die with the harvest lease; atlas opacity evidence clears after
reader drain. Tests cover wrapper emission, key reuse and null keys, all 16 fence connections,
cardinal model baking, receiving/gap/casting rays, unsupported geometry and palette/source
consistency. These fixtures do not prove the final shadow appearance in a running modded client.

### Optional voxel face texture mapping

A pack that declares and enables the `voxelFaceTexture` buffer gets exact atlas UVs beside the
16-word palette; left out or switched off, nothing is allocated. Only tier zero fills it, with the
same wrap-around slot and 96-entry addressing as `voxelPalette`:
`(slot * 96 + entry) * 42 + face * 7`, faces in DOWN, UP, NORTH, SOUTH, WEST, EAST order. Each face
has one header word, then six raw float words `u0,v0,du/ds,dv/ds,du/dt,dv/dt`. The header packs
biome RGB into bits 0..23 and flags into bits 24..31 (flag bit 0 usable UV mapping, bit 1
alpha-tested mapping, bit 2 rendered opaque-face coverage, bits 3..4 the 1-based static overflow
page). Page zero uses the base atlas; page 1..3 requires the matching array layer after ghost-UV
remapping. The seven-word stride and existing UVs are unchanged. Tint alpha is not stored; alpha comes
from the atlas sample instead. Local `(s,t)` is `(y,z)` on X faces,
`(x,z)` on Y faces and `(x,y)` on Z faces, so a turned or mirrored baked UV still comes out right. A
face is usable only when one opaque or alpha-tested quad covers the whole cell face with UVs
running straight across it. See-through materials, stacked faces, part cells, crosses, loose leaf
quads and higher tint layers mark it unusable, and a reader must fall back on the average face
colour.

Rendered opaque coverage is independent of UV mapping and source eligibility. A face receives
flag bit 2 when at least one actual baked quad covers its complete unit-square boundary in
perimeter order, using a solid material or an alpha-tested sprite whose original pixels and every
uploaded mip are fully opaque. All animation frames in those images are checked. Translucent
materials, partial/rotated planes, crossing corner order, missing pixels and any nonopaque texel
cannot prove coverage. A solid backing can therefore prove a layered face opaque while its stacked
UV mapping remains unavailable. Collision shape, average alpha, tint and vanilla light blocking
alone never establish this fact. Whole-sprite alpha results are cached by sprite-content identity;
block-atlas retirement clears them after CPU harvest leases drain, before native pixels close.
Source evaluation continues to require the independent usable-mapping bit and its existing source
checks, so opaque coverage does not admit a stacked face as an emitter.

The buffer holds 16,128 bytes per slot: about 11.2 MiB at diameter 9, 75.6 MiB at 17. Readers must
use this seven-word face layout and reject a buffer of the wrong size. A new buffer starts at zero.
Both uploads write it before the summary word in the same submission, so pending ownership
still guards stale data. `VoxelWindow.initializeStorage` drops the old owners and queued harvest
generations, so fresh face data goes up before any summary reads valid again.


### Optional per-cell voxel world light

A pack that enables the engine-sized `voxelLightmap` buffer gets tier-zero sky and block light,
apart from the material palette. Each slot is 4096 bytes (1024 uints), one byte per cell in
`(y << 8) | (z << 4) | x` order, four cells to a word: low four bits block light, high four sky,
Minecraft's 0..15. A shader reads word `slot * 1024 + (cell >> 2)`, shifts by `(cell & 3) * 8` and
masks the byte. This is light arriving from the world, not what a block gives off itself, which
palette word 15 holds. About 61 MiB at diameter 25.

The harvester copies the levels out of its world view. The mesh harvest runs after
`BlockRenderCache.init`, because at the head of `execute` the reused slice still holds the previous
section, too early for both the tint and the light; bootstrap reads use the client world's light
view. Both upload paths write the whole light range with the geometry and before the summary word,
in one transfer. A new buffer is zeroed and a missing world view gives darkness. Slot ownership, the
pending summary, the storage generation and the buffer bounds still say whether a cell is safe to
read. This is one value per block, not the smooth per-vertex light the mesh carries.

`ClientChunkCacheVoxelLightMixin` watches for light-only changes. The next active voxel frame queues
one refresh onto the resync worker, which takes the whole gathered list when it starts, camera still
and section off screen included. It reads fresh world light and republishes each slot still owned,
keeping its geometry; a mesh published after a light change queues another refresh. The work is
capped at the uploader's starting batch size and can queue behind geometry resync. Changing storage
clears the pending list, and the generation and owner checks throw out writes to retired slots. Only
one refresh worker is pending per generation, and a worker from an old generation cannot release the
new one's slot.

A section above or below build height has no geometry but still reads the world's light layers:
empty must not turn open sky into no sky light.

The `voxel_water_reflection` fullscreen pass and its underscore-suffixed variants get the same sun
and debug PassParams as `resolve`: real sun/moon direction, true sun height, and `u_Param2` holding
terrain render distance in blocks. This anchors their lighting and fog to the same world state.


### Graphics-owned inputs in raw compute submissions

`GraphicsInputDependency.requiredBy` looks at a compute pass's inputs. It says yes for the shadow
map names, the traced shadow result, and the G-buffer images: `builtin.depth`, `builtin.gNormal`,
`builtin.gAlbedo`, `builtin.gMaterial`, `builtin.gAo`, `builtin.gMotion` and `builtin.output`. It
says no for `builtin.waterDepth` and `builtin.waterNormal`. Pass names and packs do not matter to
it.

A compute pass with such an input does not go to the compute queue. `ComputePassRunner` records it
into the graphics stream, in the same order as the draws around it. With Plague's settings that is
`sun_shadow_seed` and `atmo_aerial`.

The reason is how MoltenVK works. A timeline signal only covers the command buffer it sits in. The
draws that made the images were sent earlier, in another command buffer. So a wait on the compute
queue does not put the read after those draws. Recording on the same queue does, because Metal
keeps one queue in order.

A graphics-stream pass takes no fence, no timestamp, no capture and no reuse ticket, and it signals
nothing. F10 capture and GPU timing do not run for it, so the profiler shows no GPU time for it.

`GraphicsInputDependency` itself is never built: the same check that would build it picks the
graphics stream first. Its old wait path (signal at `ALL_COMMANDS`, flush with
`VulkanPartialFlush`, wait at `COMPUTE_SHADER`) cannot run. It stays in the file only until it is
removed.

Two other cross-queue waits are separate and stay. `computeStorageWriteNeedsGraphicsCompletion`
makes a compute pass wait for last frame's graphics readers before it writes over a buffer.
`ComputeGraphicsWaits` makes a later graphics pass wait for a compute pass's output.

Tests pin which input names pick the graphics stream, and the compute queue path's wait list and
signal order.
