package com.aliothmoon.maahotta.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aliothmoon.maahotta.BuildConfig
import com.aliothmoon.maahotta.data.AppUpdate
import com.aliothmoon.maahotta.data.AppUpdater
import com.aliothmoon.maahotta.data.ConfigStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ConfigStore(app)
    var address by mutableStateOf("")
    var loaded by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var update by mutableStateOf<AppUpdate?>(null)
    private var apk: File? = null
    var ready by mutableStateOf(false)
    var progress by mutableIntStateOf(-1)

    init { viewModelScope.launch { address = store.config.first().updateUrl; loaded = true } }

    fun editAddress(value: String) {
        address = value
        update = null
        ready = false
        apk = null
        message = ""
    }

    fun check() {
        if (busy) return
        busy = true
        update = null
        ready = false
        apk = null
        message = "正在检查更新…"
        viewModelScope.launch {
            try {
                val url = address.trim()
                require(url.isNotEmpty()) { "请先填写更新说明地址" }
                val result = AppUpdater.check(url)
                store.update { it.copy(updateUrl = url) }
                update = result.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                message = if (update == null) "当前已是最新版本" else "发现新版本 ${result.versionName}"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message = "检查失败：${e.message ?: "网络异常"}"
            } finally { busy = false }
        }
    }

    fun download() {
        val release = update ?: return
        if (busy) return
        busy = true
        ready = false
        progress = -1
        message = "正在下载…"
        viewModelScope.launch {
            try {
                apk = AppUpdater.download(getApplication(), release) { value ->
                    viewModelScope.launch { progress = value }
                }
                ready = true
                message = "下载及校验完成，请点击安装"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message = "下载失败：${e.message ?: "网络异常"}"
            } finally { busy = false }
        }
    }

    fun install() {
        val file = apk ?: return
        try {
            message = if (AppUpdater.install(getApplication(), file)) "已打开系统安装界面"
                else "请允许安装此来源的应用，返回后再次点击安装"
        } catch (e: Exception) { message = "无法安装：${e.message ?: "系统安装器不可用"}" }
    }
}

@Composable
fun AppUpdateControl(running: Boolean) {
    val vm: AppUpdateViewModel = viewModel()
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Text("检查更新 · ${BuildConfig.VERSION_NAME}")
    }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("应用更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                OutlinedTextField(value = vm.address, onValueChange = vm::editAddress,
                    enabled = vm.loaded && !vm.busy, singleLine = true,
                    label = { Text("更新说明地址（HTTPS）") },
                    placeholder = { Text("https://你的服务器/update.json") })
                TextButton(onClick = vm::check, enabled = vm.loaded && !vm.busy) { Text("保存并检查更新") }
                if (vm.message.isNotBlank()) Text(vm.message)
                if (vm.busy && vm.progress >= 0) Text("下载进度 ${vm.progress}%")
                vm.update?.let { release ->
                    Text(release.notes.ifBlank { "暂无更新说明" })
                    if (running) Text("请先停止日常任务，再安装更新")
                    Button(onClick = { if (vm.ready) vm.install() else vm.download() },
                        enabled = !vm.busy && !running) {
                        Text(if (vm.ready) "安装更新" else "下载更新")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { show = false }) { Text("关闭") } },
    )
}
