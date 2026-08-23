---
paths: "src/test/**/*.java"
---

# Testing Standards

185 test classes under `src/test/java` (plus 3 shared support classes: `PackFixtures`,
`GraphValidatorTestSupport`, `PbrSettingsBlockParser`), mirroring the main package tree. The suite is
**contract-heavy** on purpose: most of this engine's failure modes are silent, so the tests pin
names, byte sizes, layouts and gate expressions rather than exercising a GPU.

## Running

```bash
./gradlew clean test    # RUN TWICE
```

**Run it twice.** Plain `test` replays stale up-to-date greens, and some suites are order-sensitive
— a single green run is not evidence. `clean` is not optional.

`test` runs with `--enable-native-access=ALL-UNNAMED`; `ComputeShaderCompilerTest` loads a real
shaderc native via LWJGL, and JDK 25 warns loudly without the flag.

**Never launch Minecraft to verify.** No `runClient`. If a change genuinely needs in-game evidence,
say so and stop — the user verifies from their own sessions and the logs land in `logs/`.

## Conventions

- JUnit Jupiter 5.11.3. `import org.junit.jupiter.api.Test`, static-import the specific assertions
  you use (`assertEquals`, `assertTrue`, `assertThrows`) rather than `Assertions.*`.
- Test classes are **package-private** (`class FooTest {`), named `<Subject>Test`, in the same
  package as the subject so package-private internals are reachable.
- Test method names are full sentences in camelCase, stating the claim, not the mechanics:
  `byteSizeIsCountWordPlusMaxLightsTimesWordsPerLight`, `swimmingBackOutIsSilent`,
  `worldChangeDropsTheBaselineSoTheNextDiveSnaps`. A name that reads `testFoo` is not a claim.
- `@TempDir` for anything touching the filesystem (pack discovery, cache round-trips).
- A comment above a magic expected value explains where the number comes from — the arithmetic, the
  spec requirement, or the live bug it pins.

## What to test

- **Contracts before behaviour.** Target names, builtin lists, byte sizes, alignment, enum ordinals,
  gate expressions, dense-ID assignment order. These are what break silently.
- **Source-level tests are legitimate here.** `BuiltinResolutionContractTest` reads
  `GraphInputResolver`'s source and asserts a `switch` case exists, because the resolver has no
  runtime enumeration and invoking it would need a live GPU device. Its Javadoc says exactly why —
  do the same when you write one, or the next reader deletes it as a hack.
- **Every new validation gets a fixture that fails it.** `src/test/resources/packs/` holds packs
  broken on purpose (`bad_toml`, `cycle`, `missing_target`, `runtime_in_enabledif`) for exactly
  this.
- **Every config field gets a migration test.** Old config JSON on disk must keep deserializing.

## Fixture order — the hash-salt trap

**Never seed an ordered fixture from `Map.of`.** Its iteration order is salted per JVM run, so a
green run is luck, not a pass. Anywhere a test pins declaration order (dense-ID assignment,
cross-file option merge order, uniform layout order), build the fixture with sequential `put()`
into a `LinkedHashMap`.

## Cross-repo: the Plague pack

The suite loads the real Plague pack from `../plague` when present and **skips when it is not**
(`Assumptions`). While Plague is being re-authored, these tests can go red without Fornax having
changed.

**Before touching engine code for a red test, check whether the failing assertion is about pack
content.** The dependency runs pack → engine only. Nothing here may depend on a specific pack's
option labels; where a test needs some, they are a fixture, not a contract.

## Checklist

- [ ] `./gradlew clean test` run twice, both green
- [ ] Test class is package-private, `<Subject>Test`, in the subject's package
- [ ] Method names state the claim in a sentence
- [ ] Expected magic values carry a comment saying where they come from
- [ ] Ordered fixtures use sequential `put()`, never `Map.of`
- [ ] New validation has a broken-pack fixture
- [ ] New config field has a migration test
- [ ] Minecraft was never launched
