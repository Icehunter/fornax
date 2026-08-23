---
paths: "src/main/java/dev/icehunter/fornax/pack/**/*.java, **/*.toml"
---

# Pack Format & Loader Standards

`docs/ARCHITECTURE.md` §4 (loading pipeline), §5 (target model), §6 (uniform contracts) and §9
(material system) describe the format in full. This file is the invariants a change must not break.

## The manifests

A pack is a directory or `.zip` under `<game dir>/shaderpacks/` whose root contains `pack.toml`.
Discovery is a plain OS/zip scan, not the Fabric resource-pack mechanism. Four typed manifests:

| File | Required | Contents |
|---|---|---|
| `pack.toml` | yes | `name`, `version`, `authors`, `license`, `format` |
| `graph.toml` | yes | `[targets.NAME]` tables and `[[pass]]` entries |
| `blocks.toml` | no | `[categories.NAME]` → dense material IDs |
| `screens.toml` | no | Settings-UI layout, `[metas.*]`, `[profiles.*]`, `[yacl] pages` |

`pack.toml`'s `format` is checked against the one version this build understands; a mismatch fails
load immediately. Bumping it is a breaking change for every pack in the wild — say so in the commit.

Fixtures live in `src/test/resources/packs/`: `sample_pack` is a realistic full deferred graph;
`bad_toml`, `cycle`, `missing_target` and `runtime_in_enabledif` are broken on purpose, to prove
each validation still fires. Add a broken fixture with every new validation.

## Parse order is semantic

All TOML tables are parsed **insertion-order-preserving**. Category declaration order becomes dense
material ID order; option declaration order becomes uniform-block layout order. Anything that
re-sorts, re-hashes or round-trips through an unordered map silently renumbers a pack's materials
and shifts its uniform layout.

## Compile options vs runtime options

The split is the core of the option grammar and is enforced, not advisory:

- **Compile options** rewrite `#define`s and recompile shader text. Only a compile option may
  appear in an `enabled_if` expression — a runtime option there fails load (`runtime_in_enabledif`
  is the fixture that proves it).
- **Runtime options** (sliders) only rewrite the live `u_PackOptions` GPU buffer. A slider change
  must never trigger a recompile, a renderer reload or a remesh.
- Two files declaring the same option name must agree byte-for-byte (type, range/enum, label) or
  load fails naming the option.

Rebuild triggers, and nothing else: a compile-option change, an engine AA/upscale method change,
a pack switch/deactivation, and a datapack tag bind (which refreshes the material table and
requests a remesh only — no shader recompile, since no shader text depends on tag membership).
A window resize never rebuilds; targets resize in place every frame in `prepare()`.

## Validation is eager and total

A broken pack must never reach a half-loaded state. Every check raises the same load-time error
type:

- Every `#moj_import` resolves against the pack's own source map.
- Every target format parses; every pass input/output resolves to a declared target or a
  `builtin.*` name; the pass/target graph is acyclic.
- **Gate consistency** — a pass must never be enabled while an `enabled_if`-gated target it reads,
  writes or mipchains is unallocated. The check enumerates the combined domain of every compile
  option both expressions reference (capped at 4096 points, beyond which only a byte-identical
  `enabled_if` is accepted) and refuses the graph naming the counterexample. Without it the
  mismatch surfaces as a runner-build failure the retry loop swallows, taking the entire post chain
  down — terrain draws into the G-buffer and nothing ever composites it.
- Meta/YACL-page references are **fatal**; profile keys referencing removed options only **warn**
  (profiles are allowed to drift).

## Targets

Each declares `format`, `scale`, optional `basis` (`render` default, or `output`), optional
`history = true` for previous-frame reads, and optional `enabled_if`. Unrecognised values fail load
like any other malformed field. `sceneHistory` is **engine-guaranteed** (written unconditionally
every frame under every AA method) and a pack must never declare it.

## Vocabulary is ours

Target names, builtin names and pass type names are Fornax's own vocabulary and are pinned by tests
(`targetNameIsOurVocabulary`). The OptiFine/Iris *pack format* names Fornax speaks
(`gbuffers_*`, `isEyeInWater`, `heldBlockLightValue`, `sunPathRotation`) are an interface, not
borrowed expression — see `.claude/rules/clean-room.md`.

## Checklist

- [ ] Insertion order preserved end to end
- [ ] New `enabled_if`-reachable option is a compile option
- [ ] Slider changes still trigger no recompile/reload/remesh
- [ ] New validation has a fixture pack broken on purpose, proving it fires
- [ ] Load failure names the offending file, target or option
- [ ] `pack.toml` `format` bump called out as breaking, if bumped
- [ ] No new engine-guaranteed target declarable by a pack
