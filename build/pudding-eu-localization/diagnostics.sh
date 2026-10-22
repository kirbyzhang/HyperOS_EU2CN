#!/system/bin/sh

diag_package() {
  label="$1"
  package_name="$2"
  expected="$3"
  package_paths="$(pm path "$package_name" 2>/dev/null)"
  if [ -z "$package_paths" ]; then
    echo "pkg.$package_name=MISSING ($label; expected $expected)"
    return
  fi
  # Filter on the stream: passing a full package dump to Android's external
  # printf can exceed ARG_MAX (especially Gallery) and corrupt diagnostics.
  package_metadata="$(dumpsys package "$package_name" 2>/dev/null | awk '
    /^[[:space:]]*versionName=/ && !name_seen++ {sub(/^[[:space:]]*versionName=/, ""); print "name=" $0}
    /^[[:space:]]*versionCode=/ && !code_seen++ {sub(/^[[:space:]]*versionCode=/, ""); sub(/ .*/, ""); print "code=" $0}
  ')"
  version_name="$(printf '%s\n' "$package_metadata" | sed -n 's/^name=//p')"
  version_code="$(printf '%s\n' "$package_metadata" | sed -n 's/^code=//p')"
  if printf '%s\n' "$package_paths" | grep -q '^package:/data/app/'; then
    status=USER_UPDATE
  elif [ -z "$version_name" ]; then
    status=VERSION_UNKNOWN
  else
    case "$version_name" in
      *"$expected"*) status=OK ;;
      *) status=VERSION_MISMATCH ;;
    esac
  fi
  echo "pkg.$package_name=$status label=$label version=$version_name code=$version_code expected=$expected"
  printf '%s\n' "$package_paths" | sed "s|^|path.$package_name=|"
}

diag_marker_package() {
  marker="$1"
  label="$2"
  package_name="$3"
  expected="$4"
  [ -f "$MARKERS/$marker" ] && diag_package "$label" "$package_name" "$expected"
}

diag_selected_packages() {
  diag_marker_package AppStore "GetApps" com.xiaomi.market 4.116.1
  if [ -f "$MARKERS/Wallet" ]; then
    diag_package "小米钱包" com.mipay.wallet 6.114.0.5756.2747
    diag_package "TSMClient" com.miui.tsmclient 26.06.16.1.f
    diag_package "NextPay" com.miui.nextpay 26.01.30.1
    diag_package "银联 TSM" com.unionpay.tsmservice.mi 01.00.57
    diag_package "PaymentService" com.xiaomi.payment 2.9.8
  fi
  if [ -f "$MARKERS/XiaoAi" ]; then
    diag_package "小爱同学" com.miui.voiceassist 7.12.2.0318
    diag_package "语音唤醒" com.miui.voicetrigger v-8.0.0.29-xiaomi
    diag_package "小爱推荐" com.xiaomi.aireco 0.1.6
    diag_package "小爱语音引擎" com.xiaomi.mibrain.speech 1.6.0
    diag_package "小爱 Proxy" com.miui.voiceassistProxy 1.5.0
    diag_package "AI 助理服务" com.xiaomi.aiasst.service 6.0.3
  fi
  if [ -f "$MARKERS/MmsYellowPage" ]; then
    diag_package "国行短信" com.android.mms 17.0.2.35
    diag_package "黄页" com.miui.yellowpage 23.0.260506
  fi
  if [ -f "$MARKERS/ContentRecognition" ]; then
    diag_package "ContentCatcher" com.miui.contentcatcher 4.1.7
    diag_package "传送门" com.miui.contentextension 4.1.9
    diag_package "快应用框架" com.miui.hybrid 1.30.7.3
  fi
  diag_marker_package PersonalAssistant "负一屏" com.miui.personalassistant 25.30.79
  diag_marker_package CnSearch "国行搜索" com.android.quicksearchbox 12.2.0.061011
  diag_marker_package Calendar "日历" com.android.calendar 17.10.8.13
  diag_marker_package Weather "天气" com.miui.weather2 17.1.0.23
  diag_marker_package Music "小米音乐" com.miui.player 4.45.0.1
  diag_marker_package SoundRecorder "录音机" com.android.soundrecorder 7.8.9.3
  diag_marker_package Notes "便签" com.miui.notes 2.4.0.9
  if [ -f "$MARKERS/GallerySuite" ]; then
    diag_package "相册" com.miui.gallery 5.0.6.6
    diag_package "MediaEditor" com.miui.mediaeditor 2.3.0.8.3
  fi
  if [ -f "$MARKERS/ThemeStore" ]; then
    diag_package "主题管理器" com.android.thememanager 10.8.0.0
    diag_package "主题商店" com.miui.themestore 23.0
  fi
  diag_marker_package MiuiIme "小米搜狗输入法" com.sohu.inputmethod.sogou.xiaomi 10.32.21
  diag_marker_package SecurityIme "小米安全键盘" com.miui.securityinputmethod 3.8.9
  diag_marker_package GlobalRoamingVirtualSim "全球上网/虚拟 SIM" com.miui.virtualsim 9.1.2.0
  diag_marker_package AiCall "AI 通话服务" com.xiaomi.aiasst.service 6.0.3
  diag_marker_package NewCall "NewCall" com.newcall 1.0.11
  diag_marker_package LockscreenMagazine "锁屏画报" com.mfashiongallery.emag 25100241
  diag_marker_package CarWith "CarWith" com.miui.carlink 4.0.1
  diag_marker_package MiuiPassword "小米密码管理" com.miui.password 1.0.15
  diag_marker_package MiuiPassword "小米密码管理后端（保留已安装版本）" com.miui.passwords ""
  if [ -f "$MARKERS/DigitalKey" ]; then
    diag_package "数字车钥匙" com.xiaomi.digitalkey 13.0.47
    diag_package "数字车钥匙 TSM 依赖" com.miui.tsmclient 26.06.16.1.f
  fi
  diag_marker_package ThirdAppAssistant "智能应用助手" com.miui.thirdappassistant 5.9
  diag_marker_package CnXmsf "CN XMSF" com.xiaomi.xmsf 7.5.19-C
  diag_marker_package ToolsBox "HyperOS_EU2CN 兼容层" local.pudding.weathercompat OS318.0.1
  diag_native_libraries
}

diag_native_libraries() {
  native_manifest="$MODDIR/native-library-manifest.tsv"
  [ -f "$native_manifest" ] || return
  native_apps="$(awk -F '\t' 'NR > 1 && !seen[$1]++ {print $1 " " $2}' "$native_manifest")"
  while read -r native_package native_apk; do
    [ -n "$native_package" ] || continue
    [ -f "$MODDIR/$native_apk" ] || continue
    native_paths="$(pm path "$native_package" 2>/dev/null)"
    if [ -z "$native_paths" ]; then
      echo "native.$native_package=PACKAGE_MISSING"
      continue
    fi
    if printf '%s\n' "$native_paths" | grep -q '^package:/data/app/'; then
      echo "native.$native_package=USER_UPDATE (native libraries managed by Android installer)"
      continue
    fi
    native_root="$(dumpsys package "$native_package" 2>/dev/null | sed -n 's/^[[:space:]]*legacyNativeLibraryDir=//p' | head -n 1)"
    native_missing=0
    native_total=0
    native_missing_names=""
    for native_name in $(awk -F '\t' -v p="$native_package" 'NR > 1 && $1 == p {print $3}' "$native_manifest"); do
      native_total=$((native_total + 1))
      if [ -z "$native_root" ] || { [ ! -f "$native_root/arm64/$native_name" ] && [ ! -f "$native_root/$native_name" ]; }; then
        native_missing=$((native_missing + 1))
        native_missing_names="$native_missing_names $native_name"
      fi
    done
    if [ "$native_missing" -gt 0 ]; then
      echo "native.$native_package=MISSING missing=$native_missing total=$native_total root=$native_root names=$native_missing_names"
    else
      echo "native.$native_package=FILES_PRESENT total=$native_total root=$native_root (not a runtime/ABI test)"
    fi
  done <<EOF
$native_apps
EOF
}

diag_overlays() {
  for overlay_marker in FocusXms XiaoAi ThemeStore NewCall; do
    [ -f "$MARKERS/$overlay_marker" ] || continue
    case "$overlay_marker" in
      FocusXms) overlay_name=pudding.focus.xms.overlay ;;
      XiaoAi) overlay_name=com.miui.voiceassistoverlay ;;
      ThemeStore) overlay_name=com.android.thememanager.customthemeconfig.config.overlay ;;
      NewCall) overlay_name=com.newcall.overlay.miui ;;
    esac
    overlay_line="$(cmd overlay list 2>/dev/null | grep -F "$overlay_name" | head -n 1)"
    [ -n "$overlay_line" ] || overlay_line=MISSING
    if [ "$overlay_marker" = ThemeStore ] && [ "$overlay_line" != MISSING ]; then
      # The original CN APK has a 40-byte resource table with zero packages.
      # It cannot map target resources; NO_IDMAP alone is not a defect here.
      echo "overlay.$overlay_name=EMPTY_RESOURCE_CONFIG_APK ($overlay_line; original CN package retained)"
      if cmd package list libraries 2>/dev/null | grep -q '^library:miui-uninstall-empty.jar$'; then
        echo "library.miui-uninstall-empty.jar=REGISTERED"
      else
        echo "library.miui-uninstall-empty.jar=MISSING"
      fi
    else
      echo "overlay.$overlay_name=$overlay_line"
    fi
  done
}

diag_hooks() {
  [ -f "$MARKERS/ToolsBox" ] || return
  for hook_name in xspace xspace_safety fcm google_home widgets systemui_icons notification_filter international; do
    echo "hook.$hook_name=$(getprop "ro.pudding.eu.hook.$hook_name")"
  done
}

diag_environment() {
  echo "checked=$(date '+%F %T')"
  echo "device=$(getprop ro.product.device)"
  echo "build=$(getprop ro.product.build.version.incremental)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  echo "boot_completed=$(getprop sys.boot_completed)"
  echo "region=$(getprop ro.miui.region)"
  echo "cust_variant=$(getprop ro.miui.cust_variant)"
  echo "se_type=$(getprop ro.se.type)"
  if grep -q 'HyperOS_EU2CN' /proc/1/mountinfo 2>/dev/null; then
    echo "mount.init_namespace=VISIBLE"
  else
    echo "mount.init_namespace=NOT_IDENTIFIED"
  fi
}
