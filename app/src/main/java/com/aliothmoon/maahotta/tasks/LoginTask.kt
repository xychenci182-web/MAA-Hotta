package com.aliothmoon.maahotta.tasks

import android.graphics.Point
import com.aliothmoon.maahotta.constant.Packages
import com.aliothmoon.maahotta.data.GameAccount
import com.aliothmoon.maahotta.engine.BotContext
import com.aliothmoon.maahotta.engine.AccountIdentity
import com.aliothmoon.maahotta.engine.AccountSession
import com.aliothmoon.maahotta.engine.GameTask
import com.aliothmoon.maahotta.engine.TaskResult
import com.aliothmoon.maahotta.vision.TemplateMatcher
import com.aliothmoon.maahotta.vision.GameScreen
import com.aliothmoon.maahotta.vision.AccountScreenDetector
import com.aliothmoon.maahotta.vision.AccountTransitionScreenDetector
import com.aliothmoon.maahotta.vision.AnnouncementDetector
import com.aliothmoon.maahotta.vision.TitleScreenDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

class LoginTask(
    private val account: GameAccount,
    private val launchGame: Boolean = true,
    private val forceSwitchWithoutVerification: Boolean = false,
    private val verificationOnly: Boolean = false,
    private val savedAccountPhones: List<String> = listOf(account.username),
) : GameTask {
    override val id = "login"
    override val title = if (verificationOnly) "核验当前账号" else "登录账号"
    override fun allowEngineRetry(): Boolean = false
    override fun allowEngineRelogin(): Boolean = false
    private var finalHudConfirmationAllowed = false
    private val loginIdentity = AccountSession()

    private data class PhonePage(val nextPoint: Point?)
    private data class PasswordPage(val fieldId: String?, val fieldPoint: Point?)
    private sealed interface CredentialAttempt {
        data class Done(val result: TaskResult) : CredentialAttempt
        data object Submitted : CredentialAttempt
    }
    private sealed interface LoginEntry {
        data class Phone(val page: PhonePage) : LoginEntry
        data class Password(val page: PasswordPage, val shownPhone: String?) : LoginEntry
        data class GameEntered(val verifiedAccountId: String? = null) : LoginEntry
        data class Failure(val reason: String) : LoginEntry
    }

    override suspend fun run(ctx: BotContext): TaskResult {
        loginIdentity.invalidate()
        ctx.invalidateAccountIdentity()
        val login = if (verificationOnly) verifyExistingAccount(ctx) else runLogin(ctx)
        // The HUD menu frames already confirm the main screen. Do not open User Center again.
        if (login.ok) {
            ctx.confirmAccountIdentity(account.id)
            ctx.log(
                if (loginIdentity.isVerifiedFor(account.id)) "账号与主界面均已确认，登录完成"
                else "主界面菜单已确认，登录完成",
            )
        }
        return login
    }

    /** Disabling automatic login still requires identity proof, but never switches or enters credentials. */
    private suspend fun verifyExistingAccount(ctx: BotContext): TaskResult {
        ctx.resetHudDetection()
        val verificationDeadline = ctx.elapsedRealtime() + 120_000L
        var identityMatched = false
        return withTimeoutOrNull(120_000L) {
            ctx.withLoginCaptureDeadline(verificationDeadline) verification@{
                awaitGameCapture(ctx)
                var userCenterReady = false
                while (ctx.elapsedRealtime() < verificationDeadline) {
                    if (ctx.device.viewInfo("lib_change_account") != null ||
                        accountOverlay(ctx, AccountScreenDetector::isUserCenter)) {
                        userCenterReady = true
                        break
                    }
                    if (ctx.gameScreen() in setOf(GameScreen.HUD, GameScreen.MENU, GameScreen.SETTINGS)) {
                        if (!openUserCenterFromGame(ctx)) {
                            return@verification TaskResult.uncertain(title, "已确认游戏界面，但未能进入用户中心核验账号")
                        }
                        userCenterReady = true
                        break
                    }
                    delay(500) // A valid screenshot of a loading screen is still not permission to navigate.
                }
                if (!userCenterReady) return@verification TaskResult.uncertain(title, "未在核验等待时间内确认可操作的游戏界面")
                var matched = false
                while (ctx.elapsedRealtime() < verificationDeadline) {
                    val center = ctx.device.viewInfo("lib_change_account") != null ||
                        accountOverlay(ctx, AccountScreenDetector::isUserCenter)
                    if (center) {
                        val shown = ctx.device.viewInfo("lib_account")?.text ?: ctx.device.maskedAccountPhone()
                        if (sameSavedPhone(shown)) { matched = true; identityMatched = true; break }
                        if (shown?.contains(Regex("[0-9]{3}\\*+[0-9]{4}|[0-9]{11}")) == true) {
                            return@verification TaskResult.uncertain(title, "当前账号与所选账号不一致或掩码存在冲突")
                        }
                    }
                    delay(400)
                }
                if (!matched) return@verification TaskResult.uncertain(title, "在核验等待时间内未能读取账号身份")
                if (!ctx.device.clickView("lib_close") && !ctx.device.clickView("lib_goback")) {
                    tapAccountAnchor(ctx, 211f, 28f)
                }
                var returnedToGame = false
                while (ctx.elapsedRealtime() < verificationDeadline) {
                    delay(400)
                    val screen = ctx.device.screenshot() ?: continue
                    val stillVisible = try {
                        AccountScreenDetector.isUserCenter(screen) || ctx.device.viewInfo("lib_change_account") != null
                    } finally { screen.recycle() }
                    if (stillVisible) continue
                    when (ctx.gameScreen()) {
                        GameScreen.SETTINGS -> {
                            leaveSettingsAndEnter(ctx)
                            returnedToGame = true
                            break
                        }
                        GameScreen.HUD -> { returnedToGame = true; break }
                        else -> Unit // Loading or unknown: wait without clicking.
                    }
                }
                if (!returnedToGame) return@verification TaskResult.uncertain(title, "账号已核对，但未在等待时间内确认返回游戏")
                waitEntered(ctx)
            }
        } ?: if (identityMatched && ctx.finishPendingHudConfirmation(verificationDeadline, allowAdditionalFrames = false)) {
            TaskResult(title, true, "账号与游戏主界面均已确认")
        } else TaskResult.uncertain(title, "账号核验超时，停止日常")
    }

    private suspend fun runLogin(ctx: BotContext): TaskResult {
        ctx.resetHudDetection()
        finalHudConfirmationAllowed = false
        ctx.log(
            when {
                launchGame -> "强制关闭并重新启动游戏，识别当前登录状态"
                forceSwitchWithoutVerification -> "从用户中心直接切换到下一账号"
                else -> "游戏已启动，继续识别当前登录状态"
            },
        )
        if (launchGame && !ctx.device.launchApp(Packages.OFFICIAL, forceStop = true)) {
            return TaskResult(title, false, "无法强制重启游戏，请检查 Shizuku 授权和游戏安装状态", retryable = false)
        }
        if (launchGame) {
            ctx.log("首次启动游戏，固定等待20秒后开始识别")
            delay(20_000)
        }
        // Capture readiness and login-screen recognition share one hard limit.
        // Return a normal failure so the existing diagnostic/report path runs.
        val loginDeadline = ctx.elapsedRealtime() + 120_000L
        val entry = withTimeoutOrNull(120_000L) {
            ctx.withLoginCaptureDeadline(loginDeadline) {
                awaitGameCapture(ctx)
                if (launchGame && !confirmAnnouncementClosedBeforeLogin(ctx, loginDeadline)) {
                    LoginEntry.Failure("游戏公告仍未关闭，停止登录识别")
                } else {
                    waitForLoginEntry(ctx, forceSwitchWithoutVerification)
                }
            }
        } ?: if (finalHudConfirmationAllowed && ctx.finishPendingHudConfirmation(loginDeadline)) {
            LoginEntry.GameEntered(loginIdentity.verifiedAccountId)
        } else null
            ?: return TaskResult(
                title,
                false,
                "登录画面识别超时：等待120秒仍未确认登录页面或游戏主界面，已终止任务（${ctx.hudDetectionSummary()}）",
                retryable = false,
            )
        if (entry is LoginEntry.GameEntered) {
            if (entry.verifiedAccountId != account.id) loginIdentity.invalidate()
            return TaskResult(title, true, "已进入游戏主界面")
        }
        if (entry is LoginEntry.Failure) {
            return TaskResult(title, false, entry.reason)
        }
        loginIdentity.invalidate()
        ctx.resetHudDetection()
        // Filling the phone and password has its own 120s budget and must not consume the loading window.
        val credentialDeadline = ctx.elapsedRealtime() + 120_000L
        val submitted = withTimeoutOrNull(120_000L) {
            ctx.withLoginCaptureDeadline(credentialDeadline) credentials@{
                if (entry is LoginEntry.Password) {
                    if (!sameSavedPhone(entry.shownPhone)) {
                        return@credentials CredentialAttempt.Done(
                            TaskResult(title, false, "密码页账号与所选手机号不一致或无法核对"),
                        )
                    }
                    ctx.log("密码页显示的账号与所选手机号一致")
                    return@credentials submitPassword(ctx, entry.page)
                }
                val phonePage = (entry as LoginEntry.Phone).page
                val phone = account.username.trim().removePrefix("+86")
                if (phone.isEmpty() || !phone.all { it in '0'..'9' }) {
                    return@credentials CredentialAttempt.Done(
                        TaskResult(title, false, "通行证账号须填写不含区号的手机号"),
                    )
                }
                if (account.password.isEmpty()) {
                    return@credentials CredentialAttempt.Done(TaskResult(title, false, "未填写密码"))
                }
                if (!agreeToTerms(ctx, phonePage)) {
                    return@credentials CredentialAttempt.Done(
                        TaskResult(title, false, "无法确认用户协议已勾选"),
                    )
                }
                if (!enterPhone(ctx, phonePage, phone)) {
                    return@credentials CredentialAttempt.Done(
                        TaskResult(title, false, "手机号未能写入输入框"),
                    )
                }
                if (!clickNext(ctx, phonePage)) {
                    return@credentials CredentialAttempt.Done(
                        TaskResult(title, false, "找不到“下一步”按钮"),
                    )
                }

                val passwordPage = waitForPasswordPage(ctx, credentialDeadline)
                    ?: return@credentials CredentialAttempt.Done(
                        TaskResult(title, false, "点击下一步后仍未进入密码页"),
                    )
                submitPassword(ctx, passwordPage)
            }
        }
        if (submitted == null) {
            return if (ctx.finishPendingHudConfirmation(credentialDeadline, allowAdditionalFrames = false)) {
                TaskResult(title, true, "已确认进入游戏主界面，继续核验账号")
            } else TaskResult.uncertain(title, "填写账号密码等待120秒仍未提交登录，已停止")
        }
        return when (submitted) {
            is CredentialAttempt.Done -> submitted.result
            CredentialAttempt.Submitted -> awaitGameLoadingAfterSubmit(ctx)
        }
    }

    /** Password entry ends when the login button is clicked. Loading starts after the login page is gone. */
    private suspend fun submitPassword(ctx: BotContext, passwordPage: PasswordPage): CredentialAttempt {
        if (!enterPassword(ctx, passwordPage)) {
            return CredentialAttempt.Done(TaskResult(title, false, "密码未能写入输入框"))
        }
        if (!clickLogin(ctx)) {
            return CredentialAttempt.Done(TaskResult(title, false, "找不到密码页的登录按钮"))
        }
        return CredentialAttempt.Submitted
    }

    private suspend fun awaitGameLoadingAfterSubmit(ctx: BotContext): TaskResult {
        // Confirm the click left the login page, but do not spend the loading window there.
        ctx.log("登录已提交，按蓝色登录按钮确认是否离开密码页")
        val exitDeadline = ctx.elapsedRealtime() + 10_000L
        val leftLoginPage = withTimeoutOrNull(10_000L) {
            ctx.withLoginCaptureDeadline(exitDeadline) {
                waitForPasswordPageExit(ctx, exitDeadline)
            }
        } == true
        if (!leftLoginPage) {
            return if (ctx.finishPendingHudConfirmation(exitDeadline, allowAdditionalFrames = false)) {
                TaskResult(title, true, "已确认进入游戏主界面，继续核验账号")
            } else {
                TaskResult.uncertain(title, "登录提交后10秒内仍能看到蓝色登录按钮，停止且不重复提交")
            }
        }
        ctx.log("蓝色登录按钮已消失，开始游戏加载")
        ctx.log("游戏加载单独等待120秒")
        val loadingDeadline = ctx.elapsedRealtime() + 120_000L
        return withTimeoutOrNull(120_000L) {
            ctx.withLoginCaptureDeadline(loadingDeadline) {
                waitEntered(ctx, loadingDeadline)
            }
        } ?: if (ctx.finishPendingHudConfirmation(loadingDeadline, allowAdditionalFrames = false)) {
            TaskResult(title, true, "已确认进入游戏主界面，继续核验账号")
        } else TaskResult.uncertain(title, "登录提交后游戏加载等待120秒仍未确认完成，已停止")
    }

    private suspend fun waitForPasswordPageExit(ctx: BotContext, deadline: Long): Boolean {
        var absentFrames = 0
        while (ctx.elapsedRealtime() < deadline) {
            delay(500)
            val screen = ctx.device.screenshot()
            if (screen == null) {
                absentFrames = 0
                continue
            }
            val passwordPageVisible = try {
                AccountScreenDetector.findPasswordSubmit(screen) != null
            } finally {
                screen.recycle()
            }
            absentFrames = if (passwordPageVisible) 0 else absentFrames + 1
            if (absentFrames >= 2) return true
        }
        return false
    }

    /** First launch only: the login home can be covered by the announcement. */
    private suspend fun confirmAnnouncementClosedBeforeLogin(ctx: BotContext, deadline: Long): Boolean {
        ctx.log("首次登录，先确认游戏公告已关闭")
        while (ctx.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            val screen = ctx.device.screenshot()
            if (screen == null) {
                delay(500)
                continue
            }
            val open = try {
                AnnouncementDetector.hasLayout(screen)
            } finally {
                screen.recycle()
            }
            if (!open) {
                ctx.log("已确认游戏公告关闭，开始下一步识别")
                return true
            }
            ctx.dismissAnnouncement()
            delay(500)
        }
        return false
    }

    private suspend fun awaitGameCapture(ctx: BotContext) {
        var nextWaitLogAt = ctx.elapsedRealtime() + 30_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            val shot = ctx.device.screenshot()
            if (shot != null) {
                try {
                    val size = ctx.device.screenSize()
                    if (size.x > 0 && size.y > 0 && shot.width > shot.height &&
                        size.x > size.y &&
                        kotlin.math.abs(shot.width.toDouble() / shot.height /
                            (size.x.toDouble() / size.y) - 1.0) <= 0.03
                    ) return
                } finally {
                    shot.recycle()
                }
            }
            val now = ctx.elapsedRealtime()
            if (now >= nextWaitLogAt) {
                ctx.log("仍未取得有效横屏游戏画面，继续识别；可点击停止结束等待")
                nextWaitLogAt = now + 30_000L
            }
            delay(500)
        }
    }

    private suspend fun waitForLoginEntry(
        ctx: BotContext,
        forceSwitchWithoutVerification: Boolean,
    ): LoginEntry {
        var accountChecked = false
        var waitingForUserCenter = false
        var lastUserCenterTap = 0L
        var settingsOpenAttempts = 0
        var userCenterOpenAttempts = 0
        var settingsExitAttempts = 0
        var switchAttempts = 0
        var switchedAccount = false
        var waitingForAccountList = false
        var waitingForOtherLogin = false
        var otherLoginSelected = false
        var lastSwitchTap = 0L
        var addAccountAttempts = 0
        var otherLoginAttempts = 0
        var recentAccountWaitRounds = 0
        var titleAccountRecoveryActive = false
        var titleAccountRecoveryAttempts = 0
        var titleEntryTapAttempts = 0
        var lastHudWaitLog = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (forceSwitchWithoutVerification || waitingForAccountList || waitingForOtherLogin || switchedAccount) {
                loginIdentity.invalidate()
            }
            // Once the account was checked, use the first fresh frame to
            // recognize the HUD. Popup overlays are excluded in this check.
            val accountFlowActive = waitingForUserCenter ||
                waitingForAccountList || waitingForOtherLogin
            if (accountChecked && !accountFlowActive) {
                finalHudConfirmationAllowed = !forceSwitchWithoutVerification || switchedAccount
                if (ctx.hasEnteredGame(requiredFrames = 3)) {
                    if (forceSwitchWithoutVerification && !switchedAccount) {
                        return LoginEntry.Failure("尚未执行切换账号就返回了游戏主界面")
                    }
                    ctx.log("菜单连续确认，已进入游戏主界面")
                    return LoginEntry.GameEntered(loginIdentity.verifiedAccountId)
                }
                finalHudConfirmationAllowed = false
                val now = ctx.elapsedRealtime()
                if (now - lastHudWaitLog >= 5_000) {
                    ctx.log("持续识别右上角菜单：${ctx.hudDetectionSummary()}")
                    lastHudWaitLog = now
                }
            }
            // Announcements can cover any page, including Settings and account
            // dialogs. Nothing underneath is clickable until the popup is gone.
            if (ctx.dismissAnnouncement()) {
                if (waitingForUserCenter) {
                    delay(500)
                    ctx.log("公告关闭后重新识别用户中心按钮")
                    tapRecognizedUserCenter(ctx)
                    lastUserCenterTap = ctx.elapsedRealtime()
                    delay(700)
                }
                continue
            }
            if (!accountChecked && !accountFlowActive && accountOverlay(ctx) {
                    TitleScreenDetector.findUnobstructedEntry(it) != null
                }) {
                if (settingsOpenAttempts++ >= 3) return LoginEntry.Failure("无法从登录首页打开设置")
                waitingForUserCenter = true
                ctx.log("已确认无遮挡登录首页，点击齿轮核对账号")
                tapTitleSettingsGear(ctx)
                ctx.log("齿轮后识别用户中心按钮")
                tapRecognizedUserCenter(ctx)
                lastUserCenterTap = ctx.elapsedRealtime()
                delay(700)
                continue
            }
            if (waitingForOtherLogin) {
                val otherLoginVisible = ctx.device.viewInfo("lib_other_login_method_layout") != null ||
                    ctx.device.viewInfo("lib_other_login") != null ||
                    accountOverlay(ctx, AccountScreenDetector::isQuickLogin)
                if (otherLoginVisible) {
                    ctx.log("添加新账号后，点击其他登录方式")
                    if (!ctx.device.clickText("其他登录方式") &&
                        !ctx.device.clickView("lib_other_login_method_layout") &&
                        !ctx.device.clickView("lib_other_login")
                    ) {
                        tapAccountAnchor(ctx, 0f, 332f)
                    }
                    waitingForOtherLogin = false
                    otherLoginSelected = true
                    recentAccountWaitRounds = 0
                    delay(700)
                } else {
                    val directPhonePage = detectPhonePage(ctx)
                    if (directPhonePage != null) {
                        ctx.log("添加新账号后已确认进入手机号输入页")
                        return LoginEntry.Phone(directPhonePage)
                    }
                    val stillOnAccountList = accountOverlay(ctx, AccountScreenDetector::isAccountList)
                    if (stillOnAccountList) {
                        if (addAccountAttempts++ >= 3) {
                            return LoginEntry.Failure("多次点击添加新账号后仍停留在账号列表")
                        }
                        ctx.log("最近账号页未出现，仍在账号列表，重新点击添加新账号")
                        val clicked = ctx.device.clickView("lib_add_account") ||
                            ctx.device.clickView("lib_add_new_account") ||
                            ctx.device.clickView("lib_add_account_layout") ||
                            ctx.device.clickText("添加新账号")
                        if (!clicked) tapAccountAnchor(ctx, 0f, 338f)
                        recentAccountWaitRounds = 0
                        delay(700)
                    } else {
                        if (recentAccountWaitRounds++ >= 4) {
                            return LoginEntry.Failure("添加新账号后无法进入最近账号页")
                        }
                        ctx.log("等待最近账号页显示，随后重新识别当前页面")
                        delay(500)
                    }
                }
                continue
            }
            if (ctx.device.viewInfo("lib_account") != null &&
                ctx.device.viewInfo("lib_next") != null
            ) {
                ctx.log("已识别手机号输入框")
                return LoginEntry.Phone(PhonePage(null))
            }
            val passwordFieldId = listOf("lib_password", "lib_pwd", "lib_login_password")
                .firstOrNull { ctx.device.viewInfo(it) != null }
            val shownPasswordPhone = ctx.device.maskedAccountPhone()
            if (passwordFieldId != null) {
                ctx.log("已识别最近账号密码页")
                return LoginEntry.Password(PasswordPage(passwordFieldId, null), shownPasswordPhone)
            }
            if (shownPasswordPhone != null) {
                val passwordField = ctx.waitMatch("pwd_password", 700, 0.76f)
                if (passwordField != null) {
                    ctx.log("已通过图片识别最近账号密码页")
                    return LoginEntry.Password(PasswordPage(null, passwordField.point), shownPasswordPhone)
                }
            }
            val accountListVisible = accountOverlay(ctx, AccountScreenDetector::isAccountList)
            val accountListControlVisible = ctx.device.viewInfo("lib_add_account") != null ||
                ctx.device.viewInfo("lib_add_new_account") != null ||
                ctx.device.viewInfo("lib_add_account_layout") != null
            if (accountListControlVisible || (accountListVisible && shownPasswordPhone == null)) {
                loginIdentity.invalidate()
                if (addAccountAttempts++ >= 3) {
                    return LoginEntry.Failure("无法从账号列表进入添加新账号")
                }
                ctx.log("识别到账号列表，点击添加新账号")
                val clicked = ctx.device.clickView("lib_add_account") ||
                    ctx.device.clickView("lib_add_new_account") ||
                    ctx.device.clickView("lib_add_account_layout") ||
                    ctx.device.clickText("添加新账号")
                if (!clicked) tapAccountAnchor(ctx, 0f, 338f)
                waitingForAccountList = false
                waitingForOtherLogin = switchedAccount
                titleAccountRecoveryActive = false
                titleEntryTapAttempts = 0
                recentAccountWaitRounds = 0
                delay(700)
                continue
            }
            if (ctx.device.viewInfo("lib_other_login_method_layout") != null ||
                accountOverlay(ctx, AccountScreenDetector::isQuickLogin)
            ) {
                if (otherLoginAttempts++ >= 3) {
                    return LoginEntry.Failure("无法处理最近账号登录页")
                }
                titleAccountRecoveryActive = false
                handleQuickLogin(ctx, switchedAccount)
                if (switchedAccount) otherLoginSelected = true
                delay(700)
                continue
            }
            if (waitingForAccountList && ctx.elapsedRealtime() - lastSwitchTap > 650) {
                val titleEntry = ctx.waitUntil(700, 250, TitleScreenDetector::findEntry)
                if (titleEntry != null) {
                    if (titleAccountRecoveryActive) {
                        if (titleEntryTapAttempts++ >= 5) {
                            if (titleAccountRecoveryAttempts >= 3) {
                                return LoginEntry.Failure("切换账号后多次点击登录首页仍未弹出账号页面")
                            }
                            ctx.log("多次点击登录首页仍未弹出账号页面，重新通过齿轮和用户中心触发切换账号")
                            titleAccountRecoveryActive = false
                            titleEntryTapAttempts = 0
                            delay(400)
                            continue
                        }
                        ctx.log("切换账号后账号页面尚未弹出，点击登录首页等待账号页面出现")
                        ctx.device.tap(titleEntry.point.x, titleEntry.point.y)
                        delay(900)
                        continue
                    }

                    if (titleAccountRecoveryAttempts++ >= 3) {
                        return LoginEntry.Failure("切换账号后停留在登录首页，无法重新打开账号页面")
                    }
                    ctx.log("切换账号后只返回登录首页且账号页面未弹出，点击右侧齿轮重新触发")
                    waitingForAccountList = false
                    waitingForUserCenter = true
                    titleAccountRecoveryActive = true
                    titleEntryTapAttempts = 0
                    tapTitleSettingsGear(ctx)
                    ctx.log("进入设置后识别用户中心按钮")
                    tapRecognizedUserCenter(ctx)
                    lastUserCenterTap = ctx.elapsedRealtime()
                    delay(700)
                    continue
                }

                recentAccountWaitRounds++
                if (recentAccountWaitRounds == 1 || recentAccountWaitRounds % 4 == 0) {
                    ctx.log("切换账号后等待账号列表或登录首页稳定")
                }
                delay(500)
                continue
            }
            if (ctx.device.viewInfo("lib_change_account") != null ||
                accountOverlay(ctx, AccountScreenDetector::isUserCenter)
            ) {
                waitingForUserCenter = false
                if (waitingForAccountList) {
                    delay(300)
                    continue
                }
                val shown = ctx.device.viewInfo("lib_account")?.text
                    ?: ctx.device.maskedAccountPhone()
                if (!forceSwitchWithoutVerification && sameSavedPhone(shown)) {
                    loginIdentity.verify(account.id)
                    ctx.log("用户中心显示的账号与所选手机号一致")
                    if (!ctx.device.clickView("lib_close") &&
                        !ctx.device.clickView("lib_goback")
                    ) {
                        tapAccountAnchor(ctx, 211f, 28f)
                    }
                    accountChecked = true
                    delay(600)
                    leaveSettingsAndEnter(ctx)
                } else {
                    loginIdentity.invalidate()
                    if (switchAttempts++ >= 3) {
                        return LoginEntry.Failure("无法切换当前账号")
                    }
                    switchedAccount = true
                    ctx.log(
                        if (forceSwitchWithoutVerification) {
                            "无需核对当前账号，直接点击切换账号"
                        } else {
                            "当前账号未匹配，点击切换账号"
                        },
                    )
                    if (!ctx.device.clickView("lib_change_account")) {
                        val change = ctx.device.viewInfo("lib_change_account")
                        if (change != null) {
                            ctx.device.tap(change.bounds.centerX(), change.bounds.centerY())
                        } else {
                            tapAccountAnchor(ctx, 182f, 88f)
                        }
                    }
                    waitingForAccountList = true
                    lastSwitchTap = ctx.elapsedRealtime()
                }
                delay(500)
                continue
            }
            // Handle settings before popup detection: the account route starts here.
            val gameScreen = ctx.gameScreen()
            if (!accountChecked &&
                !accountFlowActive &&
                (gameScreen == GameScreen.HUD || gameScreen == GameScreen.MENU)
            ) {
                if (settingsOpenAttempts++ >= 4) {
                    return LoginEntry.Failure("已在游戏内，但无法打开用户中心核对账号")
                }
                ctx.log("当前在游戏内，先进入设置和用户中心核对账号")
                if (openUserCenterFromGame(ctx)) {
                    waitingForUserCenter = true
                    lastUserCenterTap = ctx.elapsedRealtime()
                }
                delay(700)
                continue
            }
            if (gameScreen == GameScreen.SETTINGS) {
                if (!accountChecked) {
                    if (userCenterOpenAttempts++ >= 3) {
                        return LoginEntry.Failure("无法打开用户中心核对账号")
                    }
                    ctx.log("设置页已识别，识别并点击用户中心")
                    waitingForUserCenter = true
                    tapRecognizedUserCenter(ctx)
                    lastUserCenterTap = ctx.elapsedRealtime()
                } else {
                    if (settingsExitAttempts++ >= 3) {
                        return LoginEntry.Failure("账号已核对，但无法退出设置页")
                    }
                    leaveSettingsAndEnter(ctx)
                }
                delay(700)
                continue
            }
            when (gameScreen) {
                GameScreen.HUD -> Unit
                GameScreen.MENU -> {
                    val size = ctx.device.screenSize()
                    val scale = size.y / 1080f
                    ctx.device.tap((size.x - 195 * scale).toInt(), (90 * scale).toInt())
                    delay(500)
                    continue
                }
                GameScreen.SETTINGS, GameScreen.OTHER -> Unit
            }
            val titleEntry = ctx.waitUntil(700, 250, TitleScreenDetector::findEntry)
            if (titleEntry != null) {
                if (!accountChecked) {
                    if (settingsOpenAttempts >= 3) {
                        return LoginEntry.Failure("无法从登录首页打开设置")
                    }
                    settingsOpenAttempts++
                    waitingForUserCenter = true
                    ctx.log("识别到登录首页，点击齿轮核对账号")
                    tapTitleSettingsGear(ctx)
                    // The title gear opens settings. Go to User Center directly even if
                    // the generic settings classifier missed the transitional frame.
                    ctx.log("齿轮后识别用户中心按钮")
                    tapRecognizedUserCenter(ctx)
                    lastUserCenterTap = ctx.elapsedRealtime()
                    delay(700)
                } else {
                    ctx.tapTitleEntry(titleEntry.point)
                }
                continue
            }
            if (waitingForUserCenter && ctx.elapsedRealtime() - lastUserCenterTap > 2_000) {
                if (userCenterOpenAttempts++ >= 4) {
                    return LoginEntry.Failure("公告已关闭，但仍无法打开用户中心")
                }
                ctx.log("仍未看到用户中心，重新截图识别按钮后再点击")
                tapRecognizedUserCenter(ctx)
                lastUserCenterTap = ctx.elapsedRealtime()
                delay(700)
                continue
            }
            val next = ctx.waitMatch("pwd_next", 900, 0.78f)
            if (next != null) {
                if (switchedAccount && !otherLoginSelected) {
                    ctx.log("切换账号后尚未选择其他登录方式，先完成该步骤")
                    handleQuickLogin(ctx, true)
                    otherLoginSelected = true
                    delay(700)
                    continue
                }
                // The quick-login page has a blue Login button similar to Next. Recheck
                // it after the transition settles before accepting image fallback.
                if (accountOverlay(ctx, AccountScreenDetector::isQuickLogin)) {
                    ctx.log("蓝色按钮属于最近账号页")
                    handleQuickLogin(ctx, switchedAccount)
                    delay(700)
                    continue
                }
                val phoneField = ctx.waitMatch("pwd_phone", 900, 0.78f)
                if (phoneField != null) {
                    ctx.log("已同时识别手机号输入框和下一步按钮")
                    return LoginEntry.Phone(PhonePage(next.point))
                }
                ctx.log("仅识别到蓝色按钮，等待页面稳定后重新判断")
            }
            delay(300)
        }
    }

    private suspend fun handleQuickLogin(ctx: BotContext, switchedAccount: Boolean) {
        loginIdentity.invalidate()
        if (switchedAccount) {
            ctx.log("已执行切换账号，点击其他登录方式")
            if (!ctx.device.clickText("其他登录方式") &&
                !ctx.device.clickView("lib_other_login_method_layout")
            ) {
                tapAccountAnchor(ctx, 0f, 332f)
            }
        } else {
            ctx.log("未执行切换账号，使用最近账号登录")
            val clicked = ctx.device.clickView("lib_login") ||
                ctx.device.clickView("lib_btn_login") ||
                ctx.device.clickView("lib_login_btn") ||
                ctx.device.clickText("登录")
            if (!clicked) tapAccountAnchor(ctx, 0f, 292f)
        }
    }

    private suspend fun detectPhonePage(ctx: BotContext): PhonePage? {
        if (ctx.device.viewInfo("lib_other_login_method_layout") != null ||
            ctx.device.viewInfo("lib_other_login") != null ||
            accountOverlay(ctx, AccountScreenDetector::isQuickLogin)
        ) {
            return null
        }
        if (ctx.device.viewInfo("lib_account") != null &&
            ctx.device.viewInfo("lib_next") != null
        ) {
            return PhonePage(null)
        }
        val next = ctx.waitMatch("pwd_next", 700, 0.78f) ?: return null
        val phone = ctx.waitMatch("pwd_phone", 700, 0.78f) ?: return null
        return PhonePage(next.point)
    }

    private fun sameSavedPhone(shown: String?): Boolean {
        return AccountIdentity.matches(shown, account.username, savedAccountPhones)
    }

    private suspend fun accountOverlay(ctx: BotContext, detector: (android.graphics.Bitmap) -> Boolean): Boolean {
        val shot = ctx.device.screenshot() ?: return false
        return try {
            detector(shot)
        } finally {
            shot.recycle()
        }
    }

    /** Account dialogs and the settings header are centered and scale with screen height. */
    private suspend fun tapAccountAnchor(ctx: BotContext, dx: Float, y: Float) {
        val size = ctx.device.screenSize()
        val x = (size.x / 2f + dx * size.y / 525f).toInt().coerceIn(0, size.x - 1)
        val py = (y * size.y / 525f).toInt().coerceIn(0, size.y - 1)
        ctx.device.tap(x, py)
    }

    private suspend fun tapRecognizedUserCenter(ctx: BotContext): Boolean {
        val template = ctx.templates.get("settings_user_center") ?: return false
        val button = ctx.waitUntil(1_500, 300) { screen ->
            AccountTransitionScreenDetector.findUserCenter(screen, template)
        }
        if (button == null) {
            ctx.log("当前截图未识别到用户中心按钮，等待页面稳定后重试")
            return false
        }
        ctx.log("识别到用户中心 score=${"%.2f".format(button.score)}，点击 (${button.point.x},${button.point.y})")
        ctx.device.tap(button.point.x, button.point.y)
        return true
    }

    private suspend fun tapTitleSettingsGear(ctx: BotContext) {
        val size = ctx.device.screenSize()
        val x = (size.x - size.y * 62f / 525f).toInt().coerceIn(0, size.x - 1)
        val y = (size.y * 115f / 525f).toInt().coerceIn(0, size.y - 1)
        ctx.device.tap(x, y)
        delay(1_200)
    }

    /** Opens User Center from a resumed HUD or an already expanded game menu. */
    private suspend fun openUserCenterFromGame(ctx: BotContext): Boolean {
        val hudMenu = ctx.hudTemplates() ?: return false
        val settingsMenu = ctx.templates.get("menu_settings_entry") ?: return false
        val userCenter = ctx.templates.get("settings_user_center") ?: return false

        repeat(4) {
            val userCenterButton = ctx.waitUntil(900, 300) { screen ->
                AccountTransitionScreenDetector.findUserCenter(screen, userCenter)
            }
            if (userCenterButton != null) {
                ctx.log("识别到用户中心，点击 (${userCenterButton.point.x},${userCenterButton.point.y})")
                ctx.device.tap(userCenterButton.point.x, userCenterButton.point.y)
                return true
            }

            val settingsButton = ctx.waitUntil(900, 300) { screen ->
                AccountTransitionScreenDetector.findSettingsMenu(screen, settingsMenu)
            }
            if (settingsButton != null) {
                ctx.log("识别到设置入口，点击后继续寻找用户中心")
                ctx.device.tap(settingsButton.point.x, settingsButton.point.y)
                delay(800)
                return@repeat
            }

            val menuButton = ctx.waitUntil(5_000, 300) { screen ->
                AccountTransitionScreenDetector.findHudMenu(screen, hudMenu)
            }
            if (menuButton != null) {
                ctx.log("识别到游戏主界面菜单，点击后继续寻找设置")
                ctx.device.tap(menuButton.point.x, menuButton.point.y)
                delay(700)
                return@repeat
            }
            delay(400)
        }
        return false
    }

    private suspend fun leaveSettingsAndEnter(ctx: BotContext) {
        val size = ctx.device.screenSize()
        // The settings exit is the back arrow at the upper-left of the game view.
        val x = (45f * size.y / 525f).toInt().coerceIn(0, size.x - 1)
        val y = (28f * size.y / 525f).toInt().coerceIn(0, size.y - 1)
        ctx.log("账号已核对，点击设置页左上角返回")
        ctx.device.tap(x, y)
        delay(800)
        ctx.enterFromTitle()
    }

    private suspend fun agreeToTerms(ctx: BotContext, page: PhonePage): Boolean {
        val checkbox = ctx.device.viewInfo("lib_cb_user_protocol")
        if (checkbox != null) {
            if (checkbox.checked) return true
            if (!ctx.device.clickView("lib_cb_user_protocol")) {
                ctx.device.tap(checkbox.bounds.centerX(), checkbox.bounds.centerY())
            }
            repeat(12) {
                delay(250)
                if (ctx.device.viewInfo("lib_cb_user_protocol")?.checked == true) return true
            }
            return false
        }
        val anchor = page.nextPoint ?: return false
        // Relative to the matched Next button, not to MuMu or a device resolution.
        val point = relativeToNext(ctx, anchor, -141f, 32f)
        ctx.device.tap(point.x, point.y)
        delay(350)
        ctx.log("已点协议勾选框；当前设备未开放勾选状态读取")
        return true
    }

    private suspend fun enterPhone(ctx: BotContext, page: PhonePage, phone: String): Boolean {
        val field = ctx.device.viewInfo("lib_account")
        if (samePhoneDigits(field?.text, phone)) {
            ctx.log("手机号已在输入框中")
            return true
        }
        // ACTION_SET_TEXT replaces the field. A formatted or unreadable value must not
        // fall through to key events, or the same digits are appended a second time.
        if (field != null && ctx.device.setViewText("lib_account", phone)) {
            ctx.log("已写入手机号")
            return true
        }
        if (field != null) {
            ctx.device.tap(field.bounds.centerX(), field.bounds.centerY())
        } else {
            val anchor = page.nextPoint ?: return false
            val point = relativeToNext(ctx, anchor, -10f, -44f)
            ctx.device.tap(point.x, point.y)
        }
        delay(250)
        if (!ctx.device.inputText(phone)) return false
        val observed = ctx.device.viewInfo("lib_account")?.text
        if (observed != null && !samePhoneDigits(observed, phone)) return false
        ctx.log(if (samePhoneDigits(observed, phone)) "已确认手机号填入" else "已输入手机号")
        return true
    }

    private fun samePhoneDigits(shown: String?, phone: String): Boolean {
        val digits = shown?.filter(Char::isDigit).orEmpty()
        return digits.isNotEmpty() && digits == phone
    }

    private suspend fun clickNext(ctx: BotContext, page: PhonePage): Boolean {
        if (ctx.device.clickView("lib_next")) {
            delay(700)
            return true
        }
        val native = ctx.device.viewInfo("lib_next")
        if (native != null) {
            ctx.device.tap(native.bounds.centerX(), native.bounds.centerY())
            delay(700)
            return true
        }
        val point = page.nextPoint ?: return false
        ctx.device.tap(point.x, point.y)
        delay(700)
        return true
    }

    private suspend fun waitForPasswordPage(ctx: BotContext, deadline: Long): PasswordPage? {
        while (ctx.elapsedRealtime() < deadline) {
            for (id in listOf("lib_password", "lib_pwd", "lib_login_password")) {
                if (ctx.device.viewInfo(id) != null) {
                    ctx.log("已识别密码输入框")
                    return PasswordPage(id, null)
                }
            }
            val image = ctx.waitMatch("pwd_password", 900, 0.76f)
            if (image != null) {
                ctx.log("已通过图片识别密码页")
                return PasswordPage(null, image.point)
            }
            delay(250)
        }
        return null
    }

    private suspend fun enterPassword(ctx: BotContext, page: PasswordPage): Boolean {
        val id = page.fieldId
        if (id != null && ctx.device.setViewText(id, account.password)) {
            ctx.log("已写入密码")
            return true
        }
        val point = page.fieldPoint ?: return false
        ctx.device.tap(point.x, point.y)
        delay(250)
        if (!ctx.device.inputText(account.password)) return false
        ctx.log("已输入密码")
        return true
    }

    private suspend fun clickLogin(ctx: BotContext): Boolean {
        for (id in listOf("lib_login", "lib_btn_login", "lib_login_btn")) {
            if (ctx.device.clickView(id)) return true
        }
        val button = ctx.waitUntil(2_000, 400, AccountScreenDetector::findPasswordSubmit)
        if (button != null) {
            ctx.log("已识别密码页蓝色登录按钮，点击 (${button.point.x},${button.point.y})")
            ctx.device.tap(button.point.x, button.point.y)
            return true
        }
        val match = ctx.waitMatch("pwd_login", 4_000, 0.76f) ?: return false
        ctx.log("命中 pwd_login score=${"%.2f".format(match.score)}")
        ctx.device.tap(match.point.x, match.point.y)
        return true
    }

    private suspend fun waitEntered(ctx: BotContext, deadline: Long = ctx.deadlineAfter(120_000)): TaskResult {
        return ctx.withLoginCaptureDeadline(deadline) {
            withTimeoutOrNull((deadline - ctx.elapsedRealtime()).coerceAtLeast(1L)) {
                while (ctx.elapsedRealtime() < deadline) {
                    if (ctx.hasEnteredGame(requiredFrames = 3)) {
                        ctx.log("菜单连续确认，已进入游戏主界面")
                        return@withTimeoutOrNull TaskResult(title, true, "已进入游戏主界面")
                    }
                    if (ctx.dismissAnnouncement()) continue
                    if (ctx.dismissRewardRecoveryPopup(allowOcr = true)) continue
                    if (ctx.enterFromTitle()) {
                        continue
                    }
                }
                TaskResult(
                    title,
                    false,
                    "已提交密码，但未能确认进入游戏（${ctx.hudDetectionSummary()}）",
                )
            } ?: TaskResult.uncertain(title, "等待游戏加载超时，未确认进入主界面（${ctx.hudDetectionSummary()}）")
        }
    }

    private fun relativeToNext(ctx: BotContext, next: Point, dx: Float, dy: Float): Point {
        val scale = ctx.device.screenSize().y / TemplateMatcher.LOGIN_REFERENCE_HEIGHT.toFloat()
        return Point((next.x + dx * scale).toInt(), (next.y + dy * scale).toInt())
    }

}
