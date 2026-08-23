#!/usr/bin/env bash
# Builds Fornax and deploys the resulting Fabric jar into the "Vulkan Setup"
# profile.
#
# LOCAL-ONLY: this script only builds and copies files on disk. It does not
# touch git, does not commit, does not push. Safe to re-run any time.
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

echo "  found: $JAR_NAME"

echo ""
echo "=== Deploying to $DST_PROFILE/mods ==="
mkdir -p "$DST_PROFILE/mods"

# Remove any existing fornax-*.jar so exactly one is ever present.
shopt -s nullglob
for old in "$DST_PROFILE/mods"/fornax-*.jar; do
  echo "  removing existing: $(basename "$old")"
  mv -f "$old" "$DST_PROFILE/mods-retired/" 2>/dev/null || rm -f "$old"
done
shopt -u nullglob

cp -p "$JAR" "$DST_PROFILE/mods/$JAR_NAME"

echo ""
echo "  md5: $(md5 -q "$DST_PROFILE/mods/$JAR_NAME" 2>/dev/null || md5sum "$DST_PROFILE/mods/$JAR_NAME")"

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
echo "Deployed: $JAR_NAME"
echo "Nothing was committed or pushed — this script only touches the filesystem."
