# 配置与国际环境选项

安装器优先读取手机 `Download/EU2CN_Config.ini`。这是安装选择，不是兼容层里的应用开关；修改后需要重新安装模块。`ToolsBox=true` 才会安装兼容层并生成 Hook 选项对应的属性，之后仍需在 LSPosed 启用推荐作用域。

## 国际环境不是一个总依赖

以下结论来自 `InternationalPolicy.java`、`MainModule.java` 和安装器的实际条件分支。

| 配置键 | 作用范围 | 公开模板 | 与其他配置的关系 |
| --- | --- | --- | --- |
| `HookXSpaceSafety` | `com.miui.securitycore` 双开组件内的国际环境标志 | `true` | 与扩展双开列表的 `HookXSpace` 分开；不要求 `HookInternational=true` |
| `HookGoogleHome` | `com.miui.home` 桌面内的国际环境标志 | `true` | 保持桌面 Google 相关路径的兼容；不要求旧国际环境总开关 |
| `HookFcm` | 系统框架、PowerKeeper、MiuiCore 的国际环境兼容 | `false` | 独立实验选项；不是 CN XMSF 或桌面搜索的安装依赖 |
| `HookInternational` | 旧方式，覆盖上述五个目标进程中的环境修复 | `false` | 与局部选项部分重叠；通常保留关闭，不必为了使用其他功能而开启 |
| `CnXmsf` | 安装国行小米服务框架 | `false` | 与 Hook 开关分开；切换后可能重新注册小米推送，不等同于 FCM 兼容 |
| `MiPush` | 推送区域相关设置 | `false` | 与替换完整 CN XMSF 分开，可独立选择 |

这里的 `true` 是对应组件进程内的国际环境标志，不会把整台手机切换为国际版或国行版。

## 常见功能依赖

| 功能 | 安装选择与实际条件 |
| --- | --- |
| 扩展双开列表 | `ToolsBox=true`、`HookXSpace=true`，并启用 SecurityCore 作用域；建议保留 `HookXSpaceSafety=true` |
| 桌面上滑国行搜索 | `CnSearch=true`、兼容层、桌面作用域，以及兼容层中启用国行搜索；现有明确的手势选择优先。搜索通过定向方法 Hook 实现，不要求 `HookInternational=true` |
| 小米小组件 | `ToolsBox=true`、`HookWidgets=true`、桌面作用域；与旧国际环境总开关独立 |
| 天气、主题、黄页、电话的国行环境 | 对应应用和兼容层作用域，以及兼容层中的应用开关；通过各自进程内的国行环境适配实现，不依赖 `HookInternational` |
| KernelSU 后台挂载例外 | 兼容层及其 KernelSU 作用域；与上述国际环境选项无依赖，不需要授予这些系统应用 Root |

`HookGoogleHome` 与国行搜索并不矛盾：前者维持桌面总体环境，后者只处理搜索手势和搜索提供者。

公开模板的普通安装组件全部为 `false`，由用户选择。保留五个原有兼容子项默认值：`HookXSpace`、`HookXSpaceSafety`、`HookGoogleHome`、`HookWidgets`、`HookSystemUiIcons`。当 `ToolsBox=false` 时，这些子项不生效。普通“一键全选”也不会自动开启 `CnXmsf`、`HookFcm`、`HookInternational`。

这些是代码中的依赖边界，不代表所有 ROM、账号或实际业务均已验收。支付、推送到达、车钥匙等仍取决于具体环境。
