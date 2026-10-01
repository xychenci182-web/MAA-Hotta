package com.aliothmoon.maahotta.ui

import android.provider.Settings
import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aliothmoon.maahotta.data.DailyTask
import com.aliothmoon.maahotta.data.AutoStartSchedule
import com.aliothmoon.maahotta.data.GameAccount
import com.aliothmoon.maahotta.data.TaskOptions
import com.aliothmoon.maahotta.data.TrialType
import com.aliothmoon.maahotta.data.isEnabled
import com.aliothmoon.maahotta.data.orderedDailyTasks
import com.aliothmoon.maahotta.runtime.HottaAccessibilityService
import com.aliothmoon.maahotta.scheduler.AutoStartScheduler
import com.aliothmoon.maahotta.ui.theme.MahCyan
import com.aliothmoon.maahotta.ui.theme.MahGold
import com.aliothmoon.maahotta.ui.theme.MahGreen
import com.aliothmoon.maahotta.ui.theme.MahOutline
import com.aliothmoon.maahotta.ui.theme.MahRed
import com.aliothmoon.maahotta.ui.theme.MahSurface
import com.aliothmoon.maahotta.ui.theme.MahSurfaceHigh
import com.aliothmoon.maahotta.ui.theme.MahTextMuted
import rikka.shizuku.Shizuku
import java.util.UUID

private enum class MainTab(val label: String, val icon: ImageVector) {
    RUN("运行", Icons.Default.PlayArrow),
    LOG("日志", Icons.Default.FormatListBulleted),
    ACCOUNT("账号", Icons.Default.PersonOutline),
    TASK("任务", Icons.Default.TaskAlt),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HottaRoot(
    autoStartRequest: Int = 0,
    vm: HottaViewModel = viewModel(),
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAccessibilityPrompt by remember {
        mutableStateOf(!HottaAccessibilityService.isConnected())
    }
    val run by vm.run.collectAsState()
    val compactHeight = LocalConfiguration.current.screenHeightDp < 500

    LaunchedEffect(autoStartRequest) {
        if (autoStartRequest > 0) vm.startScheduledDaily()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color.White, Color(0xFFF7FAFD), Color(0xFFEDF4FA)),
                ),
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { MahHeader(run.running, compactHeight) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = if (compactHeight) 12.dp else 18.dp),
            ) {
                MahTabBar(selectedTab, compactHeight) { selectedTab = it }
                Spacer(Modifier.height(if (compactHeight) 8.dp else 12.dp))
                when (selectedTab) {
                    0 -> RunPane(vm, run)
                    1 -> LogCard(run, Modifier.fillMaxSize().padding(bottom = 8.dp))
                    2 -> AccountPane(vm, run.running)
                    else -> TaskPane(vm)
                }
            }
        }
    }
    if (showAccessibilityPrompt && !HottaAccessibilityService.isConnected()) {
        AlertDialog(
            onDismissRequest = { showAccessibilityPrompt = false },
            title = { Text("先开启无障碍服务") },
            text = { Text("运行前需要用无障碍服务获取游戏画面并点击。请在系统设置中开启 MAH 的无障碍服务，返回后再开始任务。") },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityPrompt = false
                    vm.openAccessibilitySettings()
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityPrompt = false }) { Text("稍后") }
            },
        )
    }
}

@Composable
private fun MahHeader(running: Boolean, compact: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 52.dp else 68.dp)
            .padding(horizontal = if (compact) 12.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 34.dp else 42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(MahCyan, Color(0xFF6A8DFF)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "M",
                color = Color(0xFF04111C),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column {
            Text("MAH", style = MaterialTheme.typography.titleLarge)
            if (!compact) {
                Text("幻塔日常助手", color = MahTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.weight(1f))
        StatusPill(
            text = if (running) "任务运行中" else "等待启动",
            active = running,
        )
    }
}

@Composable
private fun MahTabBar(selected: Int, compact: Boolean, onSelected: (Int) -> Unit) {
    Surface(
        color = MahSurface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MahOutline.copy(alpha = 0.72f)),
    ) {
        Row(Modifier.padding(4.dp)) {
            MainTab.entries.forEachIndexed { index, tab ->
                val isSelected = selected == index
                Surface(
                    onClick = { onSelected(index) },
                    modifier = Modifier.weight(1f),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                            modifier = Modifier.padding(vertical = if (compact) 6.dp else 9.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isSelected) MahCyan else MahTextMuted,
                        )
                        Spacer(Modifier.width(if (compact) 4.dp else 7.dp))
                        Text(
                            tab.label,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MahTextMuted,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
    val tint = if (active) MahGreen else MahTextMuted
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = CircleShape,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(7.dp))
            Text(text, color = tint, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RunPane(vm: HottaViewModel, run: RunUiState) {
    val config by vm.config.collectAsState()
    val overlayOk = Settings.canDrawOverlays(LocalContext.current)
    val accessibilityOk = HottaAccessibilityService.isConnected()
    val shizukuOk = Shizuku.pingBinder()
    val enabledAccounts = config.accounts.count { it.enabled }
    val enabledTasks = config.options.orderedDailyTasks().count { config.options.isEnabled(it) } +
        if (config.options.login) 1 else 0

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // At 1280×720 / 320 dpi the window is only about 640×360 dp.
        // A fixed-height control card would otherwise push logs off screen.
        val wide = maxWidth >= 560.dp
        val compact = maxHeight < 500.dp
        val stackedControlHeight = minOf(475.dp, maxHeight * 0.52f)
        if (wide) {
            Row(
                modifier = Modifier.fillMaxSize().padding(bottom = if (compact) 8.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
            ) {
                RunControlCard(
                    vm = vm,
                    run = run,
                    overlayOk = overlayOk,
                    accessibilityOk = accessibilityOk,
                    shizukuOk = shizukuOk,
                    enabledAccounts = enabledAccounts,
                    enabledTasks = enabledTasks,
                    schedule = config.autoStartSchedule,
                    keepAliveEnabled = config.keepAliveEnabled,
                    barkPushEnabled = config.barkPushEnabled,
                    modifier = Modifier.weight(0.48f).fillMaxHeight(),
                )
                LogCard(run, Modifier.weight(0.52f).fillMaxHeight())
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(bottom = if (compact) 8.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
            ) {
                RunControlCard(
                    vm = vm,
                    run = run,
                    overlayOk = overlayOk,
                    accessibilityOk = accessibilityOk,
                    shizukuOk = shizukuOk,
                    enabledAccounts = enabledAccounts,
                    enabledTasks = enabledTasks,
                    schedule = config.autoStartSchedule,
                    keepAliveEnabled = config.keepAliveEnabled,
                    barkPushEnabled = config.barkPushEnabled,
                    modifier = Modifier.fillMaxWidth().height(stackedControlHeight),
                )
                LogCard(run, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun RunControlCard(
    vm: HottaViewModel,
    run: RunUiState,
    overlayOk: Boolean,
    accessibilityOk: Boolean,
    shizukuOk: Boolean,
    enabledAccounts: Int,
    enabledTasks: Int,
    schedule: AutoStartSchedule,
    keepAliveEnabled: Boolean,
    barkPushEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    ConsoleCard(modifier, scrollable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("运行控制", style = MaterialTheme.typography.titleMedium)
                Text("检查环境后启动已启用账号的日常任务", color = MahTextMuted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::checkGameCapture, enabled = !run.running) {
                Text("检查画面")
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = vm::startDaily,
                enabled = !run.running,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(7.dp))
                Text("开始日常")
            }
            OutlinedButton(
                onClick = vm::stop,
                enabled = run.running,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MahRed),
                border = BorderStroke(1.dp, if (run.running) MahRed.copy(alpha = 0.6f) else MahOutline),
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(5.dp))
                Text("停止")
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("账号", enabledAccounts.toString(), Modifier.weight(1f))
            MetricTile("任务", enabledTasks.toString(), Modifier.weight(1f))
            MetricTile("后端", run.backend, Modifier.weight(1.35f))
        }

        Spacer(Modifier.height(12.dp))
        EnvironmentRow("无障碍服务", accessibilityOk, Icons.Default.SettingsAccessibility)
        EnvironmentRow("Shizuku", shizukuOk, Icons.Default.WifiTethering)
        EnvironmentRow("悬浮窗权限", overlayOk, Icons.Default.Tune)

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::openAccessibilitySettings,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) { Text("无障碍") }
            OutlinedButton(
                onClick = vm::refreshBackend,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.Sync, null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("授权")
            }
            OutlinedButton(
                onClick = if (overlayOk) vm::startOverlay else vm::openOverlaySettings,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) { Text(if (overlayOk) "悬浮窗" else "去授权") }
        }

        Spacer(Modifier.height(8.dp))
        AutoStartControl(vm, schedule)

        Spacer(Modifier.height(8.dp))
        KeepAliveControl(vm, keepAliveEnabled)

        Spacer(Modifier.height(8.dp))
        BarkPushControl(vm, barkPushEnabled)
        Spacer(Modifier.height(8.dp))
        AppUpdateControl(run.running)

        if (run.lastSummary.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) {
                Text(
                    run.lastSummary,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BarkPushControl(vm: HottaViewModel, enabled: Boolean) {
    Surface(
        color = MahSurfaceHigh,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MahOutline.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Bark 推送", style = MaterialTheme.typography.bodyMedium)
                Text("失败时通知；全部成功后汇总推送老头账号", color = MahTextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = vm::updateBarkPush)
        }
    }
}

@Composable
private fun KeepAliveControl(vm: HottaViewModel, enabled: Boolean) {
    Surface(
        color = MahSurfaceHigh,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MahOutline.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PowerSettingsNew, null, tint = MahCyan, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("后台保活", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (enabled) "常驻通知已开启，退出主界面后继续运行" else "当前未启用",
                    color = MahTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = vm::updateKeepAlive)
        }
    }
}

@Composable
private fun AutoStartControl(vm: HottaViewModel, schedule: AutoStartSchedule) {
    val context = LocalContext.current
    val exactAllowed = AutoStartScheduler.canScheduleExact(context)
    Surface(
        color = MahSurfaceHigh,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MahOutline.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Schedule, null, tint = MahCyan, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("定时开始日常", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (schedule.enabled) {
                        "每天 %02d:%02d 自动打开 MAH，并启用全部账号".format(schedule.hour, schedule.minute)
                    } else {
                        "当前未启用"
                    },
                    color = if (schedule.enabled && !exactAllowed) MahGold else MahTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            vm.updateAutoStartSchedule(schedule.copy(hour = hour, minute = minute))
                        },
                        schedule.hour,
                        schedule.minute,
                        true,
                    ).show()
                },
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text("%02d:%02d".format(schedule.hour, schedule.minute))
            }
            if (schedule.enabled && !exactAllowed) {
                TextButton(onClick = vm::openExactAlarmSettings) { Text("授权准点") }
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = {
                    vm.updateAutoStartSchedule(schedule.copy(enabled = it))
                },
            )
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MahSurfaceHigh,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MahOutline.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, color = MahTextMuted, style = MaterialTheme.typography.bodySmall)
            Text(
                value,
                color = MahCyan,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EnvironmentRow(label: String, ready: Boolean, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (ready) MahGreen else MahTextMuted)
        Spacer(Modifier.width(9.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (ready) "已就绪" else "未连接",
            color = if (ready) MahGreen else MahGold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LogCard(run: RunUiState, modifier: Modifier = Modifier) {
    val logListState = rememberLazyListState()
    var previousLogCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(run.logs) {
        if (run.logs.isNotEmpty()) {
            val lastVisible = logListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val followLatest = previousLogCount < 0 || lastVisible < 0 ||
                lastVisible >= previousLogCount - 1
            if (followLatest) {
                logListState.scrollToItem(run.logs.lastIndex)
            }
        }
        previousLogCount = run.logs.size
    }
    ConsoleCard(modifier, contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PowerSettingsNew, null, tint = MahCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("运行日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("${run.logs.size} 条", color = MahTextMuted, style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider(color = MahOutline.copy(alpha = 0.5f))
        if (run.logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.TaskAlt, null, Modifier.size(32.dp), tint = MahOutline)
                    Spacer(Modifier.height(8.dp))
                    Text("任务日志会显示在这里", color = MahTextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = logListState,
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(run.logs) { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("›", color = MahCyan, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            line,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountPane(vm: HottaViewModel, running: Boolean) {
    val config by vm.config.collectAsState()
    var label by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mobileFormExpanded by remember { mutableStateOf(false) }

    fun save() {
        if (username.isBlank()) return
        vm.upsertAccount(
            GameAccount(
                id = UUID.randomUUID().toString(),
                label = label.ifBlank { username },
                username = username,
                password = password,
            ),
        )
        label = ""
        username = ""
        password = ""
        mobileFormExpanded = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
        val expandedFormHeight = minOf(330.dp, maxHeight * 0.6f)
        if (maxWidth >= 560.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AccountFormCard(
                    label = label,
                    username = username,
                    password = password,
                    onLabel = { label = it },
                    onUsername = { username = it },
                    onPassword = { password = it },
                    onSave = ::save,
                    onCollapse = null,
                    modifier = Modifier.weight(0.4f).fillMaxHeight(),
                )
                AccountListCard(vm, config.accounts, running, Modifier.weight(0.6f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mobileFormExpanded) {
                    AccountFormCard(
                        label = label,
                        username = username,
                        password = password,
                        onLabel = { label = it },
                        onUsername = { username = it },
                        onPassword = { password = it },
                        onSave = ::save,
                        onCollapse = { mobileFormExpanded = false },
                        modifier = Modifier.fillMaxWidth().height(expandedFormHeight),
                    )
                } else {
                    OutlinedButton(
                        onClick = { mobileFormExpanded = true },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(7.dp))
                        Text("添加账号")
                    }
                }
                AccountListCard(vm, config.accounts, running, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun AccountFormCard(
    label: String,
    username: String,
    password: String,
    onLabel: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSave: () -> Unit,
    onCollapse: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ConsoleCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, tint = MahCyan)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("添加账号", style = MaterialTheme.typography.titleMedium)
                Text("账号信息仅保存在本机", color = MahTextMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (onCollapse != null) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCollapse) { Text("收起") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = onLabel,
                label = { Text("备注（可填写老头名称）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = onUsername,
                label = { Text("通行证账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPassword,
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSave,
                enabled = username.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("保存账号")
            }
        }
    }
}

@Composable
private fun AccountListCard(
    vm: HottaViewModel,
    accounts: List<GameAccount>,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    ConsoleCard(modifier, contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("账号列表", style = MaterialTheme.typography.titleMedium)
                Text(
                    "已启用 ${accounts.count { it.enabled }} / ${accounts.size}",
                    color = MahTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = { vm.setAllAccountsEnabled(true) },
                enabled = !running && accounts.isNotEmpty(),
            ) { Text("全部启用") }
            TextButton(
                onClick = { vm.setAllAccountsEnabled(false) },
                enabled = !running && accounts.isNotEmpty(),
            ) { Text("全部取消") }
        }
        HorizontalDivider(color = MahOutline.copy(alpha = 0.5f))
        if (accounts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有保存账号", color = MahTextMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(accounts, key = { it.id }) { account ->
                    AccountItem(vm, account, running)
                }
            }
        }
    }
}

@Composable
private fun AccountItem(vm: HottaViewModel, account: GameAccount, running: Boolean) {
    var editing by remember(account.id) { mutableStateOf(false) }
    var confirmingDelete by remember(account.id) { mutableStateOf(false) }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除账号") },
            text = {
                val name = account.label.ifBlank { account.username }
                Text("确定删除账号“$name”吗？删除后需要重新添加。")
            },
            confirmButton = {
                TextButton(
                    enabled = !running,
                    onClick = {
                        confirmingDelete = false
                        if (!running) vm.removeAccount(account.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MahRed),
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
    }
    if (editing) {
        AccountEditDialog(
            account = account,
            onDismiss = { editing = false },
            onSave = {
                vm.upsertAccount(it)
                editing = false
            },
        )
    }
    Surface(
        color = if (account.enabled) MahSurfaceHigh else MahSurfaceHigh.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (account.enabled) MahCyan.copy(alpha = 0.24f) else MahOutline.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = account.enabled,
                onCheckedChange = { vm.upsertAccount(account.copy(enabled = it)) },
                enabled = !running,
                colors = CheckboxDefaults.colors(checkedColor = MahCyan),
            )
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(MahCyan.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(account.label.firstOrNull()?.toString()?.uppercase() ?: "?", color = MahCyan, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(account.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(account.username, color = MahTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                if (account.characterName.isNotBlank()) {
                    Text("角色 · ${account.characterName}", color = MahGold, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (account.islandMerchantPending) {
                    Text("老头名称待写入", color = MahGold, style = MaterialTheme.typography.bodySmall)
                }
            }
            FilledTonalButton(
                onClick = { vm.startLoginOnly(account.id) },
                enabled = !running,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("测试登录") }
            IconButton(
                onClick = { editing = true },
                enabled = !running,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MahCyan),
            ) {
                Icon(Icons.Default.Edit, "修改")
            }
            IconButton(
                onClick = { confirmingDelete = true },
                enabled = !running,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MahRed),
            ) {
                Icon(Icons.Default.DeleteOutline, "删除")
            }
        }
    }
}

@Composable
private fun AccountEditDialog(
    account: GameAccount,
    onDismiss: () -> Unit,
    onSave: (GameAccount) -> Unit,
) {
    var label by remember(account.id) { mutableStateOf(account.label) }
    var username by remember(account.id) { mutableStateOf(account.username) }
    var password by remember(account.id) { mutableStateOf(account.password) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("通行证账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        account.copy(
                            label = label.trim().ifBlank { username.trim() },
                            username = username.trim(),
                            password = password,
                        ),
                    )
                },
                enabled = username.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun TaskPane(vm: HottaViewModel) {
    val config by vm.config.collectAsState()
    val o = config.options
    val expandedTasks = remember { mutableStateMapOf<DailyTask, Boolean>() }
    val taskOrder = o.orderedDailyTasks()
    fun set(update: TaskOptions.() -> TaskOptions) = vm.updateOptions(o.update())
    fun move(task: DailyTask, offset: Int) {
        val order = o.orderedDailyTasks().toMutableList()
        val from = order.indexOf(task)
        val to = from + offset
        if (from < 0 || to !in order.indices) return
        order[from] = order[to].also { order[to] = order[from] }
        set { copy(taskOrder = order) }
    }

    ConsoleCard(
        modifier = Modifier.fillMaxSize().padding(bottom = 16.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("任务编排", style = MaterialTheme.typography.titleMedium)
                Text("按顺序执行，点击任务可展开详细设置", color = MahTextMuted, style = MaterialTheme.typography.bodySmall)
            }
            StatusPill(
                text = "${taskOrder.count { o.isEnabled(it) } + if (o.login) 1 else 0} 项已启用",
                active = true,
            )
        }
        HorizontalDivider(color = MahOutline.copy(alpha = 0.5f))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                TaskItem(
                    index = 0,
                    label = "登录切号",
                    subtitle = "固定为每个账号的第一步",
                    checked = o.login,
                    icon = Icons.Default.PersonOutline,
                    locked = true,
                    onChange = { set { copy(login = it) } },
                )
            }
            items(taskOrder, key = { it.name }) { task ->
                val index = taskOrder.indexOf(task)
                val checked = o.isEnabled(task)
                val expandable = task == DailyTask.KITCHEN ||
                    task == DailyTask.TRIALS ||
                    task == DailyTask.BYGONE_PHANTASM ||
                    task == DailyTask.MAIL ||
                    task == DailyTask.GUILD_DONATE
                val expanded = expandedTasks[task] == true
                Column {
                    TaskItem(
                        index = index + 1,
                        label = taskLabel(task),
                        subtitle = taskSubtitle(task, o),
                        checked = checked,
                        icon = taskIcon(task),
                        canMoveUp = index > 0,
                        canMoveDown = index < taskOrder.lastIndex,
                        expandable = expandable,
                        expanded = expanded,
                        onChange = { enabled -> set { setTaskEnabled(task, enabled) } },
                        onMoveUp = { move(task, -1) },
                        onMoveDown = { move(task, 1) },
                        onToggleExpanded = { if (expandable) expandedTasks[task] = !expanded },
                    )
                    if (expanded && checked) {
                        TaskDetails(task, o, set = ::set)
                    }
                }
            }
        }
    }
}

private fun TaskOptions.setTaskEnabled(task: DailyTask, enabled: Boolean): TaskOptions = when (task) {
    DailyTask.CHECK_IN -> copy(checkIn = enabled)
    DailyTask.SUPPLY -> copy(supply = enabled)
    DailyTask.MAIL -> copy(mail = enabled)
    DailyTask.KITCHEN -> copy(kitchen = enabled)
    DailyTask.TRIALS -> copy(trials = enabled)
    DailyTask.ISLAND_MERCHANT -> copy(islandMerchant = enabled)
    DailyTask.BYGONE_PHANTASM -> copy(bygonePhantasm = enabled)
    DailyTask.GUILD_DONATE -> copy(guildDonate = enabled)
}

@Composable
private fun TaskItem(
    index: Int,
    label: String,
    subtitle: String,
    checked: Boolean,
    icon: ImageVector,
    locked: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onToggleExpanded: () -> Unit = {},
) {
    Surface(
        color = if (checked) MahSurfaceHigh else MahSurfaceHigh.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (checked) MahCyan.copy(alpha = 0.2f) else MahOutline.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (index + 1).toString().padStart(2, '0'),
                color = MahTextMuted,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(28.dp),
            )
            Checkbox(
                checked = checked,
                onCheckedChange = onChange,
                colors = CheckboxDefaults.colors(checkedColor = MahCyan),
            )
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(MahCyan.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(19.dp), tint = if (checked) MahCyan else MahTextMuted)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(subtitle, color = MahTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (locked) {
                Text("固定首位", color = MahTextMuted, style = MaterialTheme.typography.labelMedium)
            } else {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.ArrowUpward, "上移", Modifier.size(18.dp))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.ArrowDownward, "下移", Modifier.size(18.dp))
                }
            }
            if (expandable) {
                IconButton(onClick = onToggleExpanded, modifier = Modifier.size(38.dp)) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开")
                }
            } else {
                Icon(Icons.Default.KeyboardArrowRight, null, tint = MahOutline)
            }
        }
    }
}

@Composable
private fun TaskDetails(task: DailyTask, o: TaskOptions, set: (TaskOptions.() -> TaskOptions) -> Unit) {
    Surface(
        color = Color(0xFFF1F5FA),
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        border = BorderStroke(1.dp, MahOutline.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            when (task) {
                DailyTask.TRIALS -> Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrialType.entries.forEach { type ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = o.trialType == type,
                                onClick = { set { copy(trialType = type) } },
                                colors = RadioButtonDefaults.colors(selectedColor = MahGold),
                            )
                            Text(trialTypeLabel(type), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                DailyTask.GUILD_DONATE -> Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = o.guildWeeklyBenefit,
                            onCheckedChange = { set { copy(guildWeeklyBenefit = it) } },
                            colors = CheckboxDefaults.colors(checkedColor = MahGold),
                        )
                        Text("领取周奖励", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = o.guildRewards,
                            onCheckedChange = { set { copy(guildRewards = it) } },
                            colors = CheckboxDefaults.colors(checkedColor = MahGold),
                        )
                        Text("领取公会荣耀奖励", style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> Unit
            }
            val weekdays = o.taskWeekdays(task)
            if (weekdays != null) {
                WeekdaySelector(
                    selected = weekdays,
                    onChange = { changed -> set { setTaskWeekdays(task, changed) } },
                )
            }
        }
    }
}

@Composable
private fun WeekdaySelector(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("执行日", color = MahTextMuted, style = MaterialTheme.typography.bodySmall)
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
            val day = index + 1
            FilterChip(
                selected = day in selected,
                onClick = {
                    val changed = selected.toMutableSet()
                    if (!changed.add(day)) changed.remove(day)
                    onChange(changed)
                },
                label = { Text(label) },
            )
        }
    }
}

private fun TaskOptions.taskWeekdays(task: DailyTask): Set<Int>? = when (task) {
    DailyTask.KITCHEN -> kitchenWeekdays
    DailyTask.TRIALS -> trialsWeekdays
    DailyTask.BYGONE_PHANTASM -> bygoneWeekdays
    DailyTask.MAIL -> mailWeekdays
    else -> null
}

private fun TaskOptions.setTaskWeekdays(task: DailyTask, weekdays: Set<Int>): TaskOptions = when (task) {
    DailyTask.KITCHEN -> copy(kitchenWeekdays = weekdays)
    DailyTask.TRIALS -> copy(trialsWeekdays = weekdays)
    DailyTask.BYGONE_PHANTASM -> copy(bygoneWeekdays = weekdays)
    DailyTask.MAIL -> copy(mailWeekdays = weekdays)
    else -> this
}

private fun taskLabel(task: DailyTask): String = when (task) {
    DailyTask.CHECK_IN -> "每日签到"
    DailyTask.SUPPLY -> "执行供给"
    DailyTask.MAIL -> "领取邮件"
    DailyTask.KITCHEN -> "MIA 私厨"
    DailyTask.TRIALS -> "扫荡次元历练"
    DailyTask.ISLAND_MERCHANT -> "检查人工岛老头"
    DailyTask.BYGONE_PHANTASM -> "旧日幻想"
    DailyTask.GUILD_DONATE -> "公会捐赠"
}

private fun taskSubtitle(task: DailyTask, o: TaskOptions): String = when (task) {
    DailyTask.CHECK_IN -> "领取当日签到奖励"
    DailyTask.SUPPLY -> "领取特别行动版及累计奖励"
    DailyTask.MAIL -> "一键领取邮件附件 · ${weekdaySummary(o.mailWeekdays)}"
    DailyTask.KITCHEN -> "品尝至完成 · ${weekdaySummary(o.kitchenWeekdays)}"
    DailyTask.TRIALS -> "${trialTypeLabel(o.trialType)} · ${weekdaySummary(o.trialsWeekdays)}"
    DailyTask.ISLAND_MERCHANT -> "检查并记录老头名称"
    DailyTask.BYGONE_PHANTASM -> "潜入下一层后退出 · ${weekdaySummary(o.bygoneWeekdays)}"
    DailyTask.GUILD_DONATE -> when {
        o.guildWeeklyBenefit && o.guildRewards -> "捐赠、周奖励及荣耀奖励"
        o.guildWeeklyBenefit -> "捐赠并领取周奖励"
        o.guildRewards -> "捐赠并领取公会荣耀奖励"
        else -> "完成公会捐赠"
    }
}

private fun weekdaySummary(days: Set<Int>): String {
    if (days.size == 7) return "每天"
    if (days.isEmpty()) return "不执行"
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    return "周" + days.sorted().joinToString("、") { labels[it - 1] }
}

private fun taskIcon(task: DailyTask): ImageVector = when (task) {
    DailyTask.CHECK_IN -> Icons.Default.CheckCircle
    DailyTask.SUPPLY -> Icons.Default.TaskAlt
    DailyTask.MAIL -> Icons.Default.MailOutline
    DailyTask.KITCHEN -> Icons.Default.Restaurant
    DailyTask.TRIALS -> Icons.Default.Tune
    DailyTask.ISLAND_MERCHANT -> Icons.Default.PersonOutline
    DailyTask.BYGONE_PHANTASM -> Icons.Default.PowerSettingsNew
    DailyTask.GUILD_DONATE -> Icons.Default.Group
}

private fun trialTypeLabel(type: TrialType): String = when (type) {
    TrialType.WEAPON -> "武器历练"
    TrialType.MATRIX -> "意志历练"
    TrialType.GOLD -> "金币历练"
}

@Composable
private fun ConsoleCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MahSurface.copy(alpha = 0.94f)),
        border = BorderStroke(1.dp, MahOutline.copy(alpha = 0.72f)),
    ) {
        Column(
            Modifier.fillMaxSize()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}
