#!/bin/bash
# Session start hook - remind Claude to follow project rules.
# Runs at the start of every Claude session.

cat <<'EOF'
IMPORTANT: This project has strict requirements in CLAUDE.md and .claude/rules/.

Before writing any code:
- Read CLAUDE.md, especially "Before you write code, read docs/CLEAN_ROOM.md" and "Mandatory Workflow"
- The rules in .claude/rules/ are REQUIREMENTS, not suggestions
- Clean-room: a context that has read another renderer's source may NOT write the implementation
- A new mixin absent from fornax.mixins.json is silently never applied
- Verify with `./gradlew clean test`, run TWICE (a single green run is stale-cache luck)
- NEVER launch Minecraft. No ./gradlew runClient. In-game evidence comes from the user's sessions.

Do not skip these steps.
EOF
