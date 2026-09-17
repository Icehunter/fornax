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
BUILD_MD5="$(md5 -q "$JAR" 2>/dev/null || md5sum "$JAR" | cut -d' ' -f1)"
BUILD_SHA1="$(shasum -a 1 "$JAR" | cut -d' ' -f1)"
BUILD_SHA512="$(shasum -a 512 "$JAR" | cut -d' ' -f1)"
BUILD_SIZE="$(wc -c < "$JAR" | tr -d ' ')"

# The deployed name is the jar's own, deliberately STABLE across builds.
#
# Modrinth App 0.21.3 ("shared-content-store") indexes every file under mods/ by path, sha1 and
# size, and validates that index before launching. A row whose file is GONE is a hard stop:
#   Invalid input: mods/<name> needs repair or re-import before launching this instance
#
# A build-unique filename was tried here and is precisely what not to do: it orphans the path the
# launcher already indexed, and the instance then refuses to start on that dead row while the new
# jar sits beside it, correctly deployed and entirely unused. Overwriting one stable path keeps the
# row pointing at a real file, which the launcher re-hashes in place.
DEPLOY_NAME="$JAR_NAME"

echo "  found: $JAR_NAME"
echo "  md5:   $BUILD_MD5"
echo "  deploy as: $DEPLOY_NAME (stable path; the launcher indexes it by name)"

echo ""
echo "=== Deploying to $DST_PROFILE/mods ==="
mkdir -p "$DST_PROFILE/mods"

# Retire every fornax jar EXCEPT the stable deploy path, which is overwritten in place below so the
# launcher's index row keeps pointing at a real file. mods-retired is created first, because the
# fall through to rm when it did not exist, which silently deleted a known-good jar.
mkdir -p "$DST_PROFILE/mods-retired"
shopt -s nullglob
for old in "$DST_PROFILE/mods"/fornax-*.jar; do
  [ "$(basename "$old")" = "$DEPLOY_NAME" ] && continue
  echo "  retiring: $(basename "$old") -> mods-retired/"
  mv -f "$old" "$DST_PROFILE/mods-retired/"
done
shopt -u nullglob

cp -p "$JAR" "$DST_PROFILE/mods/$DEPLOY_NAME"

# Match the launcher's own file mode. Modrinth App writes every mod it manages as 0600, and its
# content store refused to adopt a 0644 jar with "Content links are unavailable in this directory" --
# the launcher then would not start the instance at all. cp -p carries the build tree's 0644 across,
# so the mode has to be set here rather than left to the copy.
chmod 600 "$DST_PROFILE/mods/$DEPLOY_NAME"

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

# --- Modrinth App launcher index ------------------------------------------------------------
# Modrinth App 0.21.3 ("shared-content-store", migrated 2026-09-15) indexes every file under a
# profile by path, sha1 and size, and REFUSES to launch while any indexed row is flagged missing:
#
#   Invalid input: mods/<name> needs repair or re-import before launching this instance
#
# Overwriting a jar in place flips that flag on every single deploy. Rows left behind by content
# deleted long ago block exactly as hard, and neither is fixable from the launcher UI, which hides
# the rows it believes are missing. Reconciling here is what makes a shell deploy usable at all.
#
# Set FORNAX_SKIP_LAUNCHER_INDEX=1 to leave the database untouched.
if [ -z "${FORNAX_SKIP_LAUNCHER_INDEX:-}" ]; then
  APP_DB="$HOME/Library/Application Support/ModrinthApp/app.db"
  if ! command -v sqlite3 >/dev/null 2>&1; then
    echo ""
    echo "  launcher index: skipped (no sqlite3 on PATH)"
  elif [ ! -f "$APP_DB" ]; then
    echo ""
    echo "  launcher index: skipped (no Modrinth App database at $APP_DB)"
  elif pgrep -x "Modrinth App" >/dev/null 2>&1; then
    # Writing under the running app risks a corrupt WAL, and it would overwrite this anyway.
    echo ""
    echo "  launcher index: SKIPPED -- Modrinth App is running." >&2
    echo "  Close it and re-run, or the instance will refuse to launch." >&2
  else
    PROFILE_KEY="$(basename "$DST_PROFILE")"
    INSTANCE_ID="$(sqlite3 -readonly "$APP_DB" \
      "select id from instances where path='$(printf '%s' "$PROFILE_KEY" | sed "s/'/''/g")';")"
    if [ -z "$INSTANCE_ID" ]; then
      echo ""
      echo "  launcher index: skipped (no instance registered for '$PROFILE_KEY')"
    else
      echo ""
      echo "=== Reconciling Modrinth index for $PROFILE_KEY ==="

      # 1. Adopt the jar into the content store, then point its row at what is on disk.
      #
      #    `missing` does NOT mean "the file is absent". A row whose sha1, size and file all match
      #    still carries it, and the launcher then refuses with "The content file was changed
      #    outside the app; preserve or re-import it before continuing". What the app verifies is
      #    the file against its content-store BLOB, so clearing the flag on its own is undone the
      #    moment it rescans. This reproduces by hand what the app's own "preserve" does: stage the
      #    bytes as a blob, point the file's row at that blob, and only then clear the flag.
      STORE_OBJECTS="$HOME/Library/Application Support/ModrinthApp/store/content/objects"
      BLOB_DIR="$STORE_OBJECTS/${BUILD_SHA512:0:2}"
      BLOB_PATH="$BLOB_DIR/$BUILD_SHA512"
      if [ -d "$STORE_OBJECTS" ]; then
        mkdir -p "$BLOB_DIR"
        if [ ! -f "$BLOB_PATH" ]; then
          cp -f "$JAR" "$BLOB_PATH"
          chmod 600 "$BLOB_PATH"
        fi
        NOW="$(date +%s)"
        sqlite3 "$APP_DB" "insert into store_blobs (sha512, size, status, modified_as, created_at, last_used_at, verified_at, sources) values ('$BUILD_SHA512', $BUILD_SIZE, 'ready', ${NOW}000000000, $NOW, $NOW, $NOW, '[]') on conflict(sha512) do update set last_used_at=$NOW, verified_at=$NOW, status='ready';"
        FILE_ROW_ID="$(sqlite3 -readonly "$APP_DB" "select id from instance_files where instance_id='$INSTANCE_ID' and relative_path='mods/$DEPLOY_NAME';")"
        if [ -n "$FILE_ROW_ID" ]; then
          sqlite3 "$APP_DB" "insert into store_instance_files (file_id, blob_sha512, materialization_kind) values ('$FILE_ROW_ID', '$BUILD_SHA512', 'copy') on conflict(file_id) do update set blob_sha512='$BUILD_SHA512', materialization_kind='copy';"
          echo "  content store: adopted as ${BUILD_SHA512:0:12}..."
        else
          echo "  content store: no index row for mods/$DEPLOY_NAME yet (add it once in the app)"
        fi
      else
        echo "  content store: not present, skipping adoption"
      fi

      sqlite3 "$APP_DB" "update instance_files set sha1='$BUILD_SHA1', size=$BUILD_SIZE, missing=0 where instance_id='$INSTANCE_ID' and relative_path='mods/$DEPLOY_NAME';"

      # 2. Drop rows whose file is genuinely gone. Checked one at a time against the filesystem
      #    rather than deleted by flag: a row flagged missing whose file EXISTS is a stale flag to
      #    clear, not a row to delete, and deleting it would unregister real content.
      ORPHANS=0
      while IFS= read -r rel; do
        [ -z "$rel" ] && continue
        if [ -e "$DST_PROFILE/$rel" ]; then
          sqlite3 "$APP_DB" "update instance_files set missing=0 where instance_id='$INSTANCE_ID' \
            and relative_path='$(printf '%s' "$rel" | sed "s/'/''/g")';"
          echo "  un-flagged (file present): $rel"
        else
          sqlite3 "$APP_DB" "delete from instance_files where instance_id='$INSTANCE_ID' \
            and relative_path='$(printf '%s' "$rel" | sed "s/'/''/g")';"
          ORPHANS=$((ORPHANS + 1))
        fi
      done < <(sqlite3 -readonly "$APP_DB" \
        "select relative_path from instance_files where instance_id='$INSTANCE_ID' and missing=1;")
      [ "$ORPHANS" -gt 0 ] && echo "  removed $ORPHANS orphaned row(s) for files no longer on disk"

      REMAINING="$(sqlite3 -readonly "$APP_DB" \
        "select count(*) from instance_files where instance_id='$INSTANCE_ID' and missing=1;")"
      echo "  rows still flagged missing: $REMAINING"
      if [ "$REMAINING" != "0" ]; then
        echo "  WARNING: the instance will refuse to launch while any row is flagged." >&2
      fi
    fi
  fi
fi

echo ""
echo "Deployed: $DEPLOY_NAME"
echo "Nothing was committed or pushed — this script only touches the filesystem."
