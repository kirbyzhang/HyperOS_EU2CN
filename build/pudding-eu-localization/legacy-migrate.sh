#!/system/bin/sh
# Keep existing legacy data owned by an installed, disabled package after its system APK is retired.
set -eu
umask 077
PREVIOUS=${1:?previous module}
ADB_BASE=${2:-/data/adb}
LEGACY=local.pudding.eulocalization.v4
APK="$PREVIOUS/system/product/app/PuddingEUToolsBox/PuddingEUToolsBox.apk"
EXPECTED=b2308f4039a7f4153cd09abd2093e0b55c21fe58cd8cd6e281d3966d47fc3738
[ -f "$APK" ] || exit 0
[ ! -L "$APK" ] && [ "$(readlink -f "$APK")" = "$APK" ] || exit 1
[ "$(sha256sum "$APK" | cut -d ' ' -f1)" = "$EXPECTED" ] || exit 1
paths=$(pm path "$LEGACY" 2>/dev/null || true)
CE=${PUDDING_LEGACY_CE:-/data/user/0/$LEGACY}
DE=${PUDDING_LEGACY_DE:-/data/user_de/0/$LEGACY}
if [ -z "$paths" ]; then
  [ ! -d "$CE" ] && [ ! -d "$DE" ] \
    || { echo 'Legacy registration absent with retained data; cannot verify ownership, upgrade stopped' >&2; exit 1; }
  echo 'LEGACY_ABSENT; original module APK not reinstalled'
  exit 0
fi
BACKUPS="$ADB_BASE/pudding-legacy-retirement-backups"
[ ! -L "$BACKUPS" ] && [ ! -L "$ADB_BASE" ] || exit 1
mkdir -p "$BACKUPS"
[ "$(readlink -f "$BACKUPS")" = "$BACKUPS" ] || exit 1
chmod 700 "$BACKUPS"
backup=$(mktemp -d "$BACKUPS/retire-XXXXXXXX")
cp -p "$APK" "$backup/legacy.apk"
cmp -s "$APK" "$backup/legacy.apk" || exit 1
printf '%s\n' "$paths" > "$backup/paths.before"
metadata=$(dumpsys package "$LEGACY")
printf '%s\n' "$metadata" | grep -E 'appId=|userId=|versionCode=|User 0:' > "$backup/package.before"
uid_before=$(printf '%s\n' "$metadata" | awk '/^[[:space:]]*(appId|userId)=/ && !seen++ {sub(/^[[:space:]]*/, ""); print}')
[ -n "$uid_before" ] || exit 1
am force-stop --user 0 "$LEGACY" || exit 1
: > "$backup/data-identity.before"
: > "$backup/data-files.before"
for kind in ce de; do
  if [ "$kind" = ce ]; then data="$CE"; else data="$DE"; fi
  [ -d "$data" ] || continue
  [ ! -L "$data" ] && [ "$(readlink -f "$data")" = "$data" ] || exit 1
  stat -c '%u %g %i %n' "$data" >> "$backup/data-identity.before"
  tar -cpf "$backup/$kind.tar" -C / "${data#/}" || exit 1
  tar -tf "$backup/$kind.tar" >/dev/null || exit 1
  sha256sum "$backup/$kind.tar" >> "$backup/archives.sha256"
  find "$data" -type f ! -path "$data/cache/*" ! -path "$data/code_cache/*" \
    -exec sha256sum '{}' \; | sort >> "$backup/data-files.before"
done
case "$paths" in
  *package:/data/app/*) echo 'LEGACY_USER_UPDATE_PRESERVED' ;;
  *)
    case "$paths" in
      package:/product/app/PuddingEUToolsBox/PuddingEUToolsBox.apk|package:/system/product/app/PuddingEUToolsBox/PuddingEUToolsBox.apk) ;;
      *) echo 'Unknown legacy base path; retirement stopped' >&2; exit 1 ;;
    esac
    tmpbase=${PUDDING_LEGACY_STAGE:-/data/local/tmp}
    [ -d "$tmpbase" ] && [ ! -L "$tmpbase" ] || exit 1
    stage=$(mktemp -d "$tmpbase/pudding-legacy-XXXXXXXX")
    trap 'rm -rf -- "$stage"' EXIT
    cp "$APK" "$stage/legacy.apk"
    chmod 755 "$stage"
    chmod 644 "$stage/legacy.apk"
    cmp -s "$APK" "$stage/legacy.apk" || exit 1
    # Capture Binder output in a pipe, never redirect pm directly to /data/adb.
    if output=$(pm install --user 0 -r "$stage/legacy.apk" 2>&1); then
      printf '%s\n' "$output" > "$backup/install-result.txt"
    else
      printf '%s\n' "$output" >&2; exit 1
    fi
    paths=$(pm path "$LEGACY" 2>/dev/null || true)
    case "$paths" in package:/data/app/*) ;; *) exit 1 ;; esac
    installed=${paths#package:}
    [ "$(sha256sum "$installed" | cut -d ' ' -f1)" = "$EXPECTED" ] || exit 1
    ;;
esac
if output=$(pm disable-user --user 0 "$LEGACY" 2>&1); then
  printf '%s\n' "$output" > "$backup/disable-result.txt"
else
  printf '%s\n' "$output" >&2; exit 1
fi
pm list packages -d --user 0 "$LEGACY" | grep -qx "package:$LEGACY" || exit 1
metadata=$(dumpsys package "$LEGACY")
printf '%s\n' "$metadata" | grep -E 'appId=|userId=|versionCode=|User 0:' > "$backup/package.after"
uid_after=$(printf '%s\n' "$metadata" | awk '/^[[:space:]]*(appId|userId)=/ && !seen++ {sub(/^[[:space:]]*/, ""); print}')
[ "$uid_before" = "$uid_after" ] || exit 1
: > "$backup/data-identity.after"
: > "$backup/data-files.after"
for data in "$CE" "$DE"; do
  [ -d "$data" ] || continue
  stat -c '%u %g %i %n' "$data" >> "$backup/data-identity.after"
  find "$data" -type f ! -path "$data/cache/*" ! -path "$data/code_cache/*" \
    -exec sha256sum '{}' \; | sort >> "$backup/data-files.after"
done
cmp -s "$backup/data-identity.before" "$backup/data-identity.after" || exit 1
cmp -s "$backup/data-files.before" "$backup/data-files.after" || exit 1
echo "LEGACY_RETIRED_DATA_PRESERVED|$backup"
