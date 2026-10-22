#!/system/bin/sh
MODDIR=${0%/*}
MARKERS="$MODDIR/markers"

case "${1:-}" in
  --mount-plan) exec sh "$MODDIR/ksu-mount.sh" --plan ;;
  --mount-exceptions) exec sh "$MODDIR/ksu-mount.sh" --apply ;;
  --diagnose) ;;
  '')
    echo '=== HyperOS_EU2CN OS318.0.1 模块操作 ==='
    echo '[音量+] 后台补齐 KernelSU 挂载例外（需兼容层及 KernelSU 作用域）'
    echo '[音量-] 显示诊断；8 秒无操作默认显示诊断'
    echo '补齐操作保留默认卸载开关、已有 Root 授权和其他配置。'
    action_start=$(date +%s)
    while [ $(( $(date +%s) - action_start )) -lt 8 ]; do
      action_event=$(/system/bin/timeout 1 /system/bin/getevent -lc 1 2>/dev/null || true)
      case "$action_event" in
        *VOLUMEUP*DOWN*) exec sh "$MODDIR/ksu-mount.sh" --apply ;;
        *VOLUMEDOWN*DOWN*) break ;;
      esac
    done
    ;;
  *) echo '未知操作'; exit 1 ;;
esac

if [ ! -f "$MODDIR/diagnostics.sh" ]; then
  echo "diagnostics.sh is missing"
  exit 1
fi
. "$MODDIR/diagnostics.sh"

echo "=== HyperOS_EU2CN OS318.0.1 ==="
diag_environment
echo
echo "=== Selected component states ==="
diag_selected_packages
echo
echo "=== Runtime overlays ==="
diag_overlays
echo
echo "=== 兼容层开关 ==="
diag_hooks

if [ -s "$MODDIR/manual-reset-suggestions.txt" ]; then
  echo
  echo "=== Manual data reset suggestions (nothing was cleared automatically) ==="
  sed -n '1,80p' "$MODDIR/manual-reset-suggestions.txt"
fi

if [ -f "$MODDIR/localization-status.log" ]; then
  echo
  echo "=== Last boot validation ==="
  sed -n '1,240p' "$MODDIR/localization-status.log"
fi

echo
echo "=== Component and push-region recovery ==="
if [ -f /data/adb/pudding-eu-localization-state/status ]; then
  cat /data/adb/pudding-eu-localization-state/status
fi
if [ -f /data/adb/pudding-eu-localization-state/lifecycle.log ]; then
  tail -n 80 /data/adb/pudding-eu-localization-state/lifecycle.log
else
  echo "No recovery log yet"
fi
echo
echo '=== Last KernelSU mount-exception batch ==='
ksu_last=$(ls -td /data/adb/pudding-eu-localization-state/ksu-mount-actions/run-*/ 2>/dev/null | head -n 1)
if [ -n "$ksu_last" ] && [ -f "${ksu_last}result.log" ]; then
  echo "$ksu_last"
  tail -n 80 "${ksu_last}result.log"
else
  echo '尚无批处理记录'
fi
echo
echo "=== Recent package/overlay errors ==="
logcat -d -b system -t 800 2>/dev/null   | grep -E -i 'signature|reconcile|PackageManager|PuddingEU|idmap|overlay'   | tail -n 80
