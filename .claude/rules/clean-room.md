---
paths: "**/*"
---

# Clean-Room Provenance Standards

The provenance protocol for this repository. This is the whole of it; there is no longer a second
copy under `docs/`.

## The one rule everything follows from

**Copyright protects expression, not ideas.** Fornax ships under MIT, which is a *grant* — you
cannot grant what you do not own. The bar is not "does it look different", it is "did this come out
of my own reasoning".

Free to use, always:

- That a technique exists and roughly how it works.
- Published mathematics, cited. Lottes, Hammon, Cornette–Shanks, GGX, Rayleigh/Mie, Pope & Fry,
  Keys, Roberts R1, interleaved gradient noise. Cite the paper and implement it.
- Which API calls a thing requires, and which vanilla behaviour has to be worked around.
- Measurements you take yourself, including measurements *of* another pack's output.

Never free:

- Statement sequence, identifiers, structure, comment text.
- **Authored constants** — numbers encoding taste rather than physics: colour tables, damping
  rates, octave counts, thresholds, slider ranges.
- **A deviation from published physics.** The sharpest tell there is. Take the number from the
  paper instead.
- Option names, defaults and value ranges.

## The two-context rule — NON-NEGOTIABLE

**A context that has read the reference may not write the implementation.**

| | Reader | Writer |
|---|---|---|
| May open the reference | **Yes** | **No — never** |
| Produces | A behavioural spec, in prose | The implementation |
| May carry numbers from the reference | **No** | n/a |
| Reviews the result against the reference | **No** | n/a |

With agents: one subagent reads and returns a prose spec; a **separate** subagent implements from
that spec and is given no path to the reference. Never do both in one context. Never paste the
reference into the writing context "just to check" — checking is how statement order gets
reproduced.

A good spec describes mechanism and carries no expression: *"Stars are a hash lattice on the sky
sphere; cells above a cutoff draw a star with a soft edge; several cutoffs at different densities
mix many faint stars with a few bright ones."* The writer picks the hash, the resolution, the
cutoffs, the softness, the tint. Those choices are then the writer's.

## Constants

Every constant is one of two kinds:

- **Physically derived** — keep, but cite the paper, measurement or standard in the comment. The
  labPBR decode thresholds qualify: the format spec dictates them.
- **Authored** — must be your own. Pick it off a render, tune it, or derive it from a property you
  can state. Then write down *why*. `// 0.58 because the trough weight makes the ripple
  volume-neutral over the radial measure` is a defence; the same number bare is a liability.

**When you cannot tell which kind it is, it is authored.** Assume the stricter rule.

## Comments and commit messages

**Never name another pack or renderer as a source, in code or in a commit message.** Describe the
mechanism on its own terms instead.

**One hard sequencing rule.** If an admission is already in the tree, delete it *in the same change
that replaces the code under it* — never before. Removing the comment while keeping the code
removes no infringement and adds the appearance of concealment.

Interoperability naming is fine and is not what this is about: `heldBlockLightValue`,
`isEyeInWater`, `gbuffers_*`, `sunPathRotation`, Sodium mixin targets, `@Accessor` signatures and
JVM descriptors are a published interface this engine must speak.

## Provenance records

Two ledgers, both root-level, both "no row, no entry into the tree":

- **`THIRD-PARTY-NOTICES.md`** — every piece of *code* bundled into the shipped jar (currently the
  JiJ'd night-config jars), with project, version, author, licence, and where to get the source.
  Full licence texts ship in `licenses/`.
- **`ASSETS.md`** — every *binary* in the repository, with its source and a licence permitting MIT
  redistribution. A file whose provenance cannot be stated gets regenerated or replaced, not
  grandfathered.

There is no tooling behind any of this. A `scripts/pre-commit` gate and an allowlist were once
described but never written, so the protocol is enforced by review alone. Do not claim a commit was
gate-checked.

## What to record as you go

The derivation trail is the affirmative half of this. Absence of a bad comment proves nothing;
presence of a good one proves a lot.

Generate authored tables -- start positions, palettes, sample kernels -- with a committed script
rather than pasting the numbers. The script is the record that the table is yours, and it re-runs.

## The convergence test

After a rewrite, compare against the old build:

- *Close but not identical*: correct.
- *Identical*: the rewrite failed. You reconstructed their tuning from memory; do it again.
- *Wildly different*: probably a bug. Check the mechanism.

## Checklist

- [ ] The context that wrote this never opened another renderer's source
- [ ] Every authored constant has a provenance comment
- [ ] Every cited formula names its paper
- [ ] No comment or commit message names another project as a source
- [ ] Any new bundled dependency has a row in `THIRD-PARTY-NOTICES.md` and its full licence text
      in `licenses/`
- [ ] Any new binary asset has a row in `ASSETS.md` naming a source and an MIT-redistributable
      licence
