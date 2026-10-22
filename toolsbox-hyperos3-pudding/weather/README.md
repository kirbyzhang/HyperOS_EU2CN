# 天气高德定位兼容

独立 LSPosed 插件 `local.pudding.weathercompat`，仅推荐作用域 `com.miui.core`。保留旧管理 APK、本地化模块及所有应用更新，不依赖旧管理 APK 的签名私钥。

针对已核对的 EU318 XmsCore `1.20.2.5-32`：在 `Weather2LocationProvider.call(String, String, Bundle)` 中，只在真实调用者为 `com.miui.weather2`、操作为 `locationFromNlp` 且开关开启时，把操作改为 `locationFromAmap`。参数、原有调用者验证及结果处理继续由原 provider 执行。不改地区属性、系统定位绑定或权限，不清应用数据。

开关使用 LSPosed 新版 XSharedPreferences（manifest xposedminversion 93、xposedsharedprefs true）。默认开启，LSPosed 模块本身仍需启用。每次定位调用重新读取开关；关闭后后续请求恢复 NLP，已开始的请求继续完成。

首次安装/启用须重新启动小米系统服务使 Hook 注入。日志只记录请求是否重定向及结果是否包含非空位置，不记录坐标、地址或 JSON。高德 SDK 仍可能受网络、授权、权限等因素影响，须实机验证。

构建：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --no-daemon :weather:assembleRelease :weather:lintRelease`。

隔离检查：用 javac 编译 `WeatherRequestRouter.java` 和 `tests/WeatherRequestRouterTest.java`，执行 `java -ea WeatherRequestRouterTest`。
