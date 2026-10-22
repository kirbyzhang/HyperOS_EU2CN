SKIPUNZIP=1
ASH_STANDALONE=1

FEATURE_KEYS="AppStore Wallet XiaoAi MmsYellowPage ContentRecognition PersonalAssistant CnSearch FocusXms Calendar Weather Music SoundRecorder Notes GallerySuite ThemeStore MiuiIme SecurityIme GboardTheme Fonts MiPush CnXmsf GlobalRoamingVirtualSim AiCall NewCall VideoCallBeauty LockscreenMagazine CarWith MiuiPassword DigitalKey ThirdAppAssistant ToolsBox"
HOOK_KEYS="HookXSpace HookXSpaceSafety HookFcm HookGoogleHome HookWidgets HookSystemUiIcons NotificationFilter HookInternational"
ALL_CONFIG_KEYS="$FEATURE_KEYS $HOOK_KEYS"

abort_install() {
  ui_print "! $1"
  abort "$1"
}

bool_enabled() {
  case "$1" in
    true|TRUE|True|yes|YES|Yes|1|on|ON|On) return 0 ;;
    *) return 1 ;;
  esac
}

config_value_from() {
  config_file="$1"
  config_key="$2"
  sed -n "s/^[[:space:]]*${config_key}[[:space:]]*=[[:space:]]*//p" "$config_file" 2>/dev/null     | tail -n 1 | tr -d '[:space:]'
}

set_config() {
  config_key="$1"
  config_value="$2"
  if grep -q "^${config_key}=" "$MODPATH/EU2CN_Config.ini" 2>/dev/null; then
    sed -i "s/^${config_key}=.*/${config_key}=${config_value}/" "$MODPATH/EU2CN_Config.ini"
  else
    printf '%s=%s\n' "$config_key" "$config_value" >> "$MODPATH/EU2CN_Config.ini"
  fi
}

config_enabled() {
  bool_enabled "$(config_value_from "$MODPATH/EU2CN_Config.ini" "$1")"
}

load_config_values() {
  source_config="$1"
  for config_key in $ALL_CONFIG_KEYS; do
    config_value="$(config_value_from "$source_config" "$config_key")"
    [ -n "$config_value" ] && set_config "$config_key" "$config_value"
  done
  # Only Notes survives the legacy combined option. AOD/ScreenRecorder keys
  # are retired and must never bring their payloads back during migration.
  legacy_notes_aod="$(config_value_from "$source_config" NotesAod)"
  if [ -n "$legacy_notes_aod" ] \
      && [ -z "$(config_value_from "$source_config" Notes)" ]; then
    set_config Notes "$legacy_notes_aod"
  fi
}

# Return 0 for volume up, 1 for volume down and 2 for timeout.
chooseport() {
  timeout_seconds="${1:-8}"
  event_file="$TMPDIR/pudding_volume_event"
  start_time="$(date +%s)"
  : > "$event_file"
  while true; do
    elapsed=$(( $(date +%s) - start_time ))
    [ "$elapsed" -ge "$timeout_seconds" ] && return 2
    /system/bin/timeout 1 /system/bin/getevent -lc 1 2>&1       | /system/bin/grep VOLUME       | /system/bin/grep " DOWN" > "$event_file" || true
    /system/bin/grep -q VOLUMEUP "$event_file" 2>/dev/null && return 0
    /system/bin/grep -q VOLUMEDOWN "$event_file" 2>/dev/null && return 1
  done
}

ask_feature() {
  config_key="$1"
  label="$2"
  default_value="${3:-false}"
  ui_print ""
  ui_print "- $label"
  ui_print "  [音量+] 安装 / Yes    [音量-] 跳过 / No"
  ui_print "  8 秒无操作使用默认值：$default_value"
  chooseport 8
  answer=$?
  case "$answer" in
    0) set_config "$config_key" true; ui_print "  已选择" ;;
    1) set_config "$config_key" false; ui_print "  已跳过" ;;
    *)
      set_config "$config_key" "$default_value"
      ui_print "  使用默认值：$default_value"
      ;;
  esac
}

enable_all_optional() {
  for config_key in $FEATURE_KEYS; do
    set_config "$config_key" true
  done
  # High-impact system-server/FCM and legacy global switches remain opt-in.
  set_config CnXmsf false
  set_config HookXSpace true
  set_config HookXSpaceSafety true
  set_config HookFcm false
  set_config HookGoogleHome true
  set_config NotificationFilter true
  set_config HookWidgets true
  set_config HookSystemUiIcons true
  set_config HookInternational false
}

extract_dir() {
  payload_dir="$1"
  case "$payload_dir" in
    *.apk|*.ttf|*.jar|*.xml)
      unzip -o "$ZIPFILE" "$payload_dir" -d "$MODPATH" >&2 \
        || abort_install "Failed to extract $payload_dir"
      ;;
    *)
      unzip -o "$ZIPFILE" "$payload_dir/*" -d "$MODPATH" >&2 \
        || abort_install "Failed to extract $payload_dir"
      ;;
  esac
}

mark_old_if_path() {
  config_key="$1"
  shift
  for old_path in "$@"; do
    if [ -e "$active_module_dir/$old_path" ]; then
      : > "$old_marker_dir/$config_key"
      return
    fi
  done
}

mark_old_if_marker() {
  config_key="$1"
  shift
  for old_key in "$@"; do
    if [ -f "$active_module_dir/markers/$old_key" ]       || [ -f "$active_module_dir/system/etc/pudding_eu_localization/$old_key" ]; then
      : > "$old_marker_dir/$config_key"
      return
    fi
  done
}

migrate_old_selection() {
  for config_key in $FEATURE_KEYS $HOOK_KEYS; do
    # An explicit selection in any modern INI always wins over inferred markers.
    if [ -z "$(config_value_from "$old_config" "$config_key")" ]; then
      case "$config_key" in
        Notes)
          # load_config_values already translated an explicit legacy false/true.
          [ -z "$(config_value_from "$old_config" NotesAod)" ] || continue
          ;;
      esac
      [ -f "$old_marker_dir/$config_key" ] && set_config "$config_key" true
    fi
  done

  old_toolsbox=false
  [ -f "$old_marker_dir/ToolsBox" ] && old_toolsbox=true
  old_hook_setting="$(config_value_from "$old_config" HookInternational)"
  if $old_toolsbox && [ -z "$old_hook_setting" ]; then
    # Legacy v3 had all four hooks coupled together.
    for config_key in HookXSpace HookWidgets HookSystemUiIcons; do
      [ -n "$(config_value_from "$old_config" "$config_key")" ] || set_config "$config_key" true
    done
    set_config HookInternational true
  fi

  # v3 contained the wallet services but not MipayWallet. Preserve the full
  # wallet bundle only when a wallet app is actually installed.
  if [ -z "$(config_value_from "$old_config" Wallet)" ] && pm path com.mipay.wallet >/dev/null 2>&1; then
    set_config Wallet true
  fi
}

make_marker() {
  config_key="$1"
  config_enabled "$config_key" && : > "$marker_dir/$config_key"
}

queue_reset_on_transition() {
  config_key="$1"
  package_name="$2"
  label="$3"
  old_value=false
  new_value=false
  [ -f "$old_marker_dir/$config_key" ] && old_value=true
  config_enabled "$config_key" && new_value=true
  if [ "$old_value" != "$new_value" ]; then
    printf '%s|%s\n' "$package_name" "$label" >> "$reset_queue"
  fi
}

any_enabled() {
  for config_key in "$@"; do
    config_enabled "$config_key" && return 0
  done
  return 1
}

append_prop() {
  printf '%s\n' "$1" >> "$MODPATH/system.prop"
}

device_props="$(getprop ro.product.device) $(getprop ro.product.vendor.device) $(getprop ro.product.product.name) $(getprop ro.product.system.device)"
version_props="$(getprop ro.build.version.incremental) $(getprop ro.product.build.version.incremental) $(getprop ro.system.build.version.incremental)"
sdk="$(getprop ro.build.version.sdk)"

ui_print ""
ui_print "- HyperOS_EU2CN OS318.0.1"
ui_print "- Device: $device_props"
ui_print "- Build: $version_props / API $sdk"

# Device, Android API and OS build are informational only; no compatibility gate.
ui_print "! No device, Android API or OS build restriction is enforced."
ui_print "! Payload base: pudding / Android 16 / OS3.0.319.0.WPCCNXM."
ui_print "! Compatibility with other devices or builds is NOT verified."

if [ -d "${NVBASE:-/data/adb}/modules/pudding_eu_wallet_bootstrap" ]; then
  abort_install "Remove pudding_eu_wallet_bootstrap before installing this module."
fi

active_module_dir="${NVBASE:-/data/adb}/modules/HyperOS_EU2CN"
# Retired password backend preflight. Run before extracting installer metadata.
# Android can remove data when a module-owned system base disappears; no
# password database migration or downgrade is attempted here.
if [ -e "$active_module_dir/system/product/app/MiuiPasswords/MiuiPasswords.apk" ] \
    || [ -L "$active_module_dir/system/product/app/MiuiPasswords/MiuiPasswords.apk" ] \
    || [ -L "$active_module_dir/system/product/app/MiuiPasswords" ]; then
  abort_install "旧模块提供密码后端；保留数据的迁移尚未完成，本次安装停止，不改动后端和数据"
fi
# End retired password backend preflight.
# Retired SoundPicker preflight. Shared media UID/data retirement has not been
# verified on a device; stop before changing the previous module or any data.
if [ -e "$active_module_dir/system/priv-app/SoundPicker/SoundPicker.apk" ] \
    || [ -L "$active_module_dir/system/priv-app/SoundPicker/SoundPicker.apk" ] \
    || [ -L "$active_module_dir/system/priv-app/SoundPicker" ]; then
  abort_install "旧模块装有独立声音选择器；保留数据的迁移尚未验证，本次安装停止，不改动共享媒体或应用数据"
fi
# End retired SoundPicker preflight.
# A cancelled installer may leave payloads from a different selection in the
# target. Never layer a new selection over those files or delete them here.
# The manager must supply an empty payload target for both fresh installs and
# upgrades; existing active modules remain untouched if that contract fails.
if [ -e "$MODPATH/system" ] || [ -L "$MODPATH/system" ] \
    || [ -e "$MODPATH/markers" ] || [ -L "$MODPATH/markers" ]; then
  abort_install "安装目标已有载荷或选择标记，可能是取消安装的残留；本次停止且不删除文件，请先由模块管理器清理未完成的安装"
fi
# End stale installer target preflight.
old_marker_dir="$TMPDIR/pudding_old_markers"
old_config="$TMPDIR/pudding_old_config.ini"
mkdir -p "$old_marker_dir"
if [ -f "$active_module_dir/EU2CN_Config.ini" ]; then
  cp -f "$active_module_dir/EU2CN_Config.ini" "$old_config"
elif [ -f "$active_module_dir/PuddingEULocalization.ini" ]; then
  cp -f "$active_module_dir/PuddingEULocalization.ini" "$old_config"
fi

# Build an old-state map from actual payloads, then merge marker-only options.
mark_old_if_path AppStore system/product/app/MIUISuperMarket
mark_old_if_path Wallet system/product/app/MipayWallet
mark_old_if_path XiaoAi system/product/app/VoiceAssistAndroidT
mark_old_if_path MmsYellowPage system/product/priv-app/Mms
mark_old_if_path ContentRecognition system/product/app/MiuiContentCatcher
mark_old_if_path PersonalAssistant system/product/priv-app/PersonalAssistant
mark_old_if_path FocusXms system/product/overlay/FocusXmsOverlay.apk
mark_old_if_path Calendar system/product/priv-app/Calendar
mark_old_if_path Weather system/product/priv-app/Weather
mark_old_if_path Music system/product/app/Music system/product/data-app/Music
mark_old_if_path SoundRecorder system/product/priv-app/SoundRecorder
mark_old_if_path Notes system/product/app/Notes
mark_old_if_marker Notes NotesAod
mark_old_if_path GallerySuite system/product/priv-app/MiuiGallery
mark_old_if_path ThemeStore system/product/app/ThemeManager
mark_old_if_path MiuiIme system/product/app/SogouIME
mark_old_if_path SecurityIme system/product/app/MIUISecurityInputMethod
mark_old_if_path Fonts system/product/fonts/MiSansVF.ttf
mark_old_if_path CnXmsf system/product/app/XiaomiServiceFramework
mark_old_if_path GlobalRoamingVirtualSim system/product/data-app/MIUIVirtualSim system/product/app/MIUIVirtualSim
mark_old_if_marker AiCall AiCall
mark_old_if_path NewCall system/product/priv-app/NewCall
mark_old_if_path LockscreenMagazine system/product/app/MiGalleryLockscreen
mark_old_if_path CarWith system/product/app/CarWith
mark_old_if_path MiuiPassword system/product/app/MiuiPassword system/product/data-app/MiuiPassword
mark_old_if_path DigitalKey system/system_ext/app/digitalkey
mark_old_if_path ThirdAppAssistant system/product/app/ThirdAppAssistant
mark_old_if_path ToolsBox system/product/app/PuddingEUToolsBox
mark_old_if_marker GboardTheme GboardTheme
mark_old_if_marker MiPush MiPush
mark_old_if_marker VideoCallBeauty VideoCallBeauty
mark_old_if_marker HookXSpace HookXSpace
mark_old_if_marker HookWidgets HookWidgets
mark_old_if_marker HookSystemUiIcons HookSystemUiIcons
mark_old_if_marker HookInternational HookInternational
mark_old_if_marker HookXSpaceSafety HookXSpaceSafety
mark_old_if_marker HookFcm HookFcm
mark_old_if_marker HookGoogleHome HookGoogleHome
mark_old_if_marker NotificationFilter NotificationFilter

# Extract installer metadata only. Payloads are extracted after selection.
unzip -o "$ZIPFILE" module.prop EU2CN_Config.ini README.md service.sh uninstall.sh lifecycle.sh recovery.sh boot-tasks.sh package-records.sh cache-recovery.sh cache-targets.tsv state-lock.sh legacy-migrate.sh virtualsim-state.sh action.sh ksu-mount.sh ksu-mount-helper.dex diagnostics.sh payload-manifest.tsv native-library-manifest.tsv -d "$MODPATH" >&2 \
  || abort_install "Unable to extract installer metadata"
: > "$MODPATH/system.prop"

if pm path eu.xiaomi.ext >/dev/null 2>&1; then
  ui_print "- xiaomi.eu marker package detected"
else
  ui_print "! xiaomi.eu marker was not detected; continuing because device/build match"
fi

external_config="/sdcard/Download/EU2CN_Config.ini"
if [ ! -f "$external_config" ] && [ -f /sdcard/Download/PuddingEULocalization.ini ]; then
  external_config="/sdcard/Download/PuddingEULocalization.ini"
fi
used_existing=false
if [ -f "$external_config" ]; then
  ui_print "- 使用外部配置：$external_config"
  load_config_values "$external_config"
  used_existing=true
elif [ -f "$old_config" ]; then
  ui_print ""
  ui_print "- 检测到上一版安装配置"
  ui_print "  [音量+] 沿用并迁移    [音量-] 重新选择"
  ui_print "  8 秒无操作重新选择"
  chooseport 8
  reuse_answer=$?
  if [ "$reuse_answer" -eq 0 ]; then
    load_config_values "$old_config"
    migrate_old_selection
    used_existing=true
    ui_print "- 已沿用上一版选择"
  fi
fi

if ! $used_existing; then
  ui_print ""
  ui_print "- 安装全部普通可选功能？"
  ui_print "  [音量+] 全选    [音量-] 逐项选择"
  ui_print "! CN XMSF、FCM/后台实验修复与全作用域国际标志不会被一键全选"
  ui_print "  8 秒无操作进入逐项选择"
  chooseport 8
  all_answer=$?
  if [ "$all_answer" -eq 0 ]; then
    enable_all_optional
    ui_print "- 已选择全部普通功能"
    ask_feature CnXmsf "完整 CN XMSF（高风险，重置推送注册）" false
    ask_feature HookFcm "兼容层：FCM/后台国际环境兼容（实验性，含系统框架）" false
    ask_feature HookInternational "兼容层：全作用域国际标志（实验性）" false
  else
    ask_feature AppStore "国行应用商店 GetApps" false
    ask_feature Wallet "完整小米钱包（钱包 + TSM/NextPay/银联/PaymentService）" false
    ask_feature XiaoAi "完整小爱（基础、语音、推荐、Proxy、默认助手 Overlay）" false
    ask_feature MmsYellowPage "国行短信与黄页" false
    ask_feature ContentRecognition "传送门与内容识别" false
    ask_feature PersonalAssistant "国行负一屏" false
    ask_feature CnSearch "国行系统搜索（桌面上滑；需启用兼容层并重启）" false
    ask_feature FocusXms "超级岛/XMS 通知兼容 Overlay" false
    ask_feature Calendar "国行日历" false
    ask_feature Weather "国行天气" false
    ask_feature Music "国行小米音乐 4.45.0.1" false
    ask_feature SoundRecorder "国行录音机" false
    ask_feature Notes "国行便签" false
    ask_feature GallerySuite "国行相册、MediaEditor 与完整 AI 模型" false
    ask_feature ThemeStore "国行主题管理器、主题商店与 CN Overlay" false
    ask_feature MiuiIme "小米版搜狗输入法与底栏优化" false
    ask_feature SecurityIme "小米安全键盘" false
    ask_feature GboardTheme "Gboard 主题调整" false
    ask_feature Fonts "MiLan Pro 字体替换" false
    ask_feature MiPush "MiPush 中国区区域覆盖（不替换 XMSF）" false
    ask_feature CnXmsf "完整 CN XMSF（高风险，重置推送注册）" false
    ask_feature GlobalRoamingVirtualSim "全球上网与虚拟 SIM" false
    ask_feature AiCall "AI 通话服务" false
    ask_feature NewCall "NewCall 新通话功能（含通话翻译）" false
    ask_feature VideoCallBeauty "视频通话美颜属性" false
    ask_feature LockscreenMagazine "国行锁屏画报" false
    ask_feature CarWith "CarWith 车机互联" false
    ask_feature MiuiPassword "小米密码管理" false
    ask_feature DigitalKey "数字车钥匙（NFC/eSE/BLE/UWB）" false
    ask_feature ThirdAppAssistant "智能应用兼容与通知修复助手" false
    ask_feature ToolsBox "HyperOS_EU2CN 兼容层（需 LSPosed）" false
    if config_enabled ToolsBox; then
      ask_feature HookXSpace "兼容层：双开推荐应用兼容" true
      ask_feature HookXSpaceSafety "兼容层：双开国际环境兼容" true
      ask_feature HookFcm "兼容层：FCM/后台国际环境兼容（实验性，含系统框架）" false
      ask_feature HookGoogleHome "兼容层：Google 助手负一屏与上滑搜索兼容" true
      ask_feature HookWidgets "兼容层：小米原生桌面小组件" true
      ask_feature HookSystemUiIcons "兼容层：国际版通知小图标策略" true
      ask_feature NotificationFilter "兼容层：通知过滤（LSPosed，保留系统原通知配置）" false
      ask_feature HookInternational "兼容层：全作用域国际标志（实验性）" false
    fi
  fi
fi

if ! config_enabled ToolsBox; then
  for config_key in $HOOK_KEYS; do
    set_config "$config_key" false
  done
fi

ui_print ""
ui_print "- 最终选择："
selected_count=0
for config_key in $FEATURE_KEYS; do
  if config_enabled "$config_key"; then
    ui_print "  + $config_key"
    selected_count=$((selected_count + 1))
  fi
done
[ "$selected_count" -eq 0 ] && ui_print "  （未选择任何组件）"

# Report signer switches; data cleanup is manual in the management APK.
reset_queue="$TMPDIR/pudding_reset_queue"
: > "$reset_queue"
: > "$MODPATH/manual-reset-suggestions.txt"
queue_reset_on_transition MmsYellowPage com.android.mms "短信应用设置（不删除 TelephonyProvider 短信数据库）"
queue_reset_on_transition ContentRecognition com.miui.contentcatcher "ContentCatcher 私有数据"
queue_reset_on_transition PersonalAssistant com.miui.personalassistant "负一屏布局与私有数据"
queue_reset_on_transition Calendar com.android.calendar "日历私有数据"
queue_reset_on_transition Weather com.miui.weather2 "天气私有数据"
queue_reset_on_transition Music com.miui.player "音乐设置、登录与私有数据库"
queue_reset_on_transition SoundRecorder com.android.soundrecorder "录音机私有设置（不删除共享存储录音文件）"
# Notes, like Gallery, is excluded from application-data reset suggestions.
# Preserve Gallery private data in both directions so favorites, hidden-state
# metadata and the local album index survive the CN/EU package switch.
queue_reset_on_transition GallerySuite com.miui.mediaeditor "MediaEditor 私有数据"
queue_reset_on_transition ThemeStore com.android.thememanager "主题管理器私有数据"
queue_reset_on_transition LockscreenMagazine com.mfashiongallery.emag "锁屏画报私有数据"
queue_reset_on_transition CnXmsf com.xiaomi.xmsf "XMSF 推送令牌和注册状态"

if [ -s "$reset_queue" ]; then
  ui_print ""
  ui_print "! 以下签名切换可能需要手动重置应用私有数据："
  while IFS='|' read -r package_name reset_label; do
    ui_print "  - $package_name：$reset_label"
  done < "$reset_queue"
  ui_print "! 安装器不会清理这些数据；启动后可在管理 APK 中逐项选择"
  cp "$reset_queue" "$MODPATH/manual-reset-suggestions.txt"
fi

# Password entry preflight: this version never supplies a missing backend.
if config_enabled MiuiPassword \
    && ! pm path com.miui.passwords 2>/dev/null | grep -q '^package:/'; then
  abort_install "未能确认系统已有密码后端；本版仅提供入口，不自动安装后端"
fi
# End password entry preflight.

# Retire an existing module-owned 4.2 base before the next boot removes it.
# Fresh installs do nothing; active /data/app updates are preserved.
sh "$MODPATH/legacy-migrate.sh" "$active_module_dir" "${NVBASE:-/data/adb}" \
  || abort_install "Legacy compatibility data retirement did not verify; upgrade stopped"

# Extract only the selected payloads.
config_enabled AppStore && extract_dir system/product/app/MIUISuperMarket
if config_enabled Wallet; then
  extract_dir system/product/app/MipayWallet
  extract_dir system/product/app/MINextpay
  extract_dir system/product/app/MITSMClient
  extract_dir system/product/app/UPTsmService
  extract_dir system/product/app/PaymentService
fi
if config_enabled XiaoAi; then
  extract_dir system/product/app/VoiceAssistAndroidT
  extract_dir system/product/app/VoiceTrigger
  extract_dir system/product/app/XiaoaiRecommendation
  extract_dir system/product/app/MIUIXiaoAiSpeechEngine
  extract_dir system/product/app/VoiceAssistProxy
  extract_dir system/product/overlay/VoiceAssistAndroidOverlay.apk
  extract_dir system/product/app/MIUIAiasstService
fi
if config_enabled MmsYellowPage; then
  extract_dir system/product/priv-app/Mms
  extract_dir system/product/priv-app/MIUIYellowPage
fi
if config_enabled ContentRecognition; then
  extract_dir system/product/app/MiuiContentCatcher
  extract_dir system/product/priv-app/MIUIContentExtension
  extract_dir system/product/app/HybridPlatform
fi
config_enabled PersonalAssistant && extract_dir system/product/priv-app/PersonalAssistant
config_enabled FocusXms && extract_dir system/product/overlay/FocusXmsOverlay.apk
config_enabled Calendar && extract_dir system/product/priv-app/Calendar
config_enabled Weather && extract_dir system/product/priv-app/Weather
config_enabled Music && extract_dir system/product/app/Music
config_enabled SoundRecorder && extract_dir system/product/priv-app/SoundRecorder
config_enabled Notes && extract_dir system/product/app/Notes
if config_enabled GallerySuite; then
  extract_dir system/product/priv-app/MiuiGallery
  extract_dir system/product/app/MiMediaEditor
fi
if config_enabled ThemeStore; then
  extract_dir system/product/app/ThemeManager
  extract_dir system/product/app/MIUIThemeStore
  extract_dir system/product/framework/miui-uninstall-empty.jar
  extract_dir system/product/etc/permissions/platform-miui-uninstall.xml
  extract_dir system/product/overlay/MiuiThemeManagerCnOverlay.apk
fi
config_enabled MiuiIme && extract_dir system/product/app/SogouIME
config_enabled SecurityIme && extract_dir system/product/app/MIUISecurityInputMethod
config_enabled Fonts && extract_dir system/product/fonts/MiSansVF.ttf
config_enabled CnXmsf && extract_dir system/product/app/XiaomiServiceFramework
config_enabled GlobalRoamingVirtualSim && extract_dir system/product/app/MIUIVirtualSim
if config_enabled AiCall && ! config_enabled XiaoAi; then
  extract_dir system/product/app/MIUIAiasstService
fi
if config_enabled NewCall; then
  extract_dir system/product/priv-app/NewCall
  extract_dir system/product/overlay/MiuiNewCallSdkResOverlay.apk
fi
config_enabled LockscreenMagazine && extract_dir system/product/app/MiGalleryLockscreen
config_enabled CarWith && extract_dir system/product/app/CarWith
if config_enabled MiuiPassword; then
  extract_dir system/product/app/MiuiPassword
  ui_print "- 保留系统已有密码管理后端及数据；本版不提供备用后端"
fi
if config_enabled DigitalKey; then
  extract_dir system/system_ext/app/digitalkey
  if ! config_enabled Wallet; then
    extract_dir system/product/app/MITSMClient
  fi
fi
config_enabled CnSearch && extract_dir system/product/priv-app/MIUIQuickSearchBox
config_enabled ThirdAppAssistant && extract_dir system/product/app/ThirdAppAssistant
if config_enabled ToolsBox; then
  extract_dir system/product/app/PuddingEUCompatibility
fi

marker_dir="$MODPATH/markers"
mkdir -p "$marker_dir"
for config_key in $FEATURE_KEYS $HOOK_KEYS; do
  make_marker "$config_key"
done

# Generate only properties required by selected components.
: > "$MODPATH/system.prop"
if any_enabled AppStore Wallet XiaoAi MmsYellowPage ContentRecognition PersonalAssistant CnSearch Calendar Weather Music Notes GallerySuite ThemeStore MiPush CnXmsf GlobalRoamingVirtualSim AiCall NewCall LockscreenMagazine CarWith MiuiPassword DigitalKey ThirdAppAssistant; then
  append_prop "ro.miui.region=CN"
  append_prop "ro.miui.mcc=9460"
  append_prop "ro.product.mod_device=xiaomieu"
fi
if any_enabled Wallet DigitalKey; then
  append_prop "ro.se.type=eSE,HCE,UICC"
fi
if any_enabled XiaoAi AiCall; then
  append_prop "ro.vendor.audio.aiasst.support=true"
fi
config_enabled MiuiIme && append_prop "ro.miui.support_miui_ime_bottom=1"
if config_enabled GboardTheme; then
  append_prop "ro.com.google.ime.theme_dir="
  append_prop "ro.com.google.ime.theme_file="
fi
if any_enabled MiPush CnXmsf; then
  append_prop "ro.miui.cust_variant=cn"
fi
if config_enabled VideoCallBeauty; then
  append_prop "persist.vendor.vcb.enable=true"
  append_prop "persist.vendor.vcb.ability=true"
fi
if config_enabled ToolsBox; then
  config_enabled ThemeStore && append_prop "ro.pudding.eu.feature.theme_store=1"
  config_enabled HookXSpace && append_prop "ro.pudding.eu.hook.xspace=1"
  config_enabled HookXSpaceSafety && append_prop "ro.pudding.eu.hook.xspace_safety=1"
  config_enabled HookFcm && append_prop "ro.pudding.eu.hook.fcm=1"
  config_enabled HookGoogleHome && append_prop "ro.pudding.eu.hook.google_home=1"
  config_enabled HookWidgets && append_prop "ro.pudding.eu.hook.widgets=1"
  config_enabled HookSystemUiIcons && append_prop "ro.pudding.eu.hook.systemui_icons=1"
  config_enabled NotificationFilter && append_prop "ro.pudding.eu.hook.notification_filter=1"
  config_enabled HookInternational && append_prop "ro.pudding.eu.hook.international=1"
fi

printf '%s\n' "OS3.0.319.0.WPCCNXM:6a445618e259bae015ec9c3d389cd8070b136de429a986cd649bae2f6330b459"   > "$MODPATH/source-build-id"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/ksu-mount.sh" 0 0 0755
set_perm "$MODPATH/ksu-mount-helper.dex" 0 0 0444
set_perm "$MODPATH/diagnostics.sh" 0 0 0755
set_perm "$MODPATH/lifecycle.sh" 0 0 0755
set_perm "$MODPATH/boot-tasks.sh" 0 0 0755
set_perm "$MODPATH/recovery.sh" 0 0 0755
set_perm "$MODPATH/package-records.sh" 0 0 0755
set_perm "$MODPATH/cache-recovery.sh" 0 0 0755
set_perm "$MODPATH/state-lock.sh" 0 0 0755
set_perm "$MODPATH/legacy-migrate.sh" 0 0 0755
set_perm "$MODPATH/virtualsim-state.sh" 0 0 0755
sh "$MODPATH/package-records.sh" install "$MODPATH" "${NVBASE:-/data/adb}" "$active_module_dir" \
  || abort_install "Unable to back up exact system package parse caches"
if [ "$selected_count" -gt 0 ] || [ -s "$MODPATH/cache-pending.tsv" ] \
    || [ -d "${NVBASE:-/data/adb}/pudding-eu-localization-state" ]; then
  sh "$MODPATH/lifecycle.sh" prepare "$MODPATH" "${NVBASE:-/data/adb}" \
    || abort_install "Unable to prepare reversible component/region state"
fi

ui_print ""
ui_print "- 已按选择解压 $selected_count 个组件"
[ "$selected_count" -eq 0 ] && ui_print "- 空配置：未挂载 APK/RRO，未写入功能属性"
config_enabled CnXmsf && ui_print "! CN XMSF 首次启动会重新注册推送"
config_enabled ToolsBox && ui_print "! LSPosed：启用 HyperOS_EU2CN 兼容层并勾选推荐作用域（含 KernelSU）"
config_enabled ToolsBox && ui_print "- 模块“操作”按音量+可在后台补齐挂载例外，无需保持亮屏"
ui_print "- 重启后可点击模块“操作”查看加载和签名切换状态"
ui_print "- APK 切换/记录错配只重建对应系统解析缓存；不清应用数据、不卸载手动更新"
ui_print "! 安装后须完整重启；请使用系统电源菜单的重启，不要选择 KernelSU 软重启/快捷重启"
config_enabled CnSearch && ui_print "- 桌面上滑：在桌面设置选择搜索，兼容层开启“桌面上滑使用国行搜索”"
ui_print "- KernelSU 需要 Magic Mount-rs 或等价挂载元模块"
