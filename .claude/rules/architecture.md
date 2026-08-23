---
paths: "src/main/java/**/*.java"
---

# Fornax Architecture Standards

`docs/ARCHITECTURE.md` is the current-code reference — §2 package layout, §3 frame skeleton,
§12 known laws. Read the relevant section before changing a documented surface, and update it in
the same commit.

## Mechanism, not policy — CRITICAL

The engine routes geometry, declares attachments and uploads matrices. The *pack* picks projection,
filtering, curves and colour. Anything added here must be plumbing that facilitates styling, never
a style itself.

**Never gate DATA behind a STYLING opt-in.** A uniform lane describing the world is populated
unconditionally; only a lane recording a decision the engine made this frame may be gated on that
decision. A lane fed from a conditionally-invoked pass mixin goes stale on frames that pass does
not run, and zero is a plausible colour — it never errors, it quietly starves consumers. That bug
class has appeared three times in `LevelRendererSkyPassMixin` alone.

**Corollary: no hardcoded pass sequence.** `pack.graph` walks whatever the pack declared. If you
find yourself writing `if (passName.equals("ssao"))` in the interpreter layer, stop — the graph is
the program.

## Layering

Three subsystems, and the dependency runs one way:

1. **Loader** (`pack`, `pack.layout`, `pack.material`, `pack.option`) — discovery, TOML parsing
   into immutable records, option scanning, material resolution, synthetic resource pack.
2. **Graph interpreter** (`pack.graph`) — allocates/resizes targets, dispatches each pass to a
   runner keyed by pass *type*, never by pass name.
3. **Integration surface** (`mixin.sodium`, `mixin.vanilla`, `mixin.vulkan`, `mixin.yacl`) — the
   seam. See `.claude/rules/mixins.md`.

Shared per-frame state lives in `pipeline` (G-buffer, vertex format, render-state latch, push
constants, previous-frame camera). `pass.*` holds engine-owned passes that are not pack-declared
(SSAA, TAA/reconstruct, shadow, voxel debug).

## Types

- **New parsed data is a `record`.** 54 already exist (`PackModel`, `GraphSpec`, `PassSpec`,
  `TargetSpec`, `BlocksSpec`, …). Records are immutable and give equality for free, which is what
  the contract tests lean on.
- **New stateless logic is a `final class`** with static methods and a private constructor. 203
  `final class` declarations in the tree; inheritance is rare and deliberate.
- Keep parsed manifests insertion-order-preserving: category and option declaration order becomes
  dense-ID order and uniform-block layout order downstream.

## Failure discipline

- **Fail at load, loudly, naming the thing.** `IllegalStateException` (71 uses) and
  `IllegalArgumentException` (44) are the house exceptions; a named pack error type is used where
  the failure is the pack's fault. Include the offending file path, target name or option name in
  the message.
- **Never let a graph half-load.** Every validation check raises the same load-time error type so a
  broken pack cannot reach a partially-applied state.
- **Never swallow.** An unresolvable include, an unallocated target, an unregistered builtin — each
  of these fails silently downstream and surfaces as a blank frame with no log line. Validate
  eagerly instead.

## Logging

One logger, one name:

```java
public static final Logger LOGGER = LoggerFactory.getLogger("fornax");   // FornaxMod
```

Use `FornaxMod.LOGGER`. Do not add per-class loggers. Never log per-frame at INFO — the graph
interpreter runs 60+ times a second.

## Config

`FornaxSettings` is a plain Gson-serializable POJO of **engine-owned** settings only: master
toggle, active pack name, AA method, SSAA preset, debug view, sidecar resolution. Anything a pack
should decide belongs in the pack's own options (`pack.option`, `screens.toml`), not here.

- Defaults are "invisible when off": `shadersEnabled = false`, `activePack = ""`. Fornax ships no
  pack, so a fresh install must render as plain Sodium.
- Adding a field means adding a migration path — see `FornaxSettings.migrate` and
  `FornaxSettingsMigrationTest`. Old config files on disk must keep deserializing.
- `SettingsApplyRouter` is pure change-detection behind the YACL save callback. Keep it pure; it is
  unit-tested without a GPU.

## Checklist

- [ ] No aesthetic decision baked into Java
- [ ] No data lane gated on a styling flag
- [ ] Interpreter dispatches on pass *type*, never pass name
- [ ] New parsed data is a record; new stateless logic is a final class
- [ ] Validation throws at load with the offending name in the message
- [ ] Uses `FornaxMod.LOGGER`; nothing logs per-frame at INFO
- [ ] New config field has a migration and a migration test
- [ ] `docs/ARCHITECTURE.md` updated in the same commit
