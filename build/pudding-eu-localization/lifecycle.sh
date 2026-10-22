#!/system/bin/sh
# Only module-owned state is stored here. Never source saved data as shell code.
ACTION=${1:-recover}
MODULE=${2:-/data/adb/modules/HyperOS_EU2CN}
ADB_BASE=${3:-/data/adb}
STATE="$ADB_BASE/pudding-eu-localization-state"
XMSF_DIR=${PUDDING_XMSF_DIR:-/data/user_de/0/com.xiaomi.xmsf}
LEGACY=local.pudding.eulocalization.v4
if [ "${PUDDING_STATE_LOCKED:-}" != 1 ]; then
  sh "${0%/*}/state-lock.sh" "$ADB_BASE" "$0" "$@"
  exit $?
fi

log_state() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$STATE/lifecycle.log"; }
module_active() { [ -d "$MODULE" ] && [ ! -e "$MODULE/disable" ] && [ ! -e "$MODULE/remove" ]; }
push_selected() { module_active && { [ -f "$MODULE/markers/MiPush" ] || [ -f "$MODULE/markers/CnXmsf" ]; }; }
tools_selected() { module_active && [ -f "$MODULE/markers/ToolsBox" ]; }
archive_slot() {
  local slot="$1"
  mv "$STATE/$slot" "$STATE/history-$slot-$(date +%s)-$$" || return 1
}

safe_region_path() {
  [ -d "$XMSF_DIR" ] && [ ! -L "$XMSF_DIR" ] && [ ! -L "$XMSF_DIR/files" ] \
    && [ ! -L "$XMSF_DIR/files/$1" ]
}

# Same-directory rename keeps either the old or the complete new value visible.
replace_region() {
  local source="$1" name="$2" preserve="$3" temporary uid gid
  safe_region_path "$name" || return 1
  if [ ! -d "$XMSF_DIR/files" ]; then
    uid=$(stat -c %u "$XMSF_DIR") || return 1
    gid=$(stat -c %g "$XMSF_DIR") || return 1
    mkdir "$XMSF_DIR/files" || return 1
    chown "$uid:$gid" "$XMSF_DIR/files" || return 1
    chmod 700 "$XMSF_DIR/files" || return 1
  fi
  temporary=$(mktemp "$XMSF_DIR/files/.pudding-region.XXXXXX") || return 1
  if [ "$preserve" = yes ]; then
    cp -p "$source" "$temporary" || { rm -f "$temporary"; return 1; }
  else
    uid=$(stat -c %u "$XMSF_DIR") || { rm -f "$temporary"; return 1; }
    gid=$(stat -c %g "$XMSF_DIR") || { rm -f "$temporary"; return 1; }
    cp "$source" "$temporary" && chown "$uid:$gid" "$temporary" && chmod 600 "$temporary" \
      || { rm -f "$temporary"; return 1; }
  fi
  mv -f "$temporary" "$XMSF_DIR/files/$name" || { rm -f "$temporary"; return 1; }
  if command -v restorecon >/dev/null 2>&1; then
    restorecon "$XMSF_DIR/files" "$XMSF_DIR/files/$name" 2>/dev/null || true
  fi
}

apply_region() {
  local slot="$1" name="$2" value="$3" path="$XMSF_DIR/files/$2"
  [ -d "$XMSF_DIR" ] || { log_state "$slot: pending; XMSF directory unavailable"; return 1; }
  safe_region_path "$name" || { log_state "$slot: unsafe path; no changes made"; return 1; }
  [ ! -e "$path" ] || [ -f "$path" ] || return 1
  [ ! -f "$STATE/$slot/released" ] || return 0
  if [ ! -f "$STATE/$slot/ready" ]; then
    mkdir -p "$STATE/$slot" || return 1
    if [ -f "$path" ]; then
      cp -p "$path" "$STATE/$slot/original" || return 1
      rm -f "$STATE/$slot/absent"
    else
      : > "$STATE/$slot/absent"
      rm -f "$STATE/$slot/original"
    fi
    printf '%s\n' "$value" > "$STATE/$slot/expected" || return 1
    : > "$STATE/$slot/ready"
  fi
  if cmp -s "$path" "$STATE/$slot/expected"; then
    : > "$STATE/$slot/applied"
    return 0
  fi
  if [ -f "$STATE/$slot/applied" ]; then
    : > "$STATE/$slot/released"
    log_state "$slot: external change preserved; ownership released"
    return 0
  fi
  if { [ -f "$STATE/$slot/absent" ] && [ ! -e "$path" ]; } \
    || cmp -s "$path" "$STATE/$slot/original"; then
    replace_region "$STATE/$slot/expected" "$name" no || return 1
    : > "$STATE/$slot/applied"
    log_state "$slot: original saved; override applied"
  else
    : > "$STATE/$slot/released"
    log_state "$slot: changed before first write; preserved"
  fi
}

restore_region() {
  local slot="$1" name="$2" path="$XMSF_DIR/files/$2"
  [ -f "$STATE/$slot/ready" ] || return 0
  [ -d "$XMSF_DIR" ] || { log_state "$slot: pending; XMSF directory unavailable for restore"; return 1; }
  if [ -f "$STATE/$slot/released" ] || ! safe_region_path "$name" \
    || ! cmp -s "$path" "$STATE/$slot/expected"; then
    log_state "$slot: current value changed/missing; left untouched"
    archive_slot "$slot"
    return $?
  fi
  if [ -f "$STATE/$slot/absent" ]; then
    rm -f "$path" || return 1
  else
    replace_region "$STATE/$slot/original" "$name" yes || return 1
  fi
  log_state "$slot: original state restored"
  archive_slot "$slot"
}

# Android's shell has no get-component-enabled-setting command on this ROM.
# Read the exact user-0 enabled/disabled component lists from dumpsys instead.
component_state() {
  local component="$1"
  dumpsys package "$LEGACY" 2>/dev/null | awk -v target="$component" '
    /^[[:space:]]*User [0-9]+:/ {
      active=($0 ~ /^[[:space:]]*User 0:/)
      if(active) {
        found=1
        if($0 ~ /(^|[[:space:]])installed=false([[:space:]]|$)/) uninstalled=1
      }
      mode=""; next
    }
    active && /disabledComponents:/ {mode="disabled"; next}
    active && /enabledComponents:/ {mode="enabled"; next}
    active && /^[[:space:]]*[A-Za-z].*:/ {mode=""}
    active && mode!="" {line=$0; gsub(/^[[:space:]]+|[[:space:]]+$/, "", line);
      if(line==target) state=mode}
    # A keep-data uninstall can retain disabledComponents with pkg=null. These
    # are historical settings, not an installed component that pm can restore.
    END {if(uninstalled) print "unknown"; else if(state!="") print state;
      else if(found) print "default"; else print "unknown"}'
}

# Binder shell commands cannot receive module-owned /data/adb log descriptors on
# this ROM. Pass a pipe to PackageManager, then append the captured output here.
component_command() {
  local mode="$1" component="$2" output result
  output=$(pm "$mode" --user 0 "$LEGACY/$component" 2>&1)
  result=$?
  [ -z "$output" ] || printf '%s\n' "$output" >> "$STATE/lifecycle.log"
  return "$result"
}

apply_component() {
  local slot="$1" component="$2" current
  [ ! -f "$STATE/$slot/released" ] || return 0
  current=$(component_state "$component")
  if [ "$current" = unknown ]; then
    if ! pm path "$LEGACY" 2>/dev/null | grep -q '^package:' \
      && pm path android 2>/dev/null | grep -q '^package:'; then
      log_state "$slot: legacy package absent; no component change"
      return 0
    fi
    log_state "$slot: pending; PackageManager/package unavailable"
    return 1
  fi
  if [ ! -f "$STATE/$slot/ready" ]; then
    [ "$current" != disabled ] || return 0
    mkdir -p "$STATE/$slot" || return 1
    printf '%s\n' "$current" > "$STATE/$slot/original" || return 1
    : > "$STATE/$slot/ready"
  fi
  if [ "$current" = disabled ]; then : > "$STATE/$slot/applied"; return 0; fi
  if [ -f "$STATE/$slot/applied" ] || [ "$current" != "$(cat "$STATE/$slot/original")" ]; then
    : > "$STATE/$slot/released"
    log_state "$slot: user component change preserved"
    return 0
  fi
  component_command disable "$component" || return 1
  [ "$(component_state "$component")" = disabled ] || return 1
  : > "$STATE/$slot/applied"
  log_state "$slot: legacy entry disabled; APK/data retained"
}

restore_component() {
  local slot="$1" component="$2" current original
  [ -f "$STATE/$slot/ready" ] || return 0
  current=$(component_state "$component")
  if [ "$current" = unknown ]; then
    # Only discard an obsolete package-state record once PackageManager itself is available.
    if ! pm path "$LEGACY" 2>/dev/null | grep -q '^package:' \
      && pm path android 2>/dev/null | grep -q '^package:'; then
      log_state "$slot: legacy package absent; state archived"
      archive_slot "$slot"
      return $?
    fi
    log_state "$slot: PackageManager unavailable; restore deferred"
    return 1
  fi
  if [ -f "$STATE/$slot/released" ] || [ "$current" != disabled ]; then
    log_state "$slot: user component state preserved"
    archive_slot "$slot"
    return $?
  fi
  original=$(cat "$STATE/$slot/original")
  case "$original" in
    default) component_command default-state "$component" || return 1 ;;
    enabled) component_command enable "$component" || return 1 ;;
    *) return 1 ;;
  esac
  [ "$(component_state "$component")" = "$original" ] || return 1
  log_state "$slot: component state restored"
  archive_slot "$slot"
}

[ ! -L "$STATE" ] || exit 1
if [ "$ACTION" != prepare ] && [ ! -d "$STATE" ] && ! push_selected && ! tools_selected; then exit 0; fi
mkdir -p "$STATE" || exit 1
chmod 700 "$STATE" || exit 1
if [ "$ACTION" = prepare ]; then
  mkdir -p "$ADB_BASE/service.d" || exit 1
  if [ -f "$MODULE/virtualsim-state.sh" ]; then
    cp "$MODULE/virtualsim-state.sh" "$STATE/virtualsim-state.sh.new" \
      && chmod 700 "$STATE/virtualsim-state.sh.new" \
      && mv -f "$STATE/virtualsim-state.sh.new" "$STATE/virtualsim-state.sh" || exit 1
  fi
  cp "$MODULE/lifecycle.sh" "$STATE/lifecycle.sh.new" && chmod 700 "$STATE/lifecycle.sh.new" \
    && mv -f "$STATE/lifecycle.sh.new" "$STATE/lifecycle.sh" || exit 1
  cp "$MODULE/boot-tasks.sh" "$STATE/boot-tasks.sh.new" && chmod 700 "$STATE/boot-tasks.sh.new" \
    && mv -f "$STATE/boot-tasks.sh.new" "$STATE/boot-tasks.sh" || exit 1
  cp "$MODULE/state-lock.sh" "$STATE/state-lock.sh.new" && chmod 700 "$STATE/state-lock.sh.new" \
    && mv -f "$STATE/state-lock.sh.new" "$STATE/state-lock.sh" || exit 1
  cp "$MODULE/recovery.sh" "$ADB_BASE/service.d/.pudding-eu-recovery.new" \
    && chmod 700 "$ADB_BASE/service.d/.pudding-eu-recovery.new" \
    && mv -f "$ADB_BASE/service.d/.pudding-eu-recovery.new" "$ADB_BASE/service.d/pudding-eu-recovery.sh" || exit 1
  if [ -f "$MODULE/package-records.sh" ] && [ -f "$MODULE/cache-targets.tsv" ]; then
    cp "$MODULE/package-records.sh" "$STATE/package-records.sh.new" \
      && chmod 700 "$STATE/package-records.sh.new" \
      && mv -f "$STATE/package-records.sh.new" "$STATE/package-records.sh" || exit 1
    : > "$STATE/cache-targets.tsv.new"
    while IFS='|' read -r pkg relative rest; do
      [ ! -f "$MODULE/$relative" ] || printf '%s|%s|%s\n' "$pkg" "$relative" "$rest" >> "$STATE/cache-targets.tsv.new"
    done < "$MODULE/cache-targets.tsv"
    mv -f "$STATE/cache-targets.tsv.new" "$STATE/cache-targets.tsv" || exit 1
    if [ -f "$MODULE/cache-pending.tsv" ]; then
      cp "$MODULE/cache-pending.tsv" "$STATE/cache-pending.tsv.new" \
        && mv -f "$STATE/cache-pending.tsv.new" "$STATE/cache-pending.tsv" || exit 1
    fi
    : > "$STATE/cache-enrolled"
    rm -f "$STATE/cache-restored"
    mkdir -p "$ADB_BASE/post-fs-data.d" || exit 1
    cp "$MODULE/cache-recovery.sh" "$ADB_BASE/post-fs-data.d/.pudding-eu-cache.new" \
      && chmod 700 "$ADB_BASE/post-fs-data.d/.pudding-eu-cache.new" \
      && mv -f "$ADB_BASE/post-fs-data.d/.pudding-eu-cache.new" "$ADB_BASE/post-fs-data.d/pudding-eu-cache-recovery.sh" || exit 1
  fi
  log_state 'recovery helper prepared; no application data changed'
  exit 0
fi

result=0
virtualsim_helper="$STATE/virtualsim-state.sh"
[ -f "$virtualsim_helper" ] || virtualsim_helper="$MODULE/virtualsim-state.sh"
if [ -f "$virtualsim_helper" ]; then
  sh "$virtualsim_helper" "$ACTION" "$MODULE" "$ADB_BASE" || result=1
elif [ -f "$MODULE/markers/GlobalRoamingVirtualSim" ] || [ -d "$STATE/virtualsim-enable" ]; then
  log_state 'virtualsim helper unavailable; state retained'; result=1
fi
# Each invocation releases the kernel lock before the boot wrapper retries.
printf '%s action=%s status=running\n' "$(date '+%F %T')" "$ACTION" > "$STATE/status.new"
mv -f "$STATE/status.new" "$STATE/status"
if [ "$ACTION" = apply ] && push_selected; then
  apply_region region-country mipush_country_code CN || result=1
  apply_region region-name mipush_region China || result=1
elif [ "$ACTION" = restore ] || ! push_selected; then
  restore_region region-country mipush_country_code || result=1
  restore_region region-name mipush_region || result=1
fi
if [ "$ACTION" = apply ] && tools_selected; then
  apply_component legacy-main local.pudding.eulocalization.MainActivity || result=1
  apply_component legacy-maintenance local.pudding.eulocalization.MaintenanceActivity || result=1
elif [ "$ACTION" = restore ] || ! tools_selected; then
  restore_component legacy-main local.pudding.eulocalization.MainActivity || result=1
  restore_component legacy-maintenance local.pudding.eulocalization.MaintenanceActivity || result=1
fi
if [ "$result" -eq 0 ]; then status=completed; else status=pending_or_failed; fi
printf '%s action=%s status=%s\n' "$(date '+%F %T')" "$ACTION" "$status" > "$STATE/status.new"
mv -f "$STATE/status.new" "$STATE/status"
if [ ! -d "$MODULE" ] && [ "$result" -eq 0 ]; then
  if [ -f "$STATE/cache-enrolled" ] && { [ ! -f "$STATE/cache-restored" ] || [ -f "$STATE/cache-pending.tsv" ]; }; then
    log_state 'system package reparse requires next early boot; recovery helpers retained'
    printf '%s action=%s status=pending_next_boot\n' "$(date '+%F %T')" "$ACTION" > "$STATE/status"
    exit 1
  fi
  archive_base="$ADB_BASE/pudding-eu-localization-archives"
  [ ! -L "$archive_base" ] || exit 1
  mkdir -p "$archive_base" && chmod 700 "$archive_base" || exit 1
  archive="$archive_base/uninstalled-$(date +%s)-$$"
  [ ! -e "$archive" ] || exit 1
  mv "$STATE" "$archive" || exit 1
  # Keep only recovery evidence; no executable helpers survive successful uninstall.
  rm -f "$archive/lifecycle.sh" "$archive/boot-tasks.sh" "$archive/package-records.sh" "$archive/state-lock.sh" "$archive/virtualsim-state.sh" "$archive/lock"
  rm -f "$ADB_BASE/service.d/pudding-eu-recovery.sh"
  rm -f "$ADB_BASE/post-fs-data.d/pudding-eu-cache-recovery.sh"
fi
exit "$result"
