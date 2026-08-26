# Fornax: AI Assistant Rules

## Project Overview

Fornax is a deferred rendering engine (a rendering engine is the part of a Minecraft client that
turns the game world into what appears on screen; "deferred" means it first draws the scene into
several intermediate data images, then works out lighting afterward in a separate pass, rather than
lighting each object as it is drawn) for Minecraft 26.2. It is built directly on Vulkan, a low-level
graphics interface, and ships as a Fabric client mod, a plug-in loaded by the Fabric mod loader,
that runs alongside Sodium, a popular Minecraft rendering-performance mod.

Fornax is a pure loader: every pass, target and tunable value comes from a shaderpack, a
player-supplied bundle of files under `shaderpacks/`, parsed from TOML (a plain-text settings-file
format) at runtime and walked by a graph interpreter. No pipeline is hardcoded in Java. With no pack
active, every hook does nothing and terrain renders as plain vanilla Sodium.

Ships under MIT (`Copyright (c) 2026 Ryan Wilson`). Package `dev.icehunter.fornax`.

## Before you write code, read `.claude/rules/clean-room.md`

The licence landscape this engine sits in, and what each position allows:

| Neighbour | Licence | What you may do |
|---|---|---|
| **Sodium** | PolyForm Shield 1.0.0 | Name its API, hook it, mixin into it (patch its compiled code at load time without touching its source). Do **not** copy its source. PolyForm restricts competing redistribution, not interoperation. |
| **Other loaders** | typically LGPL-3.0, sometimes with AGPL dependencies | Their code may not enter this repository under any circumstance. A copyleft licence would force this repo off MIT, which is a licence conflict rather than a judgement call. Fornax has no dependency on any of them and must not acquire one. |
| **Any shaderpack** | mostly all-rights-reserved | Nothing. Not code, not constants, not option names. |

The two rules that bite most often:

1. **A context that has read another project's source may not write the implementation.** Reader
   produces a prose spec with no code, identifiers or constants; a separate writer implements from
   the spec and never opens the reference.
2. **Never name another project as a source** in code or in a commit message.

Naming an API to interoperate with it is expressly fine and is not what rule 2 is about. Mixin
target strings, `@Accessor` signatures, JVM descriptors and the OptiFine/Iris *pack format* uniform
names are all forced by the thing being hooked. Where two implementations hook the same API they
will share that text necessarily, and sharing it is not evidence of anything.

See `.claude/rules/clean-room.md` for the working protocol.

## Mandatory Workflow

**Follow these steps for EVERY code change. No exceptions.**

1. **Check the licence position first**: if the task involves reading any other renderer or pack,
   split reader and writer contexts before a single line is written (see
   `.claude/rules/clean-room.md`).
2. **Write the pinning test.** A pinning test locks today's behaviour in place, so a suite that
   changes it gets caught. This suite already has 219 such classes, pinning names, layouts, byte
   sizes and gate expressions on purpose. Do not treat a behaviour change as finished until a test
   for it fails before the change and passes after.
3. **Implement the smallest correct change**: mechanism, not policy (see Core Principles).
4. **Register what needs registering.** A new mixin left out of `fornax.mixins.json` is never
   applied, and nothing warns you: no error, no log line, nothing.
5. **Update `docs/ARCHITECTURE.md` in the same commit.** It documents current code and says so at
   the top: any change to a surface it describes updates it in that same commit.
6. **Run verification**: `./gradlew clean test`, twice (see below). It must be green before you
   call the task done.

### Use Gradle commands

```bash
./gradlew clean test    # Full suite. RUN TWICE — see Verification below.
./gradlew build         # Compile + test + assemble the shipping jar into build/libs/
./scripts/deploy.sh     # Build, then copy the jar into the local Modrinth "Vulkan Setup" profile.
                        # Filesystem only: never commits, never pushes. Safe to re-run.
```

`FORNAX_LINK_PACK=/path/to/pack ./scripts/deploy.sh` additionally symlinks a pack checkout into the
profile's `shaderpacks/` so pack edits are live without a copy step.

There is no linter or formatter configured in this repo. `.github/workflows/ci.yml` runs
`./gradlew clean test` twice on every push and PR, and `release.yml` gates a tag on `mod_version`
matching. Locally, `./gradlew clean test` is still the gate to run yourself before pushing.

**Never launch Minecraft.** Do not run `./gradlew runClient` and do not start the game through a
launcher. Live verification comes from the user's own play sessions; the resulting game logs land
in `logs/` (gitignored, meaning Git does not track that folder). If a task needs evidence from an
actual game session, report that and stop rather than launching the game yourself.

## Core Principles

### Infrastructure, not style

The engine routes geometry, declares render targets (called "attachments" in the code) and uploads
matrices to the GPU; the shaderpack picks projection, filtering, curves and colour. Anything added
to the engine must be plumbing that makes a style possible, not a style choice itself: mechanism,
not policy. Baking an aesthetic decision into the engine turns it into a look every pack is forced
to share.

**Corollary: never gate DATA behind a STYLING opt-in.** A uniform (a value the engine feeds to the
shader every frame; the checklist below calls this a "lane") that describes the world must be
filled in every frame, unconditionally. Only a
uniform that records a decision the engine itself made this frame may be turned on or off with that
decision. A uniform fed by a mixin that only runs under certain conditions goes stale on any frame
where that mixin does not run. Because zero is a plausible colour, this never produces an error; it
quietly starves whatever reads that data. That failure has occurred three times already, each time
in `LevelRendererSkyPassMixin`.

### Smallest change that is correct

- Prefer the narrowest hook that works. In increasing order of how much of the original method a
  mixin replaces: an `@Inject` (adds code at one point) over a `@Redirect` (swaps one call), a
  `@Redirect` over an `@Overwrite` (replaces the whole method). There are 7 `@Overwrite`s in the
  tree, and each one is a maintenance liability.
- Immutability by default: values that cannot be changed once created. The codebase already has 54
  Java records (a data-only type fixed at construction) and 203 `final class` declarations (a class
  that cannot be subclassed or given mutable state). New parsed data should be a record; new logic
  that holds no state of its own should be a `final class` with static methods.
- Fail loudly at load, not silently at frame 40,000. Validation throws
  `IllegalStateException`/`IllegalArgumentException` (or a named pack error) naming the offending
  file, target or option.

## Modular Rules

Detailed standards in `.claude/rules/`:

| File | Applies To | Content |
|---|---|---|
| `clean-room.md` | everything | Reader/writer split, authored vs derived constants, comment and commit rules |
| `architecture.md` | `src/main/java/**/*.java` | Package layout, records/final classes, load-time validation, logging |
| `mixins.md` | `**/mixin/**/*.java` | Registration, `fornax$` prefixing, injector selection, no-pack no-op rule |
| `gpu-contracts.md` | `.pipeline`, `.pass`, `.atlas`, `**/*.glsl` | std140, reversed-Z, attachment counts, VRAM zero-fill, shader assets |
| `pack-format.md` | `**/pack/**`, `**/*.toml` | Manifests, graph validation, compile vs runtime options, gate consistency |
| `testing.md` | `**/*Test.java` | JUnit 5 conventions, contract tests, fixture-order rule, cross-repo skips |
| `documentation.md` | `**/*.md` | ARCHITECTURE.md same-commit rule, comment provenance, commit messages |

---

## Reference Information

### Language & Stack

- **Language**: Java 25 (`sourceCompatibility`/`targetCompatibility` = 25; mixin
  `compatibilityLevel` = `JAVA_25`)
- **Build**: Gradle + `net.fabricmc.fabric-loom` 1.16.1
- **Platform**: Minecraft 26.2, Fabric Loader 0.19.2, Fabric API 0.152.1+26.2
- **Hard dependencies**: Sodium `mc26.2-0.9.1-fabric` (`>=0.9.0 <0.9.2`), YACL `3.9.5+26.2-fabric`
- **Graphics**: Vulkan via Mojang's Blaze3D backend; MetalFX interop through a pure-Java FFM
  Objective-C bridge (macOS)
- **TOML**: night-config 3.8.1 (`toml` + `core`, both JiJ'd via `include`)
- **Tests**: JUnit Jupiter 5.11.3
- **Logging**: SLF4J, with a single logger: `FornaxMod.LOGGER = LoggerFactory.getLogger("fornax")`
- MC 26.2 ships already-named client jars: there is no `mappings` block and
  `fabric.loom.disableObfuscation=true`, which is why dependencies use plain `implementation`
  rather than `modImplementation`.
- Both `test` and `runClient` pass `--enable-native-access=ALL-UNNAMED` (shaderc and the MetalFX
  FFM bridge load real native libraries; JDK 25 warns without it).

### Code Organization

```text
fornax/
├── src/main/java/dev/icehunter/fornax/
│   ├── FornaxMod.java          # Entrypoint: config load, pack discovery/boot, keybind
│   ├── atlas/                  # LabPBR normal/material atlases built beside the block atlas
│   ├── compat/                 # Sodium video-settings integration entry point
│   ├── config/                 # FornaxSettings (Gson POJO), migrations, SettingsApplyRouter
│   ├── debug/, profile/        # Debug overlays; GPU per-pass timing + rolling stats
│   ├── metalfx/, metalfx/objc/ # MetalFX temporal scaling + FFM ObjC/Metal bridge
│   ├── mixin/sodium/           # Sodium renderer, vertex format, uniform/bind-group plumbing
│   ├── mixin/vanilla/          # GameRenderer/LevelRenderer/Window/atlas/particle hooks
│   ├── mixin/vulkan/           # Raw Vulkan accessors, device-extension enablement
│   ├── mixin/yacl/             # YACL screen chrome and private-widget accessors
│   ├── pack/                   # Manifest records, TOML parsing, discovery, reload, apply paths
│   ├── pack/graph/             # The interpreter: GraphRunner, TargetRegistry, per-type runners
│   ├── pack/layout/            # DefineRewriter, u_PackOptions layout, RuntimeShaderPack
│   ├── pack/material/          # blocks.toml -> dense material IDs, generated material GLSL
│   ├── pack/option/            # Annotated-#define option grammar and cross-file merge
│   ├── pass/                   # ssaa, taa, reconstruct, shadow, compute, voxel, water, particle
│   ├── pipeline/               # GBuffer, FornaxChunkVertex, render-state latch, push constants
│   ├── screen/                 # Pack/engine settings UI, Shader Packs tab
│   ├── util/                   # VRAM estimation, renderer-reload plumbing, sun math
│   └── voxel/                  # Brick grid upload and voxel data structures
├── src/main/resources/
│   ├── fabric.mod.json         # Entrypoints, hard deps
│   ├── fornax.mixins.json      # EVERY mixin must be listed here
│   └── assets/fornax/          # Engine-owned shaders (blocks/, post/, include/), shaders_engine/
├── src/test/java/...           # 219 test classes + shared support classes, mirroring main
├── src/test/resources/packs/   # Pack fixtures: sample_pack + deliberately broken packs
├── docs/ARCHITECTURE.md        # Current-code reference; update in the same commit
├── THIRD-PARTY-NOTICES.md      # Every dependency bundled in the jar (+ licenses/)
├── ASSETS.md                   # Every binary in the repo, with source and licence
└── scripts/deploy.sh           # Local build + profile deploy
```

Gitignored working dirs you may see and should not commit: `logs/`, `run/`, `build/`, `bin/`,
`.gradle/`, `.audit/`, `.graph/`, `.superpowers/`, `docs/superpowers/`, `docs/local/`, `.worktrees/`.

### Verification

- `./gradlew clean test`, **run twice.** Plain `test` can replay an old passing result without
  re-running anything (Gradle skips a task it believes is still "up to date"), and some suites are
  order-sensitive.
- Never seed a `LinkedHashMap` fixture (a fixed piece of sample data a test sets up before it runs)
  from `Map.of`: its iteration order is hash-salt randomised, changing from run to run, so a passing
  test would be luck rather than proof. Build the fixture with sequential `put()` calls instead.
- **Unregistered mixins fail silently.** Confirm every new mixin is listed in `fornax.mixins.json`.
- **std140 vec3 padding is a live footgun.** std140 is the memory layout GPU shaders expect for a
  block of data: it fixes how much space each field takes and where the next one starts. Mojang's
  `Std140Builder.putVec3` pads a `vec3` (three numbers, such as a colour) to a full 16-byte slot on
  the Java side, while the shader compiler expects the spec-minimal 12 bytes. Never add a scalar
  right after an existing vec3: place it before the vec3, or keep the vec3 last. Get this wrong and
  the data is silently wrong, with no compile error to catch it.
- **Reversed-Z depth**: most engines clear the depth buffer to 1.0 (the far plane) and keep whichever
  fragment compares as less than what is already stored, so a nearer surface overwrites a farther
  one. This engine reverses that convention: depth here clears to 0.0 meaning "far", and comparisons
  use greater-or-equal. Any clear, discard or reconstruction must respect that ordering, not the more
  common forward-Z convention.
- **Some Vulkan backends do not zero-fill new VRAM** (the GPU's own memory) when it is allocated, so
  a fresh target can start out full of leftover data from whatever used that memory before. Every
  engine-managed target is cleared explicitly at allocation to avoid that.

`docs/ARCHITECTURE.md` §12 "Known laws" is the full list; read it before touching GPU state.

### Cross-repo

The test suite loads the real Plague pack from `../plague` when present, and skips when it is not.
While Plague is being re-authored, **these tests can go red without Fornax having changed.** Before
touching engine code, check whether a failing assertion is actually about pack content.

Dependencies run one way, from pack to engine, never the reverse. Nothing in this repo should
depend on a specific pack's option labels; where a test needs some, treat them as a fixture, not a
contract.

---

## Code Review Checklist

- [ ] No other renderer's source was open in the context that wrote this
- [ ] Every authored constant carries a provenance comment (paper, measurement, or the render it
      was tuned against)
- [ ] No comment or commit message names another project as a source
- [ ] New bundled dependency has a `THIRD-PARTY-NOTICES.md` row and a `licenses/` text; new binary
      has an `ASSETS.md` row
- [ ] Mechanism, not policy: no aesthetic decision baked into the engine
- [ ] No DATA lane gated behind a STYLING opt-in
- [ ] New mixin listed in `fornax.mixins.json`; methods `fornax$`-prefixed
- [ ] Narrowest injector that works; no new `@Overwrite` without a reason in the Javadoc
- [ ] std140 layout: no scalar added after a vec3
- [ ] Depth code respects reversed-Z
- [ ] Validation fails at load, loudly, naming the offending file/target/option
- [ ] Ordered fixtures built with sequential `put()`, never `Map.of`
- [ ] `docs/ARCHITECTURE.md` updated in this commit if a documented surface changed
- [ ] `./gradlew clean test` green, run twice
- [ ] Minecraft was never launched
