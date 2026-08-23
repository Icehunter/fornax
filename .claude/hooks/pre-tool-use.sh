#!/bin/bash
# Pre-tool-use hook. Surfaces Fornax's silent-failure reminders right before the relevant
# file is touched, and blocks any attempt to launch Minecraft.
#
# Claude Code sends the hook payload as JSON on STDIN, with the tool name under `.tool_name`
# and its arguments under `.tool_input.*`. When there is something to say, this script
# writes the official PreToolUse JSON envelope to stdout.

INPUT="$(cat)"

# No jq, no hook. Never block on a missing dependency.
command -v jq >/dev/null 2>&1 || exit 0

TOOL_NAME=$(printf '%s' "$INPUT" | jq -r '.tool_name // empty')
FILE_PATH=$(printf '%s' "$INPUT" | jq -r '.tool_input.file_path // empty')
COMMAND=$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty')

emit_deny() {
    jq -n --arg reason "$1" '{
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": $reason
        }
    }'
    exit 0
}

CONTEXT_PARTS=()

# ==============================================================================
# Bash: block launching Minecraft, and keep the verification command honest.
# ==============================================================================
if [[ "$TOOL_NAME" == "Bash" ]]; then
    if [[ "$COMMAND" == *"runClient"* ]]; then
        emit_deny "CLAUDE.md: never launch Minecraft. Live verification comes from the user's own sessions -- report that in-game evidence is needed and stop. Game logs from their sessions are in logs/."
    fi

    if [[ "$COMMAND" == *"gradlew"*"test"* ]] || [[ "$COMMAND" == *"gradle "*"test"* ]]; then
        if [[ "$COMMAND" != *"clean"* ]]; then
            CONTEXT_PARTS+=("VERIFICATION: plain \`test\` replays stale up-to-date greens. Use \`./gradlew clean test\` -- and run it TWICE, some suites are order-sensitive.")
        else
            CONTEXT_PARTS+=("VERIFICATION: run \`./gradlew clean test\` TWICE before claiming green. Also: a red test may be about the ../plague pack's content, not engine code -- check the failing assertion before touching src/.")
        fi
    fi
fi

# ==============================================================================
# Write/Edit: silent-failure reminders, chosen by which file is being edited.
# ==============================================================================
if [[ "$TOOL_NAME" == "Write" ]] || [[ "$TOOL_NAME" == "Edit" ]] || [[ "$TOOL_NAME" == "MultiEdit" ]]; then

    # Mixins: registration is the classic silent failure.
    if [[ "$FILE_PATH" == *"/mixin/"*.java ]]; then
        if [[ ! -f "$FILE_PATH" ]]; then
            CONTEXT_PARTS+=("NEW MIXIN: it must be listed in src/main/resources/fornax.mixins.json (package-relative, e.g. vanilla.FooMixin) or it is silently NEVER applied -- no error, no warning, no log line. It also needs a row in docs/ARCHITECTURE.md section 8. See .claude/rules/mixins.md.")
        fi
        CONTEXT_PARTS+=("MIXIN RULES: prefix every injected method and @Unique member with 'fornax\$'. Prefer the narrowest injector (@Inject > @Redirect > @Overwrite). Must be a no-op when no pack is active. Gate the DECISION, never the DATA -- world-describing state must be committed from a call site that runs every frame.")
    fi

    # Tests.
    if [[ "$FILE_PATH" == *Test.java ]]; then
        CONTEXT_PARTS+=("TESTS: package-private class, name states the claim as a sentence, static-import the specific JUnit 5 assertions. NEVER seed an ordered fixture from Map.of -- iteration order is hash-salt randomised per JVM run; use sequential put(). Comment where every magic expected value comes from. See .claude/rules/testing.md.")
    fi

    # Main sources: clean room, and the architecture doc obligation.
    if [[ "$FILE_PATH" == *"/src/main/java/"*.java ]] && [[ "$FILE_PATH" != *"/mixin/"* ]]; then
        if [[ ! -f "$FILE_PATH" ]]; then
            CONTEXT_PARTS+=("NEW SOURCE FILE: clean-room check first -- if this context has read another renderer's or pack's source, it may NOT write this implementation (.claude/rules/clean-room.md). Mechanism, not policy: no aesthetic decision belongs in the engine. If this changes a surface documented in docs/ARCHITECTURE.md, update that file in the SAME commit.")
        fi
    fi

    # std140 / uniform layout.
    case "$FILE_PATH" in
        *Layout.java|*Uniform*.java|*GBuffer*.java|*PushConstant*.java|*Globals*.java)
            CONTEXT_PARTS+=("std140 FOOTGUN: Std140Builder.putVec3 pads to a full 16-byte slot on the Java side while the shader compiler uses the spec-minimal 12. NEVER add a scalar immediately after a vec3 -- put it before, or keep the vec3 last. Silently wrong, no compile error. Update the block's byte-size assertion in the same change.")
            ;;
    esac

    # Shader assets.
    case "$FILE_PATH" in
        *.glsl|*.fsh|*.vsh|*.comp)
            CONTEXT_PARTS+=("SHADER: depth is REVERSED-Z (clears to 0.0 = far, compares greater-or-equal). Engine shaders must stay mechanism (blit, downsample, reconstruct) -- a look belongs to the pack. Every authored constant needs a provenance comment; every cited formula names its paper. Includes fail silently downstream, so every #moj_import is validated eagerly at load.")
            ;;
    esac

    # Pack manifests.
    case "$FILE_PATH" in
        *graph.toml|*pack.toml|*blocks.toml|*screens.toml)
            CONTEXT_PARTS+=("PACK MANIFEST: TOML parse order is semantic (category order becomes dense material ID order; option order becomes uniform layout order). Only COMPILE options may appear in enabled_if. Gate consistency is enforced: a pass must never be enabled while an enabled_if-gated target it touches is unallocated. New validation needs a deliberately-broken fixture pack under src/test/resources/packs/. See .claude/rules/pack-format.md.")
            ;;
    esac
fi

# ==============================================================================
# Emit
# ==============================================================================
if [[ ${#CONTEXT_PARTS[@]} -gt 0 ]]; then
    CONTEXT=""
    for part in "${CONTEXT_PARTS[@]}"; do
        if [[ -n "$CONTEXT" ]]; then
            CONTEXT="$CONTEXT | $part"
        else
            CONTEXT="$part"
        fi
    done

    jq -n --arg context "$CONTEXT" '{
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": $context
        }
    }'
fi

# Anything not denied above is allowed to proceed.
exit 0
