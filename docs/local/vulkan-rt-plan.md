# Vulkan ray tracing backend (NVIDIA / Windows) — plan

Working document, not shipped documentation. Written 2026-09-24 against fornax `8edd3f4` on the
Windows / RTX 5090 machine. Everything under "Verified" was read from this tree or from `javap` on
the 26.2 client jar; everything under "To confirm" is an assumption the first phase exists to test.

## Where things stand

The RT cascade is already backend-neutral at the interface: `rt.RayProvider` (tier, readiness,
image-form `CelestialFill`, buffer-form `BufferQuery`), `rt.RayRouter` (best tier first, negative
contract: write only unanswered records), `RayQueryAbi` (request 32 B, hit 36 B, tier at word 7,
ABI v4). Packs speak only that. What is Metal-specific is every *implementation*:

| Piece | Today | Metal-coupled? |
|---|---|---|
| Device capability probe | `MetalRtSupport` (`supportsRaytracing`, Apple9) | yes |
| Tier install | `GraphRunner.rebuild`: `if (Objc.PLATFORM_SUPPORTED)` add Mesh + Voxel providers | yes |
| Caster selection | `MeshMetalProvider.snapshot*` over Sodium `RenderRegion`/`SectionRenderDataUnsafe`, `TerrainMeshSelection.validRange`, `TerrainMeshRevision` stamps | **no** — pure Java over Sodium, reusable as-is |
| Build/promote/retire | `StructureSwap` | **no** — handles only, unit-tested |
| Mesh copy | `MetalRtGeometry.ExportedBuffer`: raw `vkCreateBuffer` + exportable `VkDeviceMemory`, `vkCmdCopyBuffer` from Sodium's arena | half — the copy is Vulkan; only the export is Metal |
| Vertex decode | `mesh_shadow_decode` in `rt_mesh_shadow.metal` | yes (kernel), trivially portable |
| Celestial fill | `mesh_shadow_trace` (radial-warp inverse, cylinder test, alpha ≥ 0.1, both faces) | yes (kernel) |
| Buffer query | `rt_ray_query.metal` (fillMode, tier stamping, two atlas encodings) | yes (kernel) |
| Hit-buffer clear | `RayQueryInterop.clearHits`: `vkCmdFillBuffer` on the graphics encoder + `fornax$flushPending()` | **no** — pure Vulkan, misnamed package |
| Output image | Fornax-owned interop image, copied into `TerrainShadowResult` at publish | pattern reusable; the Metal half goes |
| Voxel tier | `VoxelMetalProvider` + `MetalRtShadowPass` (965 lines) + `rt_expand`/`rt_trace` | yes, large |

**On this machine today no provider is installed at all.** `RayTier.SOFTWARE_VOXEL` exists as an
enum value and a GLSL constant but has no Java implementation; with `Objc.PLATFORM_SUPPORTED`
false the router's list is empty, every `ray_query` record stays tier 0, `rtTerrainShadowDepth`
stays invalid, and Plague's GI/lamp-shadow passes take their own fallback. "RT turned on" in the
settings is a no-op here.

## Verified facts that shape the design

- **Blaze3D exposes a real feature-injection seam.** `VulkanBackend.createDevice(Collection<String>
  extensions, VulkanPhysicalDevice, Set<VulkanFeature> features)` — the same private static method
  `VulkanDeviceExtensionMixin` already HEAD-injects to add `VK_EXT_metal_objects`. `VulkanFeature` is
  a record `(VulkanPNextStruct struct, String name, long offset)`; `VulkanPNextStruct(int sType, int
  structSize)` has `findOrCreateStructInPNextChain(VkPhysicalDeviceFeatures2, MemoryStack)`, so a
  struct Mojang never heard of (`VkPhysicalDeviceAccelerationStructureFeaturesKHR`,
  `VkPhysicalDeviceRayQueryFeaturesKHR`) can be appended by us. Mojang's own set enables
  `synchronization2`, `dynamicRendering`, `timelineSemaphore`, `multiDraw`, `multiDrawIndirect`.
- **`bufferDeviceAddress` is not enabled** (no such string in `VulkanBackend`'s constant pool), and
  therefore Mojang's VMA allocator was created without `VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT`.
  Consequences: (a) we must enable it ourselves via `VK12_FEATURES_STRUCT` + the LWJGL offset of
  `VkPhysicalDeviceVulkan12Features.bufferDeviceAddress`; (b) every buffer an acceleration-structure
  build reads or writes must be allocated by us with raw `vkCreateBuffer`/`vkAllocateMemory` +
  `VkMemoryAllocateFlagsInfo{DEVICE_ADDRESS_BIT}` — exactly the raw-allocation path
  `MetalRtGeometry` already takes for exportable memory; (c) Sodium's VMA geometry buffers can never
  be BLAS inputs directly, so **the copy-into-our-own-buffer step stays** and the revision/diff-gate
  machinery carries over unchanged.
- Instance API version is `VK_API_VERSION_1_2`; the device check is only `>= 1.1`. Ray query needs
  SPIR-V 1.4 (`VK_KHR_spirv_1_4`, core in 1.2), so gate on device `apiVersion >= 1.2` as well as the
  extensions. The 5090 reports 1.4.
- LWJGL 3.4.1 on the classpath ships `KHRAccelerationStructure`, `KHRRayQuery`,
  `KHRDeferredHostOperations`, `KHRBufferDeviceAddress` and the feature structs. LWJGL's `VkDevice`
  wrapper loads extension entry points from the enabled-extension set at creation, which the Metal
  mixin's Javadoc already relies on.
- `ComputeShaderCompiler` sets **no shaderc target environment**, so every engine compute shader is
  SPIR-V 1.0 / Vulkan 1.0. `GL_EXT_ray_query` refuses to compile below SPIR-V 1.4. The RT kernels
  need a per-compile target (`shaderc_env_version_vulkan_1_2`); pack compute passes must keep the
  current default so nothing changes for MoltenVK.
- `ComputePipelineBuilder.buildWithDescriptorLayout(device, spirv, List<descriptorType>, pushBytes)`
  already takes arbitrary descriptor types at sequential bindings. Adding
  `VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR` is a pool-size entry plus a
  `VkWriteDescriptorSetAccelerationStructureKHR` pNext on the write; the layout side needs nothing.
- **The graphics queue is where provider-side Vulkan work already lives.** `clearHits` records
  `vkCmdFillBuffer` on the encoder's command buffer and calls `fornax$flushPending()`; the Metal
  provider's copies do the same. The compute→graphics edge for a request buffer written by a compute
  pass is already handled by `ComputeGraphicsWaits` ("the timeline that runs the other way").
  Recording the whole RT path (copy, decode, build, trace) on the graphics queue therefore needs no
  new queue-family ownership work: the block atlas and `TerrainShadowResult` are graphics-owned, and
  `VK_QUEUE_GRAPHICS_BIT` queues support `vkCmdBuildAccelerationStructuresKHR` and compute dispatch.
- `TerrainShadowResult` is `RENDER_ATTACHMENT | TEXTURE_BINDING | COPY_DST`. Keeping the Metal
  pattern — trace into a Fornax-owned RGBA32F image kept in `GENERAL`, `vkCmdCopyImage` into the
  result at publish — avoids touching Blaze3D's image-layout tracking entirely. `publish()` already
  exists for exactly that frame position.
- `TargetRegistry` buffer targets are `STORAGE | TRANSFER_DST | TRANSFER_SRC` (+ texel), so a Vulkan
  ray-query compute shader can read the request buffer and write the hit buffer **in place** — no
  copy-in / copy-out round trip, which is the part `RayQueryProviderContractTest` pins for Metal and
  the part this backend gets to skip.
- Kernel scope to port: `mesh_shadow_decode` + `mesh_shadow_trace` (166 lines Metal),
  `rt_ray_query` (249 lines) + `rt_face_normal` (52). All Fornax-authored; no clean-room split needed.
  Every authored constant (0.1 alpha cutoff, `/2048 - 8` position decode, `(0,1,2),(2,3,0)` quad ABI,
  176-byte constant block) comes with its provenance already written in the Metal source.

## Design decisions (recommended, with the alternative)

1. **Tiers implemented on Vulkan: `HARDWARE_MESH` only, phases 2–3.** `HARDWARE_VOXEL` exists on
   Apple because the mesh tier's TLAS is capped ("a few thousand instances") and the voxel window
   reaches past the receiving cylinder. NVIDIA TLAS handles millions of instances; the exact tier can
   simply hold the whole loaded light volume plus the 96-block query window. Defer voxel; revisit only
   if a measured far-shadow gap appears. `SOFTWARE_VOXEL` stays unimplemented as today.
2. **Queue: graphics, via the encoder command buffer + `fornax$flushPending()`.** Matches the two
   existing Vulkan-side RT seams. Alternative (compute queue under `SHARED_QUEUE_LOCK`) would need
   CONCURRENT sharing or ownership transfers for the atlas and the output image; not worth it.
3. **Keep the mesh copy; decode from the copy.** Rationale above (no BDA on Sodium's buffers, and the
   copy is what makes revision caching safe against arena relocation). Optimisation later: a
   `@ModifyVariable` on vertex-buffer usage to add `STORAGE_BUFFER` and decode straight from Sodium's
   buffer, skipping the copy. Not v1.
4. **Extract the backend-neutral mesh logic before writing the Vulkan provider, not after.**
   `snapshot*`, `Source`, `TerrainMeshSelection`, `StructureSwap`, revision diffing and the
   `QUERY_REACH_BLOCKS` union move to `rt.mesh.*` (`TerrainCasterSnapshot`, `CasterSource`);
   `MeshMetalProvider` is re-pointed at them in the same commit with no behaviour change. Otherwise
   two 900-line providers drift. This is the one refactor that touches the Metal path; it is pure
   code motion and the existing contract tests keep it honest.
5. **Config: keep `RayTracingMode {OFF, AUTO, FORCE}`** (the migration rule forbids reordering).
   `backendLabel()` becomes platform-aware ("Metal RT" / "Vulkan RT"); `availableBackends(boolean)`
   loses its Metal name. A neutral `rt.RtBackends` facade answers `isAvailable()` /
   `unavailableReason()` from whichever probe applies, and `GraphRunner:1960`, the settings screen and
   `RayRouter`-adjacent gates stop naming Metal.
6. **Output image, not storage-writes into Blaze3D's texture.** Rationale above.

## Phases

Each phase is independently shippable and ends with `./gradlew clean test` ×2 green, an
`ARCHITECTURE.md` update in the same commit, and the pinning tests listed. Nothing here launches
Minecraft; in-game evidence comes from your sessions and `logs/`.

### Phase 0 — device bring-up (no rendering change)

- `mixin.vulkan.VulkanDeviceRayTracingMixin` on the same `createDevice` HEAD as the Metal mixin:
  platform guard (not macOS), `apiVersion >= 1.2`, all three extensions advertised
  (`VK_KHR_acceleration_structure`, `VK_KHR_ray_query`, `VK_KHR_deferred_host_operations`), and the
  three features supported (query `vkGetPhysicalDeviceFeatures2` ourselves with our own chain on
  `physicalDevice.vkPhysicalDevice()`). Then add the names to `extensions` and three
  `VulkanFeature`s to `features`: `bufferDeviceAddress` on `VK12_FEATURES_STRUCT`, and two new
  `VulkanPNextStruct`s for accel-structure and ray-query features. Log one line either way.
- `rt.vulkan.VulkanRtSupport`: probe once (mirrors `MetalRtSupport`'s cache-forever + live-setting
  shape), records `unavailableReason()`.
- Register in `fornax.mixins.json`; §8 row.
- **To confirm here:** that `createDevice` iterates the received `Set<VulkanFeature>` and writes each
  through `findOrCreateStructInPNextChain` (read the bytecode with `javap -c`, or trust the log line
  plus a `vkGetDeviceProcAddr("vkCmdBuildAccelerationStructuresKHR") != 0` check at probe time).
- Tests: mixin source contract (names all three extensions and three feature names, platform guard
  first); `VulkanRtSupportTest` for OFF/AUTO/FORCE × supported matrix (same table as
  `MetalRtSupportTest.allowedBy`).
- Done when the log on the 5090 says `Vulkan RT: accelerationStructure=true rayQuery=true
  bufferDeviceAddress=true`, and nothing else changed.

### Phase 1 — Vulkan RT infrastructure

- `rt.vulkan.RtDeviceMemory`: raw buffer + memory with `DEVICE_ADDRESS` flag, `vkGetBufferDeviceAddress`,
  explicit zero-fill at allocation (VRAM law), size must be a 4-byte multiple (pinned).
- `rt.vulkan.AccelerationStructures`: BLAS from `(positions VK_FORMAT_R32G32B32_SFLOAT stride 12,
  triangleCount, no index buffer)` — matches what the decode kernel emits; TLAS from a
  `VkAccelerationStructureInstanceKHR` array (64 B each, pinned) with `instanceCustomIndex` = mesh
  slot and `VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR`; scratch via
  `vkGetAccelerationStructureBuildSizesKHR`; one build submission per structure change; compaction
  deferred to phase 4. Geometry flag: **not** `OPAQUE` (the alpha test needs every candidate).
- `ComputeShaderCompiler.compileToSpirv(source, name, kind, targetEnv)` overload; existing callers
  unchanged. `ComputeShaderCompilerTest` gains a case compiling a minimal `GL_EXT_ray_query` shader
  at `vulkan_1_2` (shaderc is real in tests already).
- `ComputePipelineBuilder`: pool size for `ACCELERATION_STRUCTURE_KHR`; a descriptor-write helper
  taking the AS handle (pNext struct). Storage-image and combined-sampler writes already exist.
- Tests: instance record layout (64 B, field offsets), build-size math wrapper contract, the
  4-byte-multiple rule, target-env overload does not change the default path.

### Phase 2 — `MeshVulkanProvider` (`HARDWARE_MESH`): ray-traced sun/moon shadows

- Extract `rt.mesh.TerrainCasterSnapshot` + `CasterSource` + `StructureSwap` move (decision 4).
- Provider per frame (`phaseOne`): snapshot → diff against live revisions → for each changed source
  `vkCmdCopyBuffer` arena range into our own BDA buffer → dispatch `rt_mesh_decode.comp`
  (positions + `MeshShadowPrimitive` records, 32 B each, `MESH_SURFACE_BIT | face<<12`, tint word)
  → BLAS build → TLAS build → barrier → dispatch `rt_mesh_shadow.comp` into the owned RGBA32F image.
  `StructureSwap` promotes on a fence/timeline value instead of a Metal command-buffer status.
- `publishCelestialVisibility()`: `vkCmdCopyImage` owned image → `TerrainShadowResult`, then the
  existing validity/`raiseRtDistance` bookkeeping. Same frame positions as the Metal provider.
- `GraphRunner.rebuild`: `else if (VulkanRtSupport.isAvailable()) rayProviders.add(new MeshVulkanProvider())`.
- Kernels (`shaders_engine/`): `rt_mesh_decode.comp`, `rt_mesh_shadow.comp` — `#version 450`,
  `#extension GL_EXT_ray_query : require`; `rayQueryEXT` loop with
  `rayQueryConfirmIntersectionEXT` on alpha ≥ 0.1, `gl_RayFlagsNoOpaqueEXT`, no cull flag. Constants
  as a 176-byte std140 UBO mirroring `MeshShadowConstants` (two mat4, vec4 camera, then the five
  scalars — no scalar after a vec3, none present).
- Tests: GLSL source contracts pinning the ABI numbers (stride 24, `/2048.0 - 8.0`, quad→tri order,
  face byte 20, tint bytes 12..15, emission from ushort 3, 0.1 cutoff, tier written in the same store
  as validity, `1.0` on miss); provider contract mirroring `CascadeHandoverContractTest` and
  `MetalRtSectionReadinessTest`; `RayRouterTest` unchanged.
- Done when `rtTerrainShadowDepth` goes valid on the 5090 and Plague's traced-shadow debug view
  shows white/black instead of mid-grey.

### Phase 3 — buffer-form queries: GI and lamp shadows

- `rt_ray_query.comp`: port of the Metal kernel — ABI v4 check, `fillMode` tier-word read, miss
  record (`-1.0`, face 15, white tint), both atlas encodings, `originOffset`, front-facing flag via
  `rayQueryGetIntersectionFrontFaceEXT`, primitive data via `instanceCustomIndex` + primitive index
  into the decoded records (Vulkan has no per-primitive data pointer, so records are a per-slot SSBO
  array indexed by `rayQueryGetIntersectionPrimitiveIndexEXT`; that is the one structural difference
  from Metal and it wants a test of its own).
- `rt.vulkan.VulkanRayQueries.answer(query, tier, ...)`: bind request/hit buffers **in place**, the
  TLAS, the atlas; the provider's `answer()` calls it after `ensureStructureForQuery()` (the
  camera-window build when no celestial fill ran).
- Move `clearHits` out of `metalfx.rt.RayQueryInterop` into `rt.RayQueryBuffers` (neutral; both
  backends call it). Update `RayQueryProviderContractTest` accordingly — the copy-in/copy-out pins
  stay Metal-only.
- Tests: kernel GLSL contract (word table, flag bits, `fillMode` early-out, ABI version literal 4);
  provider passes `tier().ordinal()` not a literal; primitive-index addressing test.
- Done when Plague's bounce light and traced lamp shadows render on the 5090.

### Phase 4 — verification depth and polish

- **Headless Vulkan round-trip tests.** Unlike Metal, a test JVM on this machine can create its own
  `VkInstance`/`VkDevice` with the RT extensions through LWJGL, build a one-quad BLAS and trace it —
  the Vulkan twin of `RayQueryRoundTripTest` / `MeshShadowKernelContractTest` (reversed light, a
  quad, a closed cuboid), skipping via `Assumptions` where no RT device exists (CI). This is the
  single biggest verification win the port offers; the Metal tier never had CI-runnable traces.
- Debug scene parity (`rt_debug` port) and profiler rows (`RT mesh trace GPU` via
  `createTimestampQueryPool`).
- BLAS compaction; TLAS-only refit when only the grid origin moved.
- Optional: decode straight from Sodium's buffer (decision 3 alternative).

## Risks, in the order they would bite

1. Feature injection not honoured (phase 0's confirm item). Fallback: our own `VkDevice` is not an
   option — everything shares Mojang's — so this would be a hard blocker for BDA and a reason to
   talk to the `VulkanBackend` code path differently (e.g. `@ModifyArg` on `vkCreateDevice`'s
   `pNext`). Test it first, before any other line.
2. Arena relocation between snapshot and copy — already solved for Metal by copying inside the same
   render-thread window; keep the same call order.
3. Blaze3D image layout state for the atlas when sampled from our dispatch on the graphics command
   buffer. The Metal path copied the atlas each frame; we sample Mojang's view directly. If the
   layout tracker disagrees, fall back to a per-frame copy into an owned image (what Metal does).
4. `RayTracingMode.FORCE` was documented as "Metal RT"; users with `FORCE` on Windows now get the
   Vulkan backend. Intended, but say so in the release notes.
5. Two providers drifting — mitigated by decision 4.

## Doc obligations (same commit as the code)

- `ARCHITECTURE.md`: §8 mixin row; §2338 "Uploaded-mesh celestial shadows" — replace "wherever
  `Objc.PLATFORM_SUPPORTED` holds" and "Metal RT is the implemented backend" with the backend list;
  §12 Known laws: *shaderc defaults to SPIR-V 1.0 — ray query needs 1.4*; *Mojang's VMA allocator
  has no BDA — RT buffers are raw-allocated*; *a BLAS geometry marked OPAQUE skips the alpha test
  silently*.
- `PACK-FORMAT.md` "Ray-traced shadow ownership": "Metal RT is the implemented backend" → both.
- `AGENTS.md` reference block: hard dependencies unchanged; add the RT backend note.
- `README.md` requirements: on Windows/Linux, ray tracing needs a driver exposing
  `VK_KHR_ray_query`.
