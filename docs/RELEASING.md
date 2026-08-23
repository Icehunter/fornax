# Releasing

Version numbers live in exactly one place: `mod_version` in `gradle.properties`. The jar name, the
mod metadata and the release tag all derive from it, and CI refuses a tag that disagrees with it.

## Cutting a release

1. Decide the number. Fornax follows [semantic versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

   - **PATCH** (`0.1.0` to `0.1.1`) — a fix. Existing packs keep working, unchanged.
   - **MINOR** (`0.1.1` to `0.2.0`) — something new that does not break existing packs.
   - **MAJOR** (`0.2.0` to `1.0.0`) — a change that breaks existing packs.

   A pack-format change is almost always MAJOR, because packs in the wild stop loading. If you bump
   `format` in `pack.toml`'s schema, say so in the release notes in the first line.

2. Edit `gradle.properties`:

   ```
   mod_version=0.1.1
   ```

3. Commit that on its own, so the version bump is easy to find later:

   ```
   git commit -am "Release 0.1.1"
   ```

4. Tag it, matching the version exactly with a `v` in front, and push both:

   ```
   git tag v0.1.1
   git push && git push --tags
   ```

That is the whole process. Pushing the tag starts the Release workflow.

## What the workflow does

- Refuses the tag if it does not match `mod_version`. `v0.1.2` on a tree that still says `0.1.1`
  fails immediately rather than publishing a jar whose name contradicts its tag.
- Builds and runs the test suite twice. Gradle replays stale up-to-date results, so a single green
  run is not evidence — the same reason `.claude/rules/testing.md` gives for running it twice by
  hand.
- Creates a **draft** GitHub release with the jar attached. It is a draft on purpose: write the
  notes, read them, then publish.

Every push also runs the CI workflow, which builds and tests without releasing anything. If CI is
red, a tag will not produce a jar worth shipping.

## Modrinth

Not automated, deliberately. The first release has to be made by hand regardless: creating the
project, writing the description, setting the icon and ticking the AI-content disclosure are one-time
decisions no workflow should make for you.

Once the project exists and its settings are right, an upload step can be added to the release
workflow. It will need a `MODRINTH_TOKEN` repository secret.

Two things to have settled before that first upload:

- **Modrinth prohibits page imagery created or derived from generative AI** — icon, banner, gallery.
  That is a prohibition, not a disclosure requirement, so declaring the origin does not satisfy it.
  Screenshots of the running game are unaffected.
- **The AI-content disclosure** is set per project under Settings, and covers code, assets, text and
  functionality independently.
