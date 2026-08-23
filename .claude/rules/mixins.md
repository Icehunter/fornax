---
paths: "src/main/java/dev/icehunter/fornax/mixin/**/*.java"
---

# Mixin Standards

The mixin packages are the seam between Fornax and Sodium/vanilla/Vulkan/YACL. `docs/ARCHITECTURE.md`
§8 is the full mixin inventory — a new mixin gets a row there in the same commit.

## Registration — the silent killer

**A mixin class absent from `src/main/resources/fornax.mixins.json` is never applied**, with no
error, warning, or log line distinguishing that from an intentional removal. It does nothing,
forever.

Every new mixin goes in the `client` array, package-relative (`sodium.FooMixin`,
`vanilla.BarMixin`, `vulkan.BazAccessor`, `yacl.QuxMixin`). Config facts:

- `"required": true`, `"compatibilityLevel": "JAVA_25"`, `"injectors": { "defaultRequire": 1 }` —
  an injector that matches nothing is a hard failure at load, which is the behaviour you want.
- The package root is `dev.icehunter.fornax.mixin`; the four subpackages are `sodium`, `vanilla`,
  `vulkan`, `yacl`.

## Naming

- Class name states the target *and* the job: `SodiumWorldRendererOrchestrationMixin`,
  `TextureAtlasLabPbrAnimationMixin`, `LevelRendererSkyPassMixin`. Two mixins may target the same
  class — the suffix is what tells them apart, so make it specific.
- Suffix by kind: `…Mixin` for injecting, `…Accessor` for `@Accessor`, `…Invoker` for `@Invoker`.
- **Every injected method and `@Unique` member is `fornax$`-prefixed**: `fornax$maybeOwnSky`,
  `fornax$materialId`. 301 occurrences in the tree; the only unprefixed file is a pure `@Accessor`
  interface, which has no bodies to collide.

## Injector selection

Prefer the narrowest hook that works. Current counts, in the order you should reach for them:

| Injector | Count | When |
|---|---|---|
| `@Inject` | 53 | Observing or cancelling at a well-defined point |
| `@Accessor` / `@Invoker` | 21 / 4 | Reaching a private field or method |
| `@Unique` | 26 | Engine state that must ride on a vanilla object |
| `@WrapOperation` | 20 | Replacing one call, keeping the original callable |
| `@ModifyArg` | 9 | Changing one argument |
| `@Redirect` | 8 | Replacing a call with no need for the original |
| `@Overwrite` | 7 | Last resort — document *why* in the Javadoc |

`@Overwrite` silently diverges when the target changes; do not add one without a Javadoc paragraph
saying what was tried first.

## The no-pack rule

**With no pack active, or shaders disabled, every hook must be a no-op.** Terrain then renders as
plain, undeferred vanilla Sodium. Gate on the render-state latch, not on an ad-hoc boolean, and
make the gate the first thing in the method.

Corollary from `.claude/rules/architecture.md`: gate the *decision*, never the *data*. A frame
state committed from a conditionally-invoked call site goes stale the moment that call site does
not run — `LevelRendererSkyPassMixin`'s Javadoc records exactly this (the Nether never calls
`addSkyPass`, so a water flag committed there froze across a portal). Commit world-describing state
from a call site that runs every frame.

## Documentation

Every mixin carries a class Javadoc stating: what it targets, what it changes, why the injection
point is the one chosen, and what happens when the pack is inactive. The existing mixins document
live-caught bugs in that Javadoc — keep that habit; it is the record of why the seam looks the way
it does.

## Testing

Mixins cannot be instantiated in a unit test without a client. The tree tests them as **contract
tests instead** — `DefaultChunkRendererFaceCullingMixinContractTest`,
`BuiltinResolutionContractTest` read the source file and assert on it, because the logic is a
`switch` over string literals with no runtime enumeration. Follow that pattern rather than skipping
coverage.

## Checklist

- [ ] Listed in `fornax.mixins.json`
- [ ] Class name states target + job; suffix matches kind
- [ ] Every injected method and `@Unique` member is `fornax$`-prefixed
- [ ] Narrowest injector that works; any `@Overwrite` justified in Javadoc
- [ ] No-op when no pack is active
- [ ] World-describing state committed from an every-frame call site
- [ ] Class Javadoc explains target, change, injection point, inactive behaviour
- [ ] Row added to `docs/ARCHITECTURE.md` §8
