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
| `dispatch`, `local_size` | For `compute` passes: the work group counts and size. |

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
