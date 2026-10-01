# App 内更新

运行控制中点击“检查更新”，填写 HTTPS 更新说明地址。保存并检查后，可查看新版本说明、下载、校验并点击安装。首次安装需要在系统设置允许此应用安装未知来源应用，然后返回再次点击安装。更新覆盖安装，账号配置保留；不要先卸载旧版。

当前版本为 0.2.0，versionCode 为 2。旧版首次需手动安装本版，之后才能使用内置更新。

## GitHub 托管地址（当前）

- 检查更新地址（固定）：`https://xychenci182-web.github.io/MAA-Hotta/update.json`
- APK 下载（带版本号）：`https://github.com/xychenci182-web/MAA-Hotta/releases/download/v0.2.0/mah.apk`
- 仓库：`https://github.com/xychenci182-web/MAA-Hotta`

在 App 里把更新说明填成上面的固定 `update.json` 地址即可。

## 发布下一版

1. 修改 `app/build.gradle.kts`：递增 versionCode，更新 versionName。
2. 使用同一签名密钥构建 APK。当前安装包是 Debug 签名，后续须保留同一台构建机器的 debug.keystore；换签名不能直接覆盖。
3. 构建成功后生成发布文件（把版本号改成新的）：

```powershell
.\tools\prepare-update.ps1 -ApkUrl 'https://github.com/xychenci182-web/MAA-Hotta/releases/download/vX.Y.Z/mah.apk' -Notes '本次更新说明'
```

4. 上传安装包到 GitHub Release：

```powershell
gh release create vX.Y.Z dist/update/mah.apk --title "vX.Y.Z" --notes "本次更新说明"
```

5. 覆盖固定更新说明并推送：

```powershell
Copy-Item dist/update/update.json docs/updates/update.json -Force
git add docs/updates/update.json
git commit --trailer "Co-authored-by: Cursor <cursoragent@cursor.com>" -m "Bump update manifest to vX.Y.Z"
git push
```

6. 在 App 中配置固定更新说明地址，点击检查更新。

更新说明包含 versionCode、versionName、apkUrl、sha256、notes。脚本从 APK 构建元数据提取版本并计算 SHA-256，避免手填错误。App 拒绝非 HTTPS、降级、包名不符、哈希不符和签名不同的安装包。安装操作通过 Android 系统安装器由用户确认。

下载失败可以重新下载；进程被系统终止后需重新检查和下载，不提供断点续传或静默安装。