# App 内更新

运行控制中点击“检查更新”，填写 HTTPS 更新说明地址（例如服务器上的 `update.json`）。保存并检查后，可查看新版本说明、下载、校验并点击安装。首次安装需要在系统设置允许此应用安装未知来源应用，然后返回再次点击安装。更新覆盖安装，账号配置保留；不要先卸载旧版。

当前版本为 0.2.0，versionCode 为 2。旧版首次需手动安装本版，之后才能使用内置更新。此仓库不自带远程服务器，需自行提供 HTTPS 文件托管地址。

## 发布下一版

1. 修改 `app/build.gradle.kts`：递增 versionCode，更新 versionName。
2. 使用同一签名密钥构建 APK。当前安装包是 Debug 签名，后续须保留同一台构建机器的 debug.keystore；换签名不能直接覆盖。
3. 构建成功后生成发布文件：

```powershell
.\tools\prepare-update.ps1 -ApkUrl 'https://你的服务器/mah.apk' -Notes '本次更新说明'
```

4. 将 `dist/update/mah.apk` 上传至指定下载地址，再上传 `dist/update/update.json`。APK 建议使用带版本号的独立地址，更新说明使用固定地址并禁用长期缓存。
5. 在 App 中配置更新说明的 HTTPS 地址，点击检查更新。

更新说明包含 versionCode、versionName、apkUrl、sha256、notes。脚本从 APK 构建元数据提取版本并计算 SHA-256，避免手填错误。App 拒绝非 HTTPS、降级、包名不符、哈希不符和签名不同的安装包。安装操作通过 Android 系统安装器由用户确认。

下载失败可以重新下载；进程被系统终止后需重新检查和下载，不提供断点续传或静默安装。
