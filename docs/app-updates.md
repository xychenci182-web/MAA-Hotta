# 应用更新发布

更新说明入口为 `https://xychenci182-web.github.io/MAA-Hotta/update.json`。
GitHub Pages 从 `main` 分支的 `/docs` 目录发布；`docs/update.json` 与
`docs/updates/update.json` 必须来自同一份生成清单。

公开 APK 不打包本机 `default_accounts.txt`。该文件只进入本机 `local` 构建；
debug 与 release 会排除它。`tools/prepare-update.ps1` 发现待发布包里仍有这个文件会直接中止。
账号、密码和 Bark Device Key 保存在设备本机；覆盖安装保留已有应用配置。首次安装需要自行添加账号和 Bark 地址。

发布顺序：

1. 递增 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`。
2. 构建并检查新 APK 的包名、版本和签名。签名必须与上一发布版本一致。
3. 从当前构建目录生成发布包与清单；输出可以指定其他磁盘。

```powershell
.\tools\prepare-update.ps1 `
    -ApkDirectory 'G:\MAA-Hotta-build\app\outputs\apk\debug' `
    -OutputDirectory 'G:\MAA-Hotta-release' `
    -ApkUrl 'https://ghproxy.net/https://github.com/xychenci182-web/MAA-Hotta/releases/download/vX.Y.Z/mah.apk' `
    -Notes '本次更新说明'
```

4. 提交并推送源码，把 `mah.apk` 上传到对应 GitHub Release。
   先验证 Release 下载文件的 SHA-256 与生成清单一致，再切换更新入口。
5. 将生成的 `update.json` 同步复制到两份 `docs` 清单并提交推送。
6. 等待 GitHub Pages 发布成功，确认线上两份清单一致；核对清单实际 APK 地址能够下载。

应用会校验 HTTPS、APK SHA-256、包名、递增版本号和签名后才允许安装。
如果镜像暂未同步，可从 GitHub Release 下载并覆盖安装，保留原有账号与 Bark 配置。