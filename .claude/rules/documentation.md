---
paths: "**/*.md"
---

# Documentation Standards

## `docs/ARCHITECTURE.md` is a same-commit obligation

Its own header states the rule: *"This document describes the current code. Changes to any surface
documented here must update this file in the same commit."*

Surfaces it documents, and what to update when you touch them:

| Change | Section to update |
|---|---|
| Package added/repurposed | §2 Package layout |
| Frame ordering, orchestration seam | §3 Frame skeleton |
| Load steps, validation, rebuild triggers | §4 Pack loading pipeline |
| Target sizing, basis, builtin names | §5 Target model |
| Uniform block, push constants, per-pass params | §6 Uniform contracts |
| Vertex channel | §7 Vertex format |
| Any mixin added or removed | §8 Mixin inventory |
| blocks.toml semantics, material GLSL | §9 Material system |
| Engine setting, apply routing, YACL surface | §10 Config |
| Debug view | §11 Debug views |
| A newly discovered silent-failure law | §12 Known laws |

§12 is the highest-value section in the repo: it is the list of things that fail with no error.
When you lose an afternoon to one, write it there.

## The other docs

- `.claude/rules/clean-room.md` — the provenance protocol. Changes there are policy changes; do not
  edit it to make a specific change easier to justify.
- `README.md` — user-facing: requirements, build, pointers. Keep it short; internals go in
  `docs/ARCHITECTURE.md`.
- `THIRD-PARTY-NOTICES.md` — every dependency bundled into the jar; full licence texts in
  `licenses/`. No row, no bundling.
- `ASSETS.md` — every binary in the repository, with source and licence. No row, no entry into the
  tree.
- `docs/PACK-FORMAT.md` — the manual for writing a shaderpack. The rules in its "Rules that bite"
  section were each found by a shader failing to compile or silently doing nothing; add to it when
  the next one is.
- `.audit/` holds the punchlist, the provenance audit and the historical water audit. It is
  excluded through `.git/info/exclude` rather than `.gitignore`, so the ignore rule itself never
  ships. `docs/local/`, `.graph/` and `.superpowers/` are the same kind of working area.

## Comments in code

- **Present tense only. No history.** A comment states the current engineering reason a piece of
  code is shaped the way it is — never the story of how it got that way. Banned: "used to",
  "previously", "before this", "was reverted", "already/once/fixed via/fixed by", "LIVE-CAUGHT",
  incident dates. If a comment reads as narration of a bug that happened rather than a mechanism
  that holds, rewrite it: keep every technical fact (what breaks, what depends on what), drop the
  timeline.
- The story of a bug — what broke, how it was found, what was tried — belongs in `docs/local/`
  (gitignored), never in a comment. Comments ship; incident write-ups do not.
- **Every authored constant carries a provenance comment** — the paper, the measurement, or the
  render it was tuned against. See `.claude/rules/clean-room.md`.
- **Never name another pack or renderer as a source.** Not in a comment, not in a commit message.
  Where such an admission already exists, delete it *in the same change that replaces the code
  under it*, never before.

## Markdown style

Matching the existing docs: ATX headings, tables for anything with more than two parallel facts,
fenced code blocks with a language tag, `---` between top-level sections in the longer files. Body
text wraps around 100 columns. Prose over bullet soup where a paragraph reads better.

## Checklist

- [ ] `docs/ARCHITECTURE.md` updated in the same commit as the surface it documents
- [ ] A newly discovered silent failure added to §12 Known laws
- [ ] No comment narrates history — present tense, mechanism only, no incident story
- [ ] Authored constants carry provenance comments
- [ ] No comment or commit message names another project as a source
- [ ] Gitignored scratch dirs not treated as documentation
