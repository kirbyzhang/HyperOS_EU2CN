# 从源码构建

仓库包含兼容层、辅助 DEX、模块安装脚本和载荷清单。ROM、第三方 APK、字体、私钥、SDK 和缓存不提交到 Git。源码可构建自用 APK/DEX；没有原发行证书，无法生成与正式 APK 同签名的构建。

## 环境

- JDK 17。
- Python 3.11 或更新版本（打包与源码检查）。
- Android SDK Platform 35、Build Tools 35.0.0；通过 `ANDROID_HOME` 或本地 `local.properties` 指定 SDK。
- Gradle Wrapper 使用 8.11.1，Android Gradle Plugin 使用 8.10.1。
- 首次构建需要访问 Google Maven、Maven Central、Gradle 分发站和 Xposed API 仓库。
- 推荐在支持常规文件锁和权限的本地文件系统上构建。

```sh
export ANDROID_HOME=/path/to/android-sdk
sh toolsbox-hyperos3-pudding/gradlew -p toolsbox-hyperos3-pudding \
  :compat:assembleRelease :app:ksuMountHelper
```

兼容层输出：`toolsbox-hyperos3-pudding/compat/build/outputs/apk/release/compat-release-unsigned.apk`。

辅助 DEX 输出：`$GRADLE_USER_HOME/pudding-ksu-helper/dex/classes.dex`。如果没有设置 `GRADLE_USER_HOME`，wrapper 默认使用仓库内 `.cache/gradle/`。

`app/` 还包含保留的旧工具箱工程，当前正式兼容层复用其中代码；它不作为第二个兼容 APK 发布。历史 `weather/` 子工程不参与当前默认构建。

维护者本机如果存在私有 `.tools/env.sh` 和缓存挂载脚本，wrapper 保留现有 ext4 缓存流程；公开检出没有这些脚本时走普通构建路径，无需 root 或挂载镜像。

## 自用签名

使用自己的证书签名，私钥不要提交到仓库。SDK 的 `zipalign`、`apksigner` 可以完成对齐及签名。自签 APK 无法覆盖安装正式签名的 APK；请自行安排测试设备，不要为此删除正式版数据。发行用签名材料不公开。

## 打包完整模块

需要自行准备有权使用的载荷，目录结构和哈希以 `build/pudding-eu-localization/payload-manifest.tsv` 为准；不提供自动下载第三方 APK 的脚本。`system/` 下每项载荷必须与清单对应。

```sh
python3 scripts/package_module.py \
  --payload-root /path/to/payload-tree \
  --compat-apk /path/to/signed-compat.apk \
  --helper-dex /path/to/classes.dex \
  --apksigner "$ANDROID_HOME/build-tools/35.0.0/apksigner" \
  --aapt "$ANDROID_HOME/build-tools/35.0.0/aapt" \
  --output releases/local-build/HyperOS_EU2CN_OS318.0.1.zip
```

`--payload-root` 是包含 `system/` 的目录。脚本核对第三方载荷、兼容 APK 的包名/版本/签名和 DEX 文件，更新该次构建的兼容层清单数据，只打包允许的模块文件，不覆盖已有输出。加 `--verify-only` 可以只验证输入。

该脚本重新压缩模块，输出字节不保证与正式 ZIP 一致。自用构建也不等同于正式发行的实机验证。已经发布的同版本文件如需修改，应保留旧文件，并重新计算校验值及说明差异。

## 检查

```sh
python3 -m unittest discover -s tests -v
```

国际环境边界检查会使用 JDK 编译并执行源码中的策略类。完整的 Android 构建仍需单独执行上面的 Gradle 命令。
