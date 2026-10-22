# 第三方组件与许可范围

## 项目代码

HyperOS_EU2CN 自写代码以 **GNU GPL version 3（GPL-3.0-only）** 提供，完整条款见 [LICENSE](LICENSE)。Copyright (C) 2026 Kirby Zhang and contributors。版权归相应贡献者。该授权不涵盖第三方 ROM、APK、字体、系统库、上游工具或它们的商标。

## 构建依赖与工具

| 组件 | 用途 | 许可／来源 |
| --- | --- | --- |
| Gradle Wrapper | 启动 Gradle 构建；仓库包含 wrapper JAR 和生成的启动脚本 | Gradle 项目，Apache-2.0；见 [Gradle 许可](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE) |
| Xposed API 82，`de.robv.android.xposed:api:82` | `compileOnly` API，运行时由框架提供 | rovo89，Apache-2.0（根据该版本 Maven POM）；见 [XposedBridge](https://github.com/rovo89/XposedBridge) |
| Android SDK / Android Gradle Plugin | Android 编译与打包工具 | Google / Android 项目各自条款；SDK 和工具缓存不随源码提交 |
| KernelSU、LSPosed、Zygisk 与挂载后端 | 运行环境和接口提供方 | 各上游项目各自许可；不是本项目自写代码，也不随模块作为这些工具的安装包分发 |

保留上游组件自身的版权和许可。GPL 不改变这些组件原有的许可条件。随源码保留 [Apache-2.0 全文](docs/licenses/Apache-2.0.txt) 和 Gradle 8.11.1 分发包中的 [NOTICE](docs/licenses/Gradle-NOTICE.txt)。

## ROM 载荷与外部 APK

模块包含从 Xiaomi / HyperOS ROM 提取或单独获取的应用、系统库及字体。主要 ROM 载荷基线为 pudding / Android 16 / OS3.0.319.0.WPCCNXM；目标实测环境为 HyperOS EU OS318。部分应用来自独立更新包，具体包名、版本、签名和 SHA256 见 `build/pudding-eu-localization/payload-manifest.tsv`。

这些载荷没有因此成为 GPL 软件，权利归相应权利人。仓库不提交这些二进制文件；源码清单和打包支持不表示授予第三方 APK、字体或 ROM 的再分发许可。发布者应依据各组件的真实条款确认分发条件；未取得的授权不作已取得声明。

独立更新包在本机原始材料中包含 MemeOSUpdates / HyperOSUpdates 等来源标记；这些标记只说明获取渠道，不代表权利人或授权证明。

项目与 Xiaomi、xiaomi.eu、Google、KernelSU、LSPosed 等上游没有因兼容关系而产生官方关联。
