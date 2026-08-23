# Rules Directory

Modular standards for Fornax. Each file covers ONE concern and carries a `paths:` frontmatter key
naming the files it applies to. `AGENTS.md` links to them from the "Modular Rules" table --
keep that table and this list in sync. `CLAUDE.md` is a one-line import of `AGENTS.md`, so there is
only one file to edit.

| File | Applies to |
|---|---|
| `clean-room.md` | everything — provenance protocol, reader/writer split, constants |
| `architecture.md` | `src/main/java/**` — layering, types, failure discipline, config |
| `mixins.md` | `mixin/**` — registration, naming, injector choice, no-pack no-op |
| `gpu-contracts.md` | `pipeline`, `pass`, `atlas`, `voxel`, `metalfx`, shader assets |
| `pack-format.md` | `pack/**`, `*.toml` — manifests, validation, compile vs runtime options |
| `testing.md` | `src/test/**` — JUnit conventions, contract tests, fixture-order rule |
| `documentation.md` | `**/*.md` — ARCHITECTURE.md same-commit rule, comments, commits |

## Writing a new rule file

Keep the shape the existing ones use:

```markdown
---
paths: "<glob>"
---

# [Concern] Standards

## Rules            <!-- non-negotiable requirements, with the reason -->
## Patterns         <!-- preferred approaches, with real examples from this tree -->
## Anti-Patterns    <!-- what to avoid and the failure it causes -->
## Checklist        <!-- - [ ] items an agent can verify before finishing -->
```

Two house rules for rule files themselves:

1. **Only document what is observably true in this tree.** Every count, path and constant in these
   files came from reading the code. If you add a claim, verify it first.
2. **State the failure, not just the rule.** "Register the mixin" is forgettable; "an unregistered
   mixin is silently never applied" is not.
