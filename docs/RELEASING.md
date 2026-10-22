# GitHub 发布

仓库保存源码和说明；完整模块 ZIP、独立 APK、配置模板及校验文件作为 GitHub Release 附件。模块体积约 1.52 GB，不提交到 Git 历史。

## 本次附件

均位于 `releases/OS318.0.1/`：

- `HyperOS_EU2CN_OS318.0.1.zip`
- `HyperOS_EU2CN_Compat_OS318.0.1.apk`
- `EU2CN_Config.ini`
- `README.md`
- `VALIDATION.md`
- `LICENSE`
- `THIRD_PARTY_NOTICES.md`
- `SHA256SUMS`

发布说明使用同目录的 `RELEASE_NOTES.md`。`BUILD_INFO.json`、历史候选、原始日志和私有全开配置不作为附件。

```sh
cd releases/OS318.0.1
sha256sum -c SHA256SUMS
```

源码标签建议为 `OS318.0.1`。GitHub 仓库名称为 `HyperOS_EU2CN`。创建远端、首次推送、创建标签和发布 Release 是独立的对外操作；本地整理不会替你执行这些操作。

## 发布前核对

核对 Git 暂存名单只包含白名单源码、脚本及文档；私钥、ADB 身份、日志、SDK、缓存和 ROM 不应出现。确认 Release 附件中的模板与 ZIP 内模板一致，校验文件包含将要上传的附件。核对第三方载荷的实际分发条件，项目 GPL 许可不替代第三方授权。

不要在同一个已公开的标签下悄悄替换不同内容的同名文件。后续变更应有清楚的版本和校验记录。
