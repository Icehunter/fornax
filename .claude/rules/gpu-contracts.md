---
paths: "src/main/java/dev/icehunter/fornax/pipeline/**, src/main/java/dev/icehunter/fornax/pass/**, src/main/java/dev/icehunter/fornax/atlas/**, src/main/java/dev/icehunter/fornax/voxel/**, src/main/java/dev/icehunter/fornax/metalfx/**, src/main/resources/assets/fornax/**"
---

# GPU Contract Standards

These are the laws that fail *silently* — no compile error, no exception, only a wrong frame.
`docs/ARCHITECTURE.md` §12 "Known laws" is the canonical list; §6 is the uniform contract and §7 the
vertex format.

## std140 — scalar after vec3

Mojang's `Std140Builder.putVec3` pads to a full 16-byte slot on the Java side; the shader compiler
uses the spec-minimal 12. **Never add a scalar immediately after an existing vec3.** Place the
scalar before the vec3, or keep the vec3 last in the block. Silently wrong, no compile error.

The pack-options layout builder enforces this as a hard rule rather than trusting any driver's
packing. When you change a uniform block, update the byte-size assertion in its test in the same
change — `u_Globals` is pinned at its documented size on purpose.

## Reversed-Z depth

The depth buffer clears to **0.0 ("far")** and compares **greater-or-equal**. Every clear, discard,
depth reconstruction and comparison must respect that convention, not the forward-Z one. A
forward-Z assumption produces a plausible-looking but inverted result.

## Attachment counts

A compiled pipeline's declared colour-target-state count and the render pass it binds against must
agree **exactly**, or the render pass rejects the pipeline at draw time. This is currently
guaranteed by construction, not by a runtime assertion: the deferred pipeline mixin and the
deferred render-pass mixin are hand-kept in lockstep, both gated on the same render-state latch.
Change one, change the other, in the same commit.

## VRAM is not zero-filled

Some Vulkan backends hand back a freshly allocated texture containing arbitrary previously-resident
memory. **Every engine-managed target is cleared explicitly at allocation.** Never rely on an
allocator returning zeros.

Buffer targets: `vkCmdFillBuffer` requires a size that is a multiple of 4. A fixed-size buffer
target must satisfy that by construction and pin it in a test (see
`AnalyticLightListBufferTest`).

## Includes fail silently

The underlying shader-composition mechanism splices an *error string* into the composed source for
a missing include rather than failing the load — which then surfaces only as a broken pipeline
compile deep inside a render frame. Every pack load therefore validates every `#moj_import`
eagerly and fails loudly with the offending file and include name. Keep that eagerness.

Related: a builtin name accepted by the graph validator but unresolvable by the input resolver
loads clean and then silently disables the pass that referenced it for the rest of its lifetime.
`BuiltinResolutionContractTest` pins the two lists together — extend it when you add a builtin.

## Signed `%` only sees non-negative operands

GLSL `%` becomes `OpSMod`, and at least one shipping NVIDIA driver evaluates that as an unsigned
modulo when the left operand is negative (`-1 % 9` is 3). `((a % d) + d) % d` therefore wraps
correctly near the origin and maps every negative coordinate to the wrong slot elsewhere, with no
error. Write wraps as `a >= 0 ? a % d : d - 1 - ((-1 - a) % d)`, and treat any `%` whose left
operand can go negative as a bug. `docs/ARCHITECTURE.md` §12 has the measured truth table.

## Engine-owned shader assets

Under `src/main/resources/assets/fornax/`:

- `shaders/blocks/` — terrain and shadow vertex/fragment programs
- `shaders/post/` — engine passes: reconstruct, sharpen, SSAA downsample, temporal accumulate,
  MetalFX reactive mask, framegen composites, and the debug blits
- `shaders/include/` — shared GLSL: `globals.glsl`, `chunk_vertex.glsl`, `block_atlas.glsl`
- `shaders_engine/` — compute shaders compiled by the engine itself

These are *engine* shaders and must stay mechanism: a debug blit, a downsample kernel, a
reconstruction filter. A look (a tonemap curve, a colour grade, a sky model) belongs to the pack.

GLSL constants follow `.claude/rules/clean-room.md`: physically derived constants cite their paper;
authored constants say why they are what they are.

## Which GLSL version a shader gets

Raster passes are `#version 330`, compute passes `#version 450`. 330 has no compute shaders, no
SSBOs, no `std430`, no writable storage images, no atomics, no `shared` memory and no real memory
barriers, so anything touching a buffer or talking between invocations has to be 450. Descriptor
sets, explicit bindings, push constants, specialization constants and subpass inputs are Vulkan
GLSL only, in neither version. The one exception is the engine's own ray kernels under
`shaders_engine/rt_*.comp`: `GL_EXT_ray_query` and `GL_EXT_buffer_reference` are 4.60 extensions,
so those are `#version 460` and compile through `ComputeShaderCompiler.SpirvTarget.VULKAN_1_2`;
nothing a pack writes reaches that path.

A higher version is a different language, not a superset: nothing rewrites new syntax into old, and
450 buys no speed. Do not raise a raster pass to reach one function. Ask for the function instead:
`#extension GL_ARB_shading_language_packing : require` turns on `unpackHalf2x16` and nothing else,
which is how `ssr_trace_water.fsh` reads the half-packed voxel reflection buffer.

A shared include stays 330-safe unless only compute reads it. A 450-only feature in a shared include
breaks every raster file that reads it, and the error names that file, not the include.

## Frames in flight

Per-pass GPU timing (`profile.PassTimer`) is ring-buffered across frames in flight; anything that
reads a GPU result the frame it was written is wrong. The profiler grades against an 11.1 ms /
90 FPS budget.

## Checklist

- [ ] No `%` whose left operand can be negative; wraps use the sign-folded form

- [ ] Raster pass is `#version 330`, compute is `450`; no version raised just to reach one function
- [ ] No scalar added after a vec3 in any std140 block
- [ ] Uniform block byte-size assertion updated alongside the layout
- [ ] Depth code written for reversed-Z
- [ ] Pipeline colour-target count and render-pass attachment count changed together
- [ ] Every new target cleared explicitly at allocation
- [ ] Buffer target sizes are 4-byte multiples, pinned in a test
- [ ] New builtin added to both the validator list and the resolver, with the contract test updated
- [ ] New engine shader is mechanism, not a look
