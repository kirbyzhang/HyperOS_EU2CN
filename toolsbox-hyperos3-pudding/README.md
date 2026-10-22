# HyperOS_EU2CN 源码

项目说明、安装方法和功能范围见项目根目录 `README.md`。

- `compat/`：当前发布的“HyperOS_EU2CN 兼容层”，版本 OS318.0.1 / 31801。
- `app/src/main/java/`：兼容层复用的功能、维护工具和诊断代码。
- `app/`：保留的旧工具箱工程，同时承载独立挂载辅助 DEX 构建任务；不作为第二个兼容 APK 发布。
- `ksu-helper/`：生成实际安装组件的 UID 计划、在后台发起挂载例外配置并逐项核验；保留旧界面辅助方式。
- `ksu-shared/`：后台桥协议校验与配置复制逻辑，由兼容 APK 和模块辅助 DEX 共用。

使用本目录 `gradlew` 构建，项目内的 SDK、Gradle 缓存与临时目录由 wrapper 设置。兼容层构建任务为 `:compat:assembleRelease`，挂载辅助程序任务为 `:app:ksuMountHelper`。发行构建使用原有签名；签名材料保存在项目私有目录，不随源码或发行包发布。

为兼容已安装版本，兼容层包名、偏好名称和模块内 APK 路径保留；用户可见名称统一为 HyperOS_EU2CN。本目录名是既有构建路径，不代表发行项目名称。
