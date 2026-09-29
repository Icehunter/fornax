#!/usr/bin/env bash
# Builds Fornax and deploys the resulting Fabric jar into the "Vulkan Setup"
# profile.
#
# LOCAL-ONLY: this script only builds and copies files on disk. It does not
# touch git, the launcher database or content store. No commit or push is performed.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Destination profile. Override per machine:
#   FORNAX_PROFILE="/path/to/launcher/profiles/My Profile" ./scripts/deploy.sh
DST_PROFILE="${FORNAX_PROFILE:-}"
if [ -z "$DST_PROFILE" ]; then
  echo "FORNAX_PROFILE is not set. Point it at the launcher profile to deploy into:" >&2
  echo "  FORNAX_PROFILE=\"\$HOME/Library/Application Support/ModrinthApp/profiles/<name>\" $0" >&2
  exit 1
fi
if [ ! -d "$DST_PROFILE" ]; then
  echo "FORNAX_PROFILE is not a directory: $DST_PROFILE" >&2
  exit 1
fi

echo "=== Building fornax ==="
( cd "$REPO" && ./gradlew build )

echo ""
echo "=== Locating built jar ==="
LIBS_DIR="$REPO/build/libs"
if [ ! -d "$LIBS_DIR" ]; then
  echo "ERROR: expected build output dir not found: $LIBS_DIR" >&2
  exit 1
fi

# Exclude -sources and -dev jars: Loom's remap step can leave those alongside
# the final shipping jar under build/libs/.
CANDIDATES=()
while IFS= read -r f; do
  CANDIDATES+=("$f")
done < <(find "$LIBS_DIR" -maxdepth 1 -name 'fornax-*.jar' ! -name '*-sources.jar' ! -name '*-dev.jar' | sort)

if [ "${#CANDIDATES[@]}" -eq 0 ]; then
  echo "ERROR: no candidate jar found in $LIBS_DIR" >&2
  exit 1
fi

if [ "${#CANDIDATES[@]}" -gt 1 ]; then
  echo "ERROR: expected exactly one jar in $LIBS_DIR, found ${#CANDIDATES[@]}:" >&2
  printf '  %s\n' "${CANDIDATES[@]}" >&2
  exit 1
fi

JAR="${CANDIDATES[0]}"
JAR_NAME="$(basename "$JAR")"
BUILD_MD5="$(md5 -q "$JAR" 2>/dev/null || md5sum "$JAR" | cut -d' ' -f1)"

# Keep one stable jar path across builds. The launcher manages its own index.
DEPLOY_NAME="$JAR_NAME"

echo "  found: $JAR_NAME"
echo "  md5:   $BUILD_MD5"
echo "  deploy as: $DEPLOY_NAME (stable path)"

echo ""
echo "=== Deploying to $DST_PROFILE/mods ==="
mkdir -p "$DST_PROFILE/mods"

# Stage beside the destination so rename replaces the path without truncating an open jar.
# A running client keeps its existing open file; restart Minecraft to load the new build.
STAGED_JAR="$(mktemp "$DST_PROFILE/mods/.fornax-deploy.XXXXXX")"
trap 'if [ -n "$STAGED_JAR" ]; then rm -f -- "$STAGED_JAR"; fi' EXIT
cp -p "$JAR" "$STAGED_JAR"
chmod 600 "$STAGED_JAR"
STAGED_MD5="$(md5 -q "$STAGED_JAR" 2>/dev/null || md5sum "$STAGED_JAR" | cut -d' ' -f1)"
if [ "$STAGED_MD5" != "$BUILD_MD5" ]; then
  echo "ERROR: staged jar does not match the build; existing installation preserved" >&2
  exit 1
fi

# Preserve other Fornax jars outside mods/ while replacing the stable deploy path.
mkdir -p "$DST_PROFILE/mods-retired"
shopt -s nullglob
for old in "$DST_PROFILE/mods"/fornax-*.jar; do
  [ "$(basename "$old")" = "$DEPLOY_NAME" ] && continue
  echo "  retiring: $(basename "$old") -> mods-retired/"
  mv -f "$old" "$DST_PROFILE/mods-retired/"
done
shopt -u nullglob

mv -f "$STAGED_JAR" "$DST_PROFILE/mods/$DEPLOY_NAME"
STAGED_JAR=""

# Verify what landed rather than trusting the copy: a truncated or partially written jar is exactly
# the failure the launcher's own integrity check would report next, and it is cheaper to catch here.
DEPLOYED_MD5="$(md5 -q "$DST_PROFILE/mods/$DEPLOY_NAME" 2>/dev/null \
  || md5sum "$DST_PROFILE/mods/$DEPLOY_NAME" | cut -d' ' -f1)"
if [ "$DEPLOYED_MD5" != "$BUILD_MD5" ]; then
  echo "ERROR: deployed jar does not match the build" >&2
  echo "  built:    $BUILD_MD5" >&2
  echo "  deployed: $DEPLOYED_MD5" >&2
  exit 1
fi

echo ""
echo "  verified md5: $DEPLOYED_MD5"

# Optional: link a pack checkout into the profile so edits to it are live without a copy step.
# Set FORNAX_LINK_PACK to the checkout path; the link is named after that directory.
#
#   FORNAX_LINK_PACK=/path/to/my-pack ./scripts/deploy.sh
#
# Unset by default. The engine ships no pack and boots with shaders off, so a deploy that links
# nothing is the normal case.
if [ -n "${FORNAX_LINK_PACK:-}" ]; then
  if [ ! -d "$FORNAX_LINK_PACK" ]; then
    echo "FORNAX_LINK_PACK is set but is not a directory: $FORNAX_LINK_PACK" >&2
    exit 1
  fi
  LINK_NAME="$(basename "$FORNAX_LINK_PACK")"
  echo ""
  echo "=== Linking $LINK_NAME into $DST_PROFILE/shaderpacks ==="
  mkdir -p "$DST_PROFILE/shaderpacks"
  /bin/rm -f "$DST_PROFILE/shaderpacks/$LINK_NAME"
  ln -s "$FORNAX_LINK_PACK" "$DST_PROFILE/shaderpacks/$LINK_NAME"
  echo "  linked: $DST_PROFILE/shaderpacks/$LINK_NAME -> $FORNAX_LINK_PACK"
fi

echo ""
echo "Deployed: $DEPLOY_NAME"
echo "Nothing was committed or pushed — this script only touches the filesystem."
