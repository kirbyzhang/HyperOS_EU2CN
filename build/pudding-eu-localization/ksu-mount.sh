#!/system/bin/sh
# User-invoked background batch. Never write KernelSU's profile file directly.
set -eu
umask 077
MODDIR=${0%/*}
MODE=${1:---plan}
case "$MODE" in --plan|--apply|--apply-ui) ;; *) echo '用法：ksu-mount.sh --plan、--apply 或 --apply-ui'; exit 1 ;; esac
[ "$(id -u)" = 0 ] || { echo '请从 KernelSU 模块入口运行'; exit 1; }
[ "$(readlink -f "$MODDIR")" = /data/adb/modules/HyperOS_EU2CN ] || {
  echo '请先安装并完整重启，仅在活动模块中运行'; exit 1;
}
[ -f "$MODDIR/ksu-mount-helper.dex" ] && [ ! -L "$MODDIR/ksu-mount-helper.dex" ] || exit 1
STATE=/data/adb/pudding-eu-localization-state
[ ! -L "$STATE" ] || exit 1
mkdir -p "$STATE"
[ "$(readlink -f "$STATE")" = "$STATE" ] || exit 1
BASE="$STATE/ksu-mount-actions"
[ ! -L "$BASE" ] || exit 1
mkdir -p "$BASE"
chmod 700 "$BASE"
AUDIT=$(mktemp -d "$BASE/run-XXXXXXXX")
echo "本次日志：$AUDIT/result.log"
if [ "$MODE" = --plan ]; then
  CLASSPATH="$MODDIR/ksu-mount-helper.dex" app_process /system/bin local.pudding.ksu.KsuBackgroundClient --plan "$MODDIR" "$AUDIT" 2>&1 | tee "$AUDIT/result.log"
  # The helper's completion marker distinguishes failure from a successful plan.
  grep -q '^BACKGROUND_COMPLETE ' "$AUDIT/result.log"
else
  HELPER=local.pudding.ksu.KsuBackgroundClient
  if [ "$MODE" = --apply-ui ]; then
    HELPER=local.pudding.ksu.KsuMountHelper
    echo '使用旧版界面方式，需要解锁亮屏、Material 中文界面及 1220×2656 屏幕。'
  else
    [ -f "$MODDIR/system/product/app/PuddingEUCompatibility/PuddingEUCompatibility.apk" ] || {
      echo '后台配置需要安装兼容层；请重新安装时选中兼容层，或手动设置挂载例外。'; exit 1;
    }
    echo '后台补齐挂载例外，无需保持亮屏，不打开管理器页面。'
    echo '需在 LSPosed 启用 HyperOS_EU2CN 兼容层，作用域勾选 KernelSU。'
  fi
  echo '后台方式按实际接口和配置结构检测兼容性；保留全局默认、Root 授权和其他配置。'
  echo '完成后需完整重启。不会自动调整 LSPosed 作用域或重启手机。'
  # The manager action terminal may be closed by navigation. Detach its worker.
  /data/adb/ksu/bin/busybox --list | grep -qx setsid || { echo '缺少 setsid，停止启动'; exit 1; }
  /data/adb/ksu/bin/busybox setsid /data/adb/ksu/bin/busybox nohup sh -c '
    export CLASSPATH="$1/ksu-mount-helper.dex"
    app_process /system/bin "$3" --apply "$1" "$2"
    helper_rc=$?
    printf "\nHELPER_EXIT=%s\n" "$helper_rc"
    exit "$helper_rc"
  ' pudding-ksu-worker "$MODDIR" "$AUDIT" "$HELPER" > "$AUDIT/result.log" 2>&1 < /dev/null &
  echo "辅助程序已启动，PID=$!。结果以日志及配置复核为准。"
fi
