# Writing a shaderpack for Fornax

Fornax renders nothing on its own. Every visual decision belongs to a shaderpack: a folder you
write, dropped into `shaderpacks/`, that tells the engine which images to allocate, which shaders to
run over them, in what order, and which settings to show the player.

This document is the manual for writing one. It assumes you can read code but not that you have
written a renderer before.

Fornax bundles no pack. Nothing is installed for you and nothing is selected on a fresh install, so
the engine renders as plain Sodium until you put a pack in `shaderpacks/` and pick it. Everything
below is what a pack has to contain for that to work.

---

## 1. What a pack is

A pack is a directory (or a `.zip`) under `<game directory>/shaderpacks/` whose root contains a file
called `pack.toml`. Fornax finds it by looking in that folder. There is no registration step and no
manifest to add it to.

Inside, four settings files and a tree of shaders:

| File | Required | What it does |
|---|---|---|
| `pack.toml` | yes | Names the pack and declares which format version it targets |
| `graph.toml` | yes | Declares the images to render into, and the passes that fill them |
| `screens.toml` | no | Lays out the settings screen the player sees |
| `blocks.toml` | no | Assigns blocks to material categories |
| `shaders/**` | yes | The GLSL that actually draws |

All four are [TOML](https://toml.io), a plain text settings format. If you have written an `.ini`
file you already know most of it.

The smallest pack that loads is a `pack.toml`, a `graph.toml` with one pass that writes to the
screen, and the shader that pass names. Everything else is optional.

---

## 2. `pack.toml`

The manifest. Short, and mostly self-explanatory:

```toml
[pack]
name = "Sample Pack"
version = "0.1.0"
authors = ["Your Name"]
license = "MIT"
format = 1
```

`format` is the pack format version, and it is the one field worth understanding. Each Fornax build
understands exactly one version. If yours does not match, the pack refuses to load and says so.
This is deliberate: a pack written against a format that has since changed will fail loudly at load
rather than render something subtly wrong.

---

## 3. `graph.toml`

This is the heart of the format, and the file you will spend the most time in.

A frame is built by rendering into a series of off-screen images, each one reading the results of
the ones before it, until something finally writes to the screen. `graph.toml` declares both halves
of that: the **targets** (the images) and the **passes** (the steps).

### Ray-traced shadow ownership

An optional graph-level table declares a celestial RT subscriber:

```toml
[ray_traced_shadows]
enabled_if = "TRACE_ENABLED"
distance_option = "u_TraceDistance"
blocks_per_unit = 16
filter_guard_texels = 0.0
```

The names are examples; declare your own options in shader source. `enabled_if` uses the same
compile-expression rules as pass gates. `distance_option` must name a finite numeric runtime option.
Its value multiplied by the positive integer `blocks_per_unit` (default `1`) gives horizontal
camera-to-receiver distance in blocks. The effective distance is capped by the shadow camera's
configured extent. `filter_guard_texels` is a finite nonnegative bound on the pack's largest shadow
filter offset, including bilinear support (default zero for point sampling). Understating this guard
can cause individual filter taps to fall back near the RT boundary. Unknown fields fail load.

A declaration, an enabled consumer of `rtTerrainShadowDepth`, and an available selected backend are
all required before terrain copies or tracing start. Consumer compile and per-frame gates apply.
Automatic is the default engine backend policy; None disables RT, and the UI offers only supported,
implemented explicit backends. GPU vendors are not separate RT APIs.

`rtTerrainShadowDepth` is a read-only RGBA32F builtin at shadow-map resolution: R is nearest forward
light depth (1 for a miss), G is the tier that answered, A is current valid trace coverage, and B is
reserved. It is the only ray-traced builtin. The older screen-space names `rtSunVisibility`,
`rtSunValid` and `rtSunDepth` are gone; a pack listing any of them fails load naming the input. Invalid A must
select raster. Its descriptor exists with zero validity even when RT is unavailable.

Decode that texel through the engine include rather than by hand: `#moj_import <fornax:ray_answer.glsl>`
gives `fornaxRayAnswered(vec4)`, which is the A test, and `fornaxRayTier(vec4)`, which reads G. G is
the channel that names the tier that answered, as one of `FORNAX_RAY_TIER_NONE`,
`FORNAX_RAY_TIER_SOFTWARE_VOXEL`, `FORNAX_RAY_TIER_HARDWARE_VOXEL` or
`FORNAX_RAY_TIER_HARDWARE_MESH`. The uploaded-mesh tracer writes `FORNAX_RAY_TIER_HARDWARE_MESH`
on every texel it traces, including a traced miss; a skipped ray writes zero tier and zero validity.
The voxel tracer then fills only the texels still carrying zero validity, writing
`FORNAX_RAY_TIER_HARDWARE_VOXEL`, so one image can carry answers from both at once. Read A per texel:
the tiers do not cover the same ground and the boundary between them is not a circle. Read A first: an untraced or
invalidated target is all zeros, and zero is a legal value for R and for G.

The caster scene includes accepted SOLID/CUTOUT meshes throughout the relevant loaded light volume,
including blockers outside the receiving distance. Final uploaded atlas UVs preserve connected
textures; model emission and voxel harvesting are not part of this path. Only light rays reaching
the receiving region plus filter support need traversal, but each traces the full light-depth span.
A narrow receiving-distance transition belongs to the pack, evaluated at the actual world sample
position (including water, reflected surfaces and fog). The pack unions RT terrain depth with
`sunEntityShadowMapRaw` before filtering. `sunShadowMap`/`sunShadowMapRaw` retain complete raster
terrain and entities for distant receivers and fallback. Cloud-volume transmission remains separate.

The profiler values `rt_shadow_distance_blocks` and `rt_shadow_meshes` report active mesh RT coverage;
zero distance means raster fallback. The backend does not remove nearby raster casters, since those
can shadow distant receivers. Distance reduces eligible RT rays, not all BVH maintenance or raster
cost. The old voxel debug scene requires its own active pack subscriber and is not the mesh result.

### Targets

A target is an off-screen image the engine allocates for you.

```toml
[targets.ssao]
format = "r8"
scale = 1.0
history = true
```

| Key | Meaning |
|---|---|
| `format` | Pixel format. `r8`, `r32f`, `rgba16f` and similar. Controls precision and memory. |
| `scale` | Size relative to the render resolution. `1.0` is full size, `0.5` is half. |
| `basis` | What `scale` is relative to. `render` (the default) or `output`. |
| `history` | `true` keeps last frame's copy readable as `<name>.history`. |
| `filter` | `linear` to allow smooth sampling between pixels. Required on anything downsampled. |
| `enabled_if` | Only allocate this target when the expression is true. See below. |
| `storage`, `width`, `height` | For compute passes that need a fixed-size image rather than a screen-sized one. |

A target sized at `scale = 0.5` costs a quarter of the memory of a full-size one and is a common way
to make an expensive effect affordable.

**`history = true` is how a pass reads the previous frame.** Declaring it doubles the memory, because
the engine keeps two copies and swaps them each frame. Passes that smooth a noisy result over time
need it.

### Passes

A pass is one step. Passes run in the order they appear in the file, top to bottom. There is no
priority field and no dependency resolution: **declaration order is execution order.**

```toml
[[pass]]
name = "ssao_raw"
type = "fullscreen"
shader = "shaders/post/ssao.fsh"
enabled_if = "SSAO_ENABLED"
inputs = ["builtin.gNormal", "builtin.depth"]
outputs = ["ssaoRaw"]
```

| Key | Meaning |
|---|---|
| `name` | Yours, for logs and error messages. |
| `type` | Which kind of pass. See the table below. |
| `shader` | Path to the shader, relative to the pack root. |
| `program` | For `geometry` passes: a path *without* extension; the engine appends `.vsh` and `.fsh`. |
| `slot` | For `geometry` passes: which stream of world geometry to draw. |
| `inputs` | Targets this pass reads. **Order matters.** See section 4. |
| `outputs` | Targets this pass writes. |
| `target` | For `mipchain` passes: the target whose mip levels are being built. |
| `enabled_if` | Only run this pass when the expression is true. |
| `dispatch`, `local_size` | For `compute` passes: the work group counts, or the group size to derive them from the first output's extent (a texture, or a `count = "render"` buffer as a 1-D domain). |

Pass types:

| Type | What it does |
|---|---|
| `geometry` | Draws world geometry (terrain, entities, particles) through your shader |
| `fullscreen` | Runs your fragment shader once over every pixel. Most post-processing is this. |
| `mipchain` | Repeatedly halves a target to build its mip levels |
| `compute` | Runs a compute shader over a grid you specify |
| `particles` | Draws the particle stream |
| `temporal` | A fullscreen pass that the engine's temporal machinery drives |
| `copy` | Copies one target to another |
| `consolidate` | Copies several same-shaped inputs into one array texture, one layer each |

**`consolidate` exists for the sampler budget, not for effect.** A fragment shader can only read so
many distinct textures before the driver refuses to compile it (Metal's ceiling, 16). If a
`fullscreen` pass needs more inputs than that, group some into a `consolidate` pass instead:

```toml
[[pass]]
name = "gbuf_consolidate"
type = "consolidate"
inputs = ["builtin.gAlbedo", "builtin.gMaterial", "builtin.gAo"]
outputs = ["consolidatedGbuf"]
```

Every input must be the same shape: either declared `[targets.*]` textures agreeing on `format`
and `scale`/`basis` (or fixed size), or the three G-buffer builtins (`builtin.gAlbedo`,
`builtin.gMaterial`, `builtin.gAo`), never a mix. No `shader`, no `enabled_if`. The output name is
new, not a target you declare elsewhere. A later pass reads it as one `sampler2DArray`, layer `i`
for the `i`-th `inputs` entry:

```glsl
uniform sampler2DArray u_Input0; // consolidatedGbuf
vec4 albedo = texture(u_Input0, vec3(texCoord, 0.0));
vec4 material = texture(u_Input0, vec3(texCoord, 1.0));
vec4 ao = texture(u_Input0, vec3(texCoord, 2.0));
```

### `ray_query`: casting your own rays

A `ray_query` pass hands the engine a buffer of rays and gets a buffer of hits back. It has **no
shader of your own** and names no hardware: the engine picks the best traversal the machine has, so
the same declaration is answered by exact mesh ray tracing on one machine and an approximate voxel
march on another. `program`, `shader`, `target`, `slot` and `blend` are refused on this type,
because there is nothing for them to name.

```toml
[targets.rayRequests]
kind = "buffer"
stride_bytes = 32
count = 65536

[targets.rayHits]
kind = "buffer"
stride_bytes = 36
count = 65536

[[pass]]
name = "seed_bounce_rays"
type = "compute"
shader = "compute/seed_bounce.comp"
outputs = ["rayRequests"]
dispatch = [256, 1, 1]

[[pass]]
name = "trace_bounce"
type = "ray_query"
inputs = ["rayRequests"]
outputs = ["rayHits"]

[pass.ray_query]
kind = "closest_hit"   # or "visibility"
rays = 65536
min_tier = "hardware_voxel"   # optional; default accepts any tier
atlas_uv_encoding = "packed_half"   # optional; use "texel_u16" for exact level-zero atlas texels
```

Exactly one input, the request buffer, and one output, the hit buffer. Both must be declared
`kind = "buffer"` targets: requests need at least `rays * 32` bytes and hits `rays * 36` bytes; a buffer one record short is
refused at load, because nothing would report it at run time.

**One ray per pixel: `rays = "render"`.** A seed that writes a ray per screen pixel must not size
its buffers to one window: a literal `count` covers the window it was written against and leaves
every row past it untraced on a larger one (a maximised window, a 4K still), which reads as the
traced shadow stopping at a straight line across the screen. Declare the query
`rays = "render"` and both buffers `count = "render"`; the engine sizes them from the render size
every frame, re-sizing in place when the window or render scale changes, and traces whatever both
buffers hold that frame (capped at 16 Mi rays). The three declarations must agree: a fixed buffer
under a `"render"` query, or a fixed `rays` over a `"render"` buffer, is refused at load. The seed
that fills the request buffer has to grow with it too: give it `local_size = [x, 1]` (matching the
shader's `local_size_x`) and the engine dispatches one group per `x` elements of the buffer's live
count; the mandatory `dispatch` stays a `[1, 1, 1]` placeholder. A literal `dispatch` there stops
seeding at its own group count, and every request past it is a zero ray the trace answers as a
miss, so the tail of the screen comes back lit rather than raster. A
`count = "render"` buffer is zero-cleared whenever it re-sizes, like a texture, so it suits a
per-pixel record and not an accumulation field. The request buffer must be written by
an **earlier** pass in the same frame: an unwritten one holds whatever the allocation left there,
which is finite floats often enough to trace, so the failure would be a frame of plausible wrong
answers rather than an error.

**A request** is 8 floats, 32 bytes: origin xyz then `tMin`, direction xyz then `tMax`. The
direction need not be normalised; distances come back measured along the normalised direction.

**Origins are camera-relative**, the frame `u_InvProjModelView` already hands you. Each tier builds
its structure in a frame of its own, the mesh tier's rebased onto a coarse grid and the voxel tier's
onto its window's first section, and the engine moves your ray into whichever one answers. Writing
world coordinates instead traces a scene displaced by up to the grid step: every hit lands on real
geometry in the wrong place, with nothing to report it.

**A hit** is 9 words, 36 bytes:

| Word | Type | Meaning |
|---|---|---|
| 0 | float | distance along the ray. Negative means the ray met nothing |
| 1 | uint | flags: bit 0 front-facing, bits 8-11 face (15 = none), bit 12 UV known, bit 13 exact texel address |
| 2 | uint | surface word, the voxel tiers' `voxelIndex \| face << 12 \| palette << 16`; 0 for meshes |
| 3 | uint | atlas address, only when bit 12 is set: half2 UV by default, or unsigned 16-bit x/y when bit 13 is set |
| 4-6 | float | outward normal. The zero vector means the surface names no face; never normalise it |
| 7 | uint | **the tier that answered. Zero means nothing did** |
| 8 | uint | tint in the low three bytes, the block's own light level 0-15 in the top one |

`atlas_uv_encoding` defaults to `"packed_half"`: use `unpackHalf2x16` for word 3. Opting into
`"texel_u16"` returns x in the low 16 bits and y in the high 16 bits, and sets flag bit 13 on
UV-known hits. Decode those integer coordinates and use `texelFetch(atlas, coord, 0)` to read the
exact texel the cutout alpha test accepted. Both atlas extents must be 1..65536; an unsupported
extent fails the query with an error rather than truncating. The record is 36 bytes in both modes.
Misses and records without UVs do not set bit 13. Readers may branch on bit 13 to support both
encodings; an engine without this key rejects the manifest, so update the engine before a pack opts in.

**Read word 7 first.** A hit buffer nothing traced reads back all zeros, and zero is a legal
distance, so the sign of word 0 cannot tell an answer from untouched memory. `<fornax:ray_answer.glsl>`
carries the tier constants; the same ordinals appear here and in the celestial image's G channel.

`kind` decides how much of a record gets filled and how early a traversal may stop. `visibility`
answers "is anything on this segment", may accept the first hit rather than the nearest, and may
leave the surface fields zero: enough for a shadow or an occlusion term. `closest_hit` returns the
nearest hit with its normal, flags, surface word and atlas UV: what a bounce or a reflection needs.
Albedo is never returned. Picking a filter and a mip is your decision, not the engine's.

`min_tier` is a floor, not a request. A pack that would rather fall back to its own raster path than
take an approximate answer raises it, and every tier below is skipped rather than blended. Names are
`none`, `software_voxel`, `hardware_voxel` and `hardware_mesh`.

### Builtin targets

Some images are provided by the engine rather than declared by you. They are named with a
`builtin.` prefix and are always available:

| Name | Contents |
|---|---|
| `builtin.gAlbedo` | Surface colour |
| `builtin.gNormal` | Which way each surface faces |
| `builtin.gMaterial` | Roughness, metalness and related surface properties |
| `builtin.gAo` | Ambient occlusion baked into the geometry |
| `builtin.gMotion` | How far each pixel moved since last frame |
| `builtin.depth` | Distance from the camera, all geometry |
| `builtin.depth_opaque` | Distance from the camera, solid geometry only |
| `builtin.waterDepth`, `builtin.waterNormal` | The water surface, separately |
| `builtin.noise` | A noise texture, for dithering and sampling patterns |
| `builtin.lightmap`, `builtin.blockAtlas` | Vanilla's own lighting and block textures |
| `builtin.normalAtlas`, `builtin.materialAtlas` | LabPBR sidecar textures, if the resource pack ships them |
| `builtin.celestials` | Sun and moon |
| `builtin.spriteBounds` | Which sprite covers each part of the block atlas, and its rectangle |
| `builtin.spriteHeightRange` | The labPBR height range of that sprite |
| `builtin.output` | **The screen.** Something must write here or nothing appears. |

There is one more, `sceneHistory`, holding the finished previous frame. The engine writes it every
frame no matter what, so reflections can read it. **Never declare `sceneHistory` yourself.** Read it
as `sceneHistory.history` and leave the declaration alone.

`builtin.spriteBounds` and `builtin.spriteHeightRange` are grids laid over the block atlas rather
than screen images. Give an atlas coordinate, get the value for whichever sprite is there. Parallax
needs them: a block model can map a face onto part of its texture, so the quad's own UV range is not
the sprite's.

**Read them with the engine's helper, never with your own index.**

```glsl
#moj_import <fornax:block_atlas.glsl>

vec4 bounds = fornax_spriteGridCell(u_Input0, v_TexCoord);
vec4 range  = fornax_spriteGridCell(u_Input1, v_TexCoord);
```

The grid's resolution is the engine's, and it changes: a pack with many sprites gets a finer grid,
because the same grid decides how tightly the atlas can be packed. A shader that computes the cell
from its own constant reads the wrong cell as soon as the two disagree, and nothing reports it. The
rectangle simply stops containing the coordinate that looked it up, and whatever depended on it
turns off.

### `enabled_if`

Both targets and passes accept an `enabled_if` expression. When it is false, the pass does not run
and the target is not allocated at all, so a disabled effect costs no memory.

```toml
enabled_if = "SSR_QUALITY != 0 && (SSR_SURFACE_MODE == 2 || SSR_WATER_MODE > 1)"
```

The expression may reference **compile options only** (see section 5). Referencing a runtime option
here fails at load, and that restriction is not arbitrary: the graph is rebuilt when a compile option
changes, and never when a slider moves, so a slider in an `enabled_if` would be a condition that
silently stopped being checked.

---

## 4. How a shader reads its inputs

This is the part that surprises people, so it gets its own section.

**Inputs are bound by position, not by name.** The first entry in a pass's `inputs` list arrives in
the shader as `u_Input0`, the second as `u_Input1`, and so on:

```toml
inputs = ["builtin.gNormal", "builtin.depth", "ssao"]
```

```glsl
uniform sampler2D u_Input0;   // builtin.gNormal
uniform sampler2D u_Input1;   // builtin.depth
uniform sampler2D u_Input2;   // ssao
```

Two consequences worth internalizing:

**Appending to the list is safe. Reordering it is not.** Inserting an entry in the middle shifts
every sampler after it, and nothing will warn you. The shader still compiles and still runs. It
reads the wrong images, and you get a frame that looks broken in a way that does not point
at the cause. Comment each `u_InputN` declaration with the target it corresponds to, and keep those
comments accurate.

**A pass may not read a target it writes.** The one exception is `.history`, which is last frame's
copy and therefore a different image.

---

## 4b. Writing a terrain shader

A `geometry` pass runs over world geometry rather than the screen, and there are three things it
must do that a fullscreen pass never has to. All three fail at shader compile time, which takes the
whole terrain pipeline down, so they are worth knowing before you start rather than after.

### Ship a copy of the vertex decode

Fornax packs terrain vertices into a compact format. `shaders/include/chunk_vertex.glsl` unpacks it,
and **every pack carries its own copy** — the engine keeps that file to itself and a pack cannot
import it. Copy it from the example pack and do not edit it: it has to agree byte for byte with how
the engine wrote the data.

Import it with `#moj_import <fornax_runtime:chunk_vertex.glsl>`. The `fornax_runtime` namespace is
your own pack's files; `fornax` is the engine's, and only `globals.glsl` and `block_atlas.glsl` are
available there.

XYZ is a whole-number code carried in RGBA16_UNORM, not a position scaled straight from that
normalized value. Recover it with `floor(a_Position.xyz * 65535.0 + 0.5)`, divide by 2048, then
subtract 8. The encoder rounds to that grid and clamps to `[0,65535]`, so +24 clamps to
`24 - 1/2048` instead of wrapping around. This keeps matching planes lined up at 16-block section
edges. UVs still use plain 65535-based UNORM, and position W still carries packed facts. Keep the
engine encoder and every pack decoder for XYZ matching each other, and rebuild terrain whenever
that changes.

### Declare the push-constant block

Block positions arrive relative to their own 16-block section, not as world coordinates, because
absolute coordinates run out of float precision far from spawn and geometry starts to shimmer.
Rebuilding a position takes two additions, and the first comes from a push-constant block you
declare yourself:

```glsl
#ifdef VULKAN
layout(push_constant) uniform FornaxPushConstants {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
    vec3 u_SunDirection;
    vec3 u_PrevRegionOffset;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
uniform vec3 u_SunDirection;
uniform vec3 u_PrevRegionOffset;
#endif
```

**Copy it exactly, in this order.** A push-constant block is a memory layout, so a field out of
place reads whatever its neighbour wrote. Vulkan allows one such block per stage, which is why
unrelated things share it.

The second addition comes from the draw id, which encodes where a section sits in its 8x4x8 region:

```glsl
uvec3 sectionGridCoord(uint drawId) {
    return uvec3(drawId) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}
vec3 sectionWorldOffset(uint drawId) {
    return vec3(sectionGridCoord(drawId)) * 16.0;
}

vec3 worldPosition = u_RegionOffset + sectionWorldOffset(_draw_id) + _vert_position;
```

### Read when a section appeared

Terrain streams in a section at a time, and a section becomes drawable in a single frame. If you
want to introduce new geometry gradually rather than let it pop, the arrival time of each section is
available to any terrain program. Declare the buffer yourself, alongside the push-constant block:

```glsl
uniform isamplerBuffer u_SectionTimeInfo;
```

The slot for the section being drawn is its region's id times the 256 section positions a region
holds, plus the draw id:

```glsl
int sectionSlot = int((u_RegionID * 256u) + uint(_draw_id));
int sectionAppearedAt = texelFetch(u_SectionTimeInfo, sectionSlot).r;
```

**The clock is per region, not global.** The stored value is milliseconds from the moment that
section's own region was created to the moment the section's mesh was uploaded. Every region has a
different zero point, so the number means nothing on its own. `u_CurrentTime` from the push-constant
block above is the matching "now" on that same clock, which is what makes the subtraction valid:

```glsl
float elapsedMillis = float(u_CurrentTime - sectionAppearedAt);
```

Compare it against a frame counter, a world time, or any clock of your own and you get garbage that
varies by region, which reads as terrain revealing at random rates in different directions.

**Test the sign for "settled", never a literal.** A negative value means this section is not
arriving and needs no reveal treatment. Zero is a legitimate value meaning it appeared at the instant
its region was created, so `== 0` is wrong, and the specific negative filler is an implementation
detail, so `== -1` is wrong too:

```glsl
float reveal = (sectionAppearedAt < 0) ? 1.0 : clamp(elapsedMillis * yourRateHere, 0.0, 1.0);
```

Three things the engine handles. A recycled region never inherits the previous occupant's times; a
section's slot is written before that section can enter any draw batch, so no frame draws against a
stale slot; and the stored value cannot reach you wrapped. One thing it does not: a region that has
never uploaded geometry has no id assigned, so a pack that tracks region ids itself must treat an
unassigned one as no data rather than computing a slot from it.

`u_Globals` also carries `u_FadePeriodInv`, the reciprocal of the client's own chunk-fade duration,
for the engine's fallback shader. If you read it, do not assume it is finite. Deriving your own rate
from `elapsedMillis` is a look decision and belongs in your pack.

### Read the climate field and the world bounds

Two more world facts a shader cannot derive. `precipCoarseClipmap` is an engine-filled buffer any
compute pass may declare as an input: one `ivec4` per four-block column over a 512-block window
around the player. Word 0 is the precipitation class with a self-validating tag; word 1 packs the
surface temperature the game's own rain-or-snow decision used (signed 16-bit, 1/256 steps), downfall
0..255, and a byte of biome tags (hot, cold, wet, dry, ocean, jungle, badlands, mountain); word 2 is
the biome's nominal temperature. Index it as `slot * 4 + word`. Only a compute pass may read it, so
turn it into a texture there and sample that from graphics passes.

`u_Globals` also carries `u_WorldBounds`: sea level, the lowest buildable Y, one above the highest,
and the dimension (0 other, 1 overworld, 2 nether, 3 end). Filled every frame.

### Entity occluders

`entityOccluders` is a buffer the engine fills. A `fullscreen` or `particles` pass may declare it as
an input. A `compute` pass may not, because the upload can only be seen from the graphics queue.
Every word is a float bit pattern; read it back with `uintBitsToFloat`. It lists the nearby bodies a
pack's own local coloured-light shadow march may occlude against:

```glsl
uniform usamplerBuffer u_InputN;   // entityOccluders, every word a float bit pattern
float occF(int w) { return uintBitsToFloat(texelFetch(u_InputN, w).r); }

bool hitsBox(vec3 o, vec3 d, vec3 bmin, vec3 bmax, float maxT) {
    vec3 inv = 1.0 / d;
    vec3 t0 = (bmin - o) * inv, t1 = (bmax - o) * inv;
    vec3 tn = min(t0, t1), tf = max(t0, t1);
    float tEnter = max(max(tn.x, tn.y), tn.z), tExit = min(min(tf.x, tf.y), tf.z);
    return tExit >= max(tEnter, 0.0) && tEnter <= maxT;
}

bool occludedByEntity(vec3 originCamRel, vec3 dir, float len) {
    int count = int(occF(0));
    for (int i = 0; i < count; i++) {
        int b = 4 + i * 12;
        vec3 bmin = vec3(occF(b), occF(b + 1), occF(b + 2));
        vec3 bmax = vec3(occF(b + 4), occF(b + 5), occF(b + 6));
        if (hitsBox(originCamRel, dir, bmin, bmax, len)) return true;
    }
    return false;
}
```

Positions are measured from this frame's animated camera, the same point a fullscreen pass works
`worldPos` out from. The slot-0 body is the local player when its kind is 1. Test kind, not slot, to
find it. A ray origin that lands inside a box is the held-light case; how to treat it is left to the
pack.

A pass that declares `entityOccluders` as an input must name the header size, the occluder cap, and
the record size as their own `const int` declarations. They can sit in the pass's own file or in any
`fornax_runtime` include it pulls in. The short snippet above writes the numbers straight into the
code instead:

```glsl
const int ENTITY_OCCLUDER_HEADER_WORDS = 4;
const int ENTITY_OCCLUDER_MAX = 64;
const int ENTITY_OCCLUDER_RECORD_WORDS = 12;
```

Load fails, naming the shader and the declaration, if any reading pass is missing one of these or
declares a value this engine version does not use.

### Use compile options, not runtime options

**A runtime option in a geometry shader will not compile.** Runtime options become uniforms, and
uniforms reach fullscreen passes through a block that geometry shaders are not given. The error is
`'u_YourOption' : undeclared identifier`, and it takes the terrain pipeline with it.

Compile options are plain `#define`s and work in any stage. Use those in terrain shaders. A slider
that only affects post-processing can still be a runtime option in the pass that reads it.

---

## 5. Options

Options are settings the player can change. You declare them in the shader itself, on the `#define`
that uses them, with an annotation comment.

```glsl
#define SSAO_ENABLED    //[] compile "Ambient Occlusion"
#define SSR_QUALITY 1   //[0="Off" 1="Fancy" 2="Fast"] compile "Reflections"
#define u_SsaoStrength 1.0 //[0.0..2.0 step 0.05] runtime "AO Strength"
```

The shape is:

```
#define NAME [value]  //[range or values] compile|runtime "Label"
```

- **Empty brackets** `//[]` make it a toggle. Commenting the whole line out with `//` makes it a
  toggle that defaults to off.
- **`min..max step n`** makes it a slider.
- **`value="Label" value="Label"`** makes it a list the player cycles through.

### Compile versus runtime, and why it matters

This distinction runs through the whole format.

**Compile options are whole numbers.** The value is substituted into the shader text literally, so
there is nowhere for a fraction to live. Store an integer percent and divide where you use it:

```glsl
#define BUMP_STRENGTH_PCT 100 //[0 50 100 150 200] compile "Bump Strength"
#define BUMP_STRENGTH (float(BUMP_STRENGTH_PCT) / 100.0)
```

A fraction fails when the pack is switched on rather than when it loads, so the pack list will offer
it and then fall back to vanilla. Runtime options take real numbers and need none of this.

**Compile options** are baked into the shader text. Changing one rewrites every `#define` and
recompiles. That is why only compile options may appear in `enabled_if`: the graph itself is rebuilt
when they change, so passes and targets can genuinely appear and disappear.

**Runtime options** are sliders. Changing one writes a new number into a buffer the shaders already
read. Nothing recompiles, nothing reloads, and the change is visible on the next frame.

Pick compile for anything that changes which passes run, and runtime for anything a player will
drag while watching the result.

### A file can only test an option it declares

The engine rewrites the `#define` lines a shader already contains. It does **not** add missing ones.

So this, in a file that never declares `AO_ENABLED`, is permanently false:

```glsl
#ifdef AO_ENABLED       // always false here unless this file declares AO_ENABLED
```

Nothing warns you. The shader compiles, the option appears in the settings screen, the player
changes it and nothing happens. Declare the option in every file that tests it.

### Declaring the same option twice

Two shaders may declare the same option, and often need to. If they do, **the two lines must match
byte for byte**, including the range and the label. A mismatch fails at load and names the option.
This prevents one file quietly disagreeing with another about a slider's range.

---

## 6. `screens.toml`

Optional. Without it, your options still work; they have no settings screen.

```toml
sliders = ["u_SsaoStrength", "u_SsaoRadius", "u_SsrStrength"]

[main]
elements = ["<profile>"]
columns = 1

[screens.LIGHTING]
title = "Lighting"
elements = ["u_BumpStrength", "u_AOStrength", "SSAO_ENABLED", "u_SsaoStrength"]

[profiles.Potato]
values = { SSR_QUALITY = 0, SSAO_ENABLED = false }

[profiles.Ultra]
values = { SSR_QUALITY = 1, SSAO_ENABLED = true }
```

- `sliders` lists which options render as sliders rather than plain number entry.
- `[main]` is the front page. `<profile>` puts the quality-preset picker there.
- `[screens.NAME]` is a page. Its `elements` are option names, in display order.
- `[profiles.NAME]` is a quality preset: a named bundle of values applied together.

### Metas

A meta is one row in the settings screen that sets several options at once. Useful when "Reflections:
High" really means four separate values.

```toml
[metas.REFLECTIONS]
label = "Reflections"
values = ["Off", "Low", "High"]

[metas.REFLECTIONS.assign.Off]
SSR_QUALITY = 0

[metas.REFLECTIONS.assign.High]
SSR_QUALITY = 1
u_SsrTraceQuality = 48.0
```

Reference it from a page with the token `<meta:REFLECTIONS>`. Every tier under `assign` must appear
in that meta's own `values` list.

Broken meta references are fatal at load. Broken *profile* references only warn, because profiles are
allowed to name options a pack has since removed.

---

## 7. `blocks.toml`

Optional. It groups blocks into named categories so your shaders can treat them differently, and
lets you fill in material properties a resource pack did not author.

```toml
[categories.polished_metal]
blocks = ["minecraft:iron_block", "minecraft:gold_block", "#c:storage_blocks/iron"]
force_override = true
smoothness = { source = "albedo_luma", curve = 2.0, min = 0.6 }
f0 = "metal_albedo"

[categories.glass]
blocks = ["minecraft:glass", "minecraft:tinted_glass"]
smoothness = { source = "albedo_luma", curve = 1.0, min = 0.5 }
```

Entries beginning with `#` are datapack tags, so `#c:storage_blocks/iron` catches modded iron blocks
too without naming them.

`force_override = true` means "use my values even if the resource pack authored its own." Without it,
your values only fill gaps the pack left empty.

**Category order is significant.** Categories are numbered in declaration order, and that number is
what the shader receives. Reordering the file renumbers every material.

---

## 8. Rules that bite

Collected here because each of these has cost someone an afternoon.

**Declaration order is execution order.** Passes run top to bottom. Moving one changes the frame.

**Inputs are positional.** Reordering an `inputs` list silently rebinds every sampler after the
change. See section 4.

**A pass cannot read what it writes**, except through `.history`.

**Never hardcode a size the engine owns.** Atlas grids, page counts and atlas dimensions
all change with the pack. Read them from the texture, or use the helper that does.

**`history` and `mipchain` are mutually exclusive.** A target with mip levels is owned by the mipchain
machinery, which does not keep a previous-frame copy. Declaring both gets you one of them.

**Never declare `sceneHistory`.** The engine owns it. Read `sceneHistory.history`.

**A compile option is undefined inside an include.** An import is spliced where it sits, before
the program's `#define`s, so a shared file testing `#if MY_OPTION` sees no such name. The
preprocessor silently reads it as 0 and the shader compiles. Pass the value into the include's
functions as an argument from the program file.

**`u_SectionTimeInfo` runs on a per-region clock.** Subtract it from `u_CurrentTime` in the same
push-constant block and nothing else. Any global clock gives a different wrong answer per region,
which looks like terrain revealing at different rates depending on which way you face. See
section 4.

**`filter = "linear"` is required on any target you downsample.** Without it the hardware cannot
sample between pixels and the result is blocky.

**Something must write `builtin.output`.** A graph that never writes to the screen loads without
complaint and renders nothing.

**Never apply `%` to a signed int that can be negative.** Some drivers evaluate it as an unsigned
modulo (`-1 % 9` gives 3), so the textbook wrap `((a % d) + d) % d` maps every negative section
coordinate to the wrong voxel slot on those machines, with no error, while reading correctly near
the world origin and on other machines. Fold the sign first: `a >= 0 ? a % d : d - 1 - ((-1 - a) %
d)` hands `%` only non-negative operands and is exact everywhere.

**Annotation syntax is matched anywhere in a file, including inside comments.** A comment that
happens to contain `//[` followed by the annotation shape is parsed as a malformed option and fails
the load. If you need to write about the syntax in a comment, break it up.

---

## 9. When a pack fails to load

Fornax validates everything before rendering anything, so a broken pack fails at load with a message
naming the file, target or option at fault. It never half-loads.

Checks that run every time:

- Every `#moj_import` include resolves inside your own pack.
- Every target format parses.
- Every input and output names a real target or a real `builtin.`.
- The pass graph has no cycles.
- No `enabled_if` references a runtime option.
- Every pass and every target it touches agree about when they are enabled. If a pass could ever run
  while a target it reads is switched off, the graph is refused and the message names the exact
  combination of settings that would break it.
- Every meta and settings page references something that exists.

If the game starts and your pack is not in the list, check the log. The reason will be there.

---

## 10. Where to look next

- Build up from the smallest thing that loads: a `pack.toml`, a `graph.toml` with one fullscreen
  pass writing to `builtin.output`, and the shader it names. Once that renders anything at all, add
  one pass at a time.
- `docs/ARCHITECTURE.md` describes the engine internals: what the G-buffer holds, how uniforms are
  laid out, what happens each frame. Read it when this document does not answer your question.
- The settings screen's pack folder button opens `shaderpacks/` directly.

Reloading is quicker than restarting. The Fornax settings screen has a reload button, and holding
Shift while pressing it also reloads the renderer.
