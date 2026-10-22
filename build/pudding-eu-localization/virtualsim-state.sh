#!/system/bin/sh
# Reversible user-0 enable state, after Android's regional package policy runs.
set -eu
umask 077
ACTION=${1:?apply recover or restore}
MODULE=${2:?module path}
ADB_BASE=${3:-/data/adb}
STATE="$ADB_BASE/pudding-eu-localization-state"
SLOT="$STATE/virtualsim-enable"
PACKAGE=com.miui.virtualsim
EXPECTED_SHA=3716704b30befea53ef402ab84ce71c2f51b2f56a413de52296b004b03408d84
case "$ACTION" in apply|recover|restore) ;; *) exit 1 ;; esac
if [ "${PUDDING_STATE_LOCKED:-}" != 1 ]; then
  sh "${0%/*}/state-lock.sh" "$ADB_BASE" "$0" "$@"
  exit $?
fi
selected() {
  [ "$ACTION" != restore ] && [ -d "$MODULE" ] && [ ! -e "$MODULE/disable" ] \
    && [ ! -e "$MODULE/remove" ] && [ -f "$MODULE/markers/GlobalRoamingVirtualSim" ] \
    && [ -f "$MODULE/system/product/app/MIUIVirtualSim/MIUIVirtualSim.apk" ] \
    && [ -f "$MODULE/EU2CN_Config.ini" ] || return 1
  local value
  value=$(awk -F= '$1 ~ /^[[:space:]]*GlobalRoamingVirtualSim[[:space:]]*$/ {
    v=$2; gsub(/[[:space:]\r]/,"",v); found=1} END {if(found) print tolower(v)}' "$MODULE/EU2CN_Config.ini")
  case "$value" in true|yes|1|on) return 0 ;; *) return 1 ;; esac
}
log() { printf '%s virtualsim-enable: %s\n' "$(date '+%F %T')" "$*" >> "$STATE/lifecycle.log"; }
archive() {
  local destination="$STATE/history-virtualsim-enable-$(date +%s)-$$"
  [ ! -e "$destination" ] && mv "$SLOT" "$destination"
}
release() { [ ! -d "$SLOT" ] || { : > "$SLOT/released"; log "$*; ownership released"; }; }
safe_slot() {
  [ ! -L "$STATE" ] && [ ! -L "$SLOT" ] || return 1
  local name
  for name in original uid ready applied applied-boot released; do
    [ ! -L "$SLOT/$name" ] || return 1
    [ ! -e "$SLOT/$name" ] || [ -f "$SLOT/$name" ] || return 1
  done
}
# Capture Binder output in a pipe; never pass a /data/adb log descriptor to pm.
command_state() {
  local mode="$1" output rc
  output=$(pm "$mode" --user 0 "$PACKAGE" 2>&1) && rc=0 || rc=$?
  [ -z "$output" ] || printf '%s\n' "$output" >> "$STATE/lifecycle.log"
  return "$rc"
}
read_package() {
  local paths metadata record code
  KIND=unknown; UID_VALUE=; ENABLED=
  paths=$(pm path "$PACKAGE" 2>/dev/null || true)
  if [ -z "$paths" ]; then
    if pm path android 2>/dev/null | grep -q '^package:'; then KIND=absent; fi
    return 0
  fi
  case "$paths" in
    *package:/data/app/*) KIND=update; return 0 ;;
    package:/product/app/MIUIVirtualSim/MIUIVirtualSim.apk|package:/system/product/app/MIUIVirtualSim/MIUIVirtualSim.apk) ;;
    *) KIND=other; return 0 ;;
  esac
  [ "$(sha256sum "${paths#package:}" | cut -d ' ' -f1)" = "$EXPECTED_SHA" ] || { KIND=other; return 0; }
  metadata=$(dumpsys package "$PACKAGE" 2>/dev/null || true)
  UID_VALUE=$(printf '%s\n' "$metadata" | awk '/^[[:space:]]*(appId|userId)=[0-9]+/ && !seen++ {
    sub(/^[[:space:]]*(appId|userId)=/,""); sub(/ .*/,""); print}')
  code=$(printf '%s\n' "$metadata" | awk '/^[[:space:]]*versionCode=/ && !seen++ {
    sub(/^[[:space:]]*versionCode=/,""); sub(/ .*/,""); print}')
  record=$(printf '%s\n' "$metadata" | awk '/^[[:space:]]*User 0:/ && !seen++ {print}')
  case "$UID_VALUE" in ''|*[!0-9]*) return 0 ;; esac
  [ "$code" = 9120 ] || { KIND=other; return 0; }
  case "$record" in *' installed=false '*) KIND=absent; return 0 ;; *' installed=true '*) ;; *) return 0 ;; esac
  ENABLED=$(printf '%s\n' "$record" | awk '{for(i=1;i<=NF;i++) if($i ~ /^enabled=[0-4]$/) {sub(/^enabled=/,"",$i);print $i}}')
  case "$ENABLED" in 0|1|2|3|4) KIND=official ;; esac
}

WANTED=no
if selected; then WANTED=yes; fi
[ "$WANTED" = yes ] || [ -d "$SLOT" ] || exit 0
safe_slot || exit 1
[ "$(getprop sys.boot_completed 2>/dev/null || true)" = 1 ] || exit 1
mkdir -p "$STATE"
read_package
case "$KIND" in
  unknown) log 'PackageManager unavailable; pending'; exit 1 ;;
  update|other) release 'different APK/manual update preserved';
    [ "$WANTED" = yes ] || { [ ! -d "$SLOT" ] || archive; }; exit 0 ;;
  absent) if [ "$WANTED" = yes ]; then log 'package not installed; pending'; exit 1; fi
    [ ! -d "$SLOT" ] || { log 'package absent; state archived'; archive; }; exit 0 ;;
esac
if [ -f "$SLOT/ready" ]; then
  ORIGINAL=$(cat "$SLOT/original"); SAVED_UID=$(cat "$SLOT/uid")
  case "$ORIGINAL" in 0|1|2) ;; *) exit 1 ;; esac
  case "$SAVED_UID" in ''|*[!0-9]*) exit 1 ;; esac
  if [ "$UID_VALUE" != "$SAVED_UID" ]; then
    release 'package UID changed; current state preserved'
    [ "$WANTED" = yes ] || archive
    exit 0
  fi
fi
if [ "$WANTED" = no ]; then
  [ -f "$SLOT/ready" ] || { [ ! -d "$SLOT" ] || archive; exit 0; }
  if [ -f "$SLOT/released" ] || [ "$ENABLED" != 1 ]; then
    log 'external/current state preserved on restore'; archive; exit 0
  fi
  # Recheck user state/identity immediately before changing a package setting.
  read_package
  [ "$KIND" = official ] && [ "$UID_VALUE" = "$SAVED_UID" ] && [ "$ENABLED" = 1 ] \
    || { log 'restore race preserved'; archive; exit 0; }
  case "$ORIGINAL" in 0) mode=default-state ;; 1) mode=enable ;; 2) mode=disable ;; esac
  if [ "$ORIGINAL" != 1 ]; then command_state "$mode" || exit 1; fi
  read_package
  [ "$KIND" = official ] && [ "$UID_VALUE" = "$SAVED_UID" ] && [ "$ENABLED" = "$ORIGINAL" ] || exit 1
  log "original state $ORIGINAL restored"; archive; exit 0
fi
[ ! -f "$SLOT/released" ] || exit 0
case "$ENABLED" in 3|4) release 'manual disabled-user/disabled-until-used preserved'; exit 0 ;; esac
BOOT=$(cat /proc/sys/kernel/random/boot_id)
case "$BOOT" in ''|*[!a-zA-Z0-9-]*) exit 1 ;; esac
if [ -f "$SLOT/applied" ] && [ "$ENABLED" = 0 ]; then
  release 'external default-state preserved'; exit 0
fi
if [ -f "$SLOT/applied" ] && [ "$ENABLED" = 2 ] \
  && [ "$(cat "$SLOT/applied-boot")" = "$BOOT" ]; then
  release 'same-boot disable preserved'; exit 0
fi
if [ ! -f "$SLOT/ready" ]; then
  mkdir -p "$SLOT"
  printf '%s\n' "$ENABLED" > "$SLOT/original"
  printf '%s\n' "$UID_VALUE" > "$SLOT/uid"
  : > "$SLOT/ready"
fi
SAVED_UID=$(cat "$SLOT/uid")
if [ "$ENABLED" != 1 ]; then
  read_package
  [ "$KIND" = official ] && [ "$UID_VALUE" = "$SAVED_UID" ] \
    || { release 'APK/UID race preserved'; exit 0; }
  case "$ENABLED" in 0|2) ;; 1) ;; *) release 'user state race preserved'; exit 0 ;; esac
  # Module/option state can change while Binder is queried.
  selected || exit 1
  [ "$ENABLED" = 1 ] || command_state enable || exit 1
  read_package
  [ "$KIND" = official ] && [ "$UID_VALUE" = "$SAVED_UID" ] && [ "$ENABLED" = 1 ] || exit 1
fi
printf '%s\n' "$BOOT" > "$SLOT/applied-boot"
: > "$SLOT/applied"
log 'enabled after boot; original retained; global region unchanged'
