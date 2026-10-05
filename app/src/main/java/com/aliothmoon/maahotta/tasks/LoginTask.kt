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
import com.aliothmoon.maahotta.vision.TitleScreenDetector
import com.aliothmoon.maahotta.vision.SearchRegion
import com.aliothmoon.maahotta.vision.MatchResult
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
    override val title = when {
        verificationOnly -> "核验当前账号"
        launchGame -> "首次登陆流程"
        else -> "登录账号"
    }
    override fun allowEngineRetry(): Boolean = false
    override fun allowEngineRelogin(): Boolean = false
    override fun isFirstLoginFlow(): Boolean = launchGame && !verificationOnly
    private val loginIdentity = AccountSession()
    private var credentialsSubmitted = false
    private var accountSwitchTriggered = false

    private companion object {
        const val INITIAL_LOAD_ATTEMPTS = 3
        const val INITIAL_LOAD_DELAY_MS = 40_000L
        const val ANNOUNCEMENT_SEARCH_MS = 20_000L
        const val ANNOUNCEMENT_POLL_MS = 2_000L
    }

    private data class PhonePage(val nextPoint: Point?)
    private data class PasswordPage(val fieldId: String?, val fieldPoint: Point?)
    private sealed interface LoginEntry {
        data class Phone(val page: PhonePage) : LoginEntry
        data class Password(val page: PasswordPage, val shownPhone: String?) : LoginEntry
        data class GameEntered(val verifiedAccountId: String? = null) : LoginEntry
        data class Failure(val reason: String, val outcomeUnknown: Boolean = false) : LoginEntry
    }

    override suspend fun run(ctx: BotContext): TaskResult {
        if (isFirstLoginFlow()) return ctx.withFirstLoginRecognition { runAndVerify(ctx) }
        credentialsSubmitted = false
        accountSwitchTriggered = false
        ctx.onTaskFailureRecovery { screen, page ->
            val userCenterVisible = AccountScreenDetector.isUserCenter(screen) ||
                ctx.device.viewInfo("lib_change_account") != null
            val gameVisible = page.state in setOf(
                com.aliothmoon.maahotta.vision.PageState.HUD,
                com.aliothmoon.maahotta.vision.PageState.MENU,
                com.aliothmoon.maahotta.vision.PageState.SETTINGS,
            )
            if (credentialsSubmitted || verificationOnly) {
                // A submitted password may only be audited; input pages never replay credentials.
                if (!userCenterVisible && !gameVisible) null
                else {
                    val audited = verifyExistingAccount(ctx)
                    if (audited.ok && ctx.safety.pendingStepId != null) ctx.confirmActionResult()
                    publishVerifiedResult(ctx, audited)
                }
            } else {
                val knownAccountPage = page.state == com.aliothmoon.maahotta.vision.PageState.LOGIN_ACCOUNT ||
                    userCenterVisible || AccountScreenDetector.isAccountList(screen) ||
                    AccountScreenDetector.isQuickLogin(screen) ||
                    ctx.device.viewInfo("lib_account") != null ||
                    listOf("lib_password", "lib_pwd", "lib_login_password").any { ctx.device.viewInfo(it) != null } ||
                    TitleScreenDetector.findUnobstructedEntry(screen) != null
                if (gameVisible || knownAccountPage) runAndVerify(ctx) else null
            }
        }
        return runAndVerify(ctx)
    }

    private suspend fun runAndVerify(ctx: BotContext): TaskResult {
        loginIdentity.invalidate()
        ctx.invalidateAccountIdentity()
        val login = if (verificationOnly) verifyExistingAccount(ctx) else runLogin(ctx)
        // Reuse only this attempt's User Center proof; submitting credentials requires a fresh audit.
        val result = if (!verificationOnly && login.ok && !loginIdentity.isVerifiedFor(account.id)) {
            if (isFirstLoginFlow()) {
                TaskResult.uncertain(title, "首次登陆流程未取得当前账号核验证据，停止后续任务")
            } else {
                ctx.log("主界面已确认，开始核验当前账号")
                verifyExistingAccount(ctx)
            }
        } else login
        return publishVerifiedResult(ctx, result)
    }

    private fun publishVerifiedResult(ctx: BotContext, result: TaskResult): TaskResult {
        if (result.ok) {
            if (!isFirstLoginFlow() && ctx.safety.pendingStepId == "login:credentials") {
                ctx.confirmActionResult()
            }
            ctx.confirmAccountIdentity(account.id)
            val source = if (forceSwitchWithoutVerification) "切换账号登陆" else title
            if (ctx.rememberLoginMenuForCheckIn(account.id, source)) {
                ctx.log("登陆结果菜单已保存，供每日签到复用")
            }
            ctx.log("账号身份与主界面均已确认，登录完成")
        }
        return result
    }

    /** Disabling automatic login still requires identity proof, but never switches or enters credentials. */
    private suspend fun verifyExistingAccount(ctx: BotContext): TaskResult {
        ctx.resetHudDetection()
        return ctx.withLoginCaptureWaiting verification@{
            awaitGameCapture(ctx)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (ctx.device.viewInfo("lib_change_account") != null ||
                    accountOverlay(ctx, AccountScreenDetector::isUserCenter)) break
                if (isFirstLoginFlow() && tapRecognizedUserCenter(ctx)) break
                if (ctx.gameScreen() in setOf(GameScreen.HUD, GameScreen.MENU, GameScreen.SETTINGS)) {
                    if (!openUserCenterFromGame(ctx)) {
                        return@verification TaskResult.uncertain(title, "已确认游戏界面，但未能进入用户中心核验账号")
                    }
                    break
                }
                delay(500)
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val center = ctx.device.viewInfo("lib_change_account") != null ||
                    accountOverlay(ctx, AccountScreenDetector::isUserCenter)
                if (center) {
                    val shown = ctx.device.viewInfo("lib_account")?.text ?: ctx.device.maskedAccountPhone()
                    if (sameSavedPhone(shown)) break
                    if (shown?.contains(Regex("[0-9]{3}\\*+[0-9]{4}|[0-9]{11}")) == true) {
                        return@verification TaskResult.uncertain(title, "当前账号与所选账号不一致或掩码存在冲突")
                    }
                }
                delay(400)
            }
            requireLoginStep(ctx, "login:close_user_center")
            if (!ctx.device.clickView("lib_close") && !ctx.device.clickView("lib_goback")) {
                tapAccountAnchor(ctx, 211f, 28f)
            }
            var centerCloseSubmittedAt = ctx.elapsedRealtime()
            var centerCloseRetried = false
            while (true) {
                currentCoroutineContext().ensureActive()
                delay(400)
                val screen = ctx.device.screenshot() ?: continue
                val stillVisible = try {
                    AccountScreenDetector.isUserCenter(screen) || ctx.device.viewInfo("lib_change_account") != null
                } finally { screen.recycle() }
                if (stillVisible) {
                    if (!isFirstLoginFlow() && ctx.elapsedRealtime() - centerCloseSubmittedAt >= 1_500) {
                        if (centerCloseRetried) return@verification TaskResult.uncertain(
                            title, "账号已核对，但用户中心关闭重试1次后仍未消失",
                        )
                        requireLoginStep(ctx, "login:close_user_center")
                        ctx.log("用户中心仍在，重新识别关闭控件后重试一次")
                        if (!ctx.device.clickView("lib_close") && !ctx.device.clickView("lib_goback")) {
                            tapAccountAnchor(ctx, 211f, 28f)
                        }
                        centerCloseRetried = true
                        centerCloseSubmittedAt = ctx.elapsedRealtime()
                    }
                    continue
                }
                if (isFirstLoginFlow()) {
                    if (ctx.hasEnteredGame(requiredFrames = 1)) break
                    if (findUserCenterButton(ctx, 700) != null) {
                        leaveSettingsAndEnter(ctx)
                        break
                    }
                    continue
                }
                when (ctx.gameScreen()) {
                    GameScreen.SETTINGS -> { leaveSettingsAndEnter(ctx); break }
                    GameScreen.HUD -> break
                    else -> Unit
                }
            }
            waitEntered(ctx)
        }
    }

    private suspend fun runLogin(ctx: BotContext): TaskResult {
        ctx.resetHudDetection()
        if (launchGame) {
            // Only the first launch waits/restarts. Account transitions always pass launchGame=false.
            loadGame(ctx)?.let { return it }
        } else {
            ctx.log(
                if (forceSwitchWithoutVerification) "从用户中心直接切换到下一账号"
                else "游戏已启动，继续识别当前登录状态",
            )
        }
        // Navigation and capture readiness have no overall timeout; stopping cancels every wait.
        return ctx.withLoginCaptureWaiting login@{
            awaitGameCapture(ctx)
            val entry = waitForLoginEntry(ctx, forceSwitchWithoutVerification && !accountSwitchTriggered)
            if (entry is LoginEntry.GameEntered) {
                if (entry.verifiedAccountId != account.id) loginIdentity.invalidate()
                return@login TaskResult(title, true, "已进入游戏主界面")
            }
            if (entry is LoginEntry.Failure) {
                return@login if (entry.outcomeUnknown) TaskResult.uncertain(title, entry.reason)
                    else TaskResult(title, false, entry.reason)
            }
            loginIdentity.invalidate()
            ctx.resetHudDetection()
            // Submit credentials only once. Missing frames never replay input.
            if (entry is LoginEntry.Password) {
                if (!sameSavedPhone(entry.shownPhone)) {
                    return@login TaskResult(title, false, "密码页账号与所选手机号不一致或无法核对")
                }
                ctx.log("密码页显示的账号与所选手机号一致")
                return@login completePasswordLogin(ctx, entry.page)
            }
            val phonePage = (entry as LoginEntry.Phone).page
            val phone = account.username.trim().removePrefix("+86")
            if (phone.isEmpty() || !phone.all { it in '0'..'9' }) {
                return@login TaskResult(title, false, "通行证账号须填写不含区号的手机号")
            }
            if (account.password.isEmpty()) {
                return@login TaskResult(title, false, "未填写密码")
            }
            if (!agreeToTerms(ctx, phonePage)) {
                return@login TaskResult(title, false, "无法确认用户协议已勾选")
            }
            if (!enterPhone(ctx, phonePage, phone)) {
                return@login TaskResult(title, false, "手机号未能写入输入框")
            }
            if (!clickNext(ctx, phonePage)) {
                return@login TaskResult(title, false, "找不到“下一步”按钮")
            }
            val passwordPage = waitForPasswordPage(ctx)
            completePasswordLogin(ctx, passwordPage)
        }
    }

    /** Returns a failure only after all three launch attempts; credentials are never replayed here. */
    private suspend fun loadGame(ctx: BotContext): TaskResult? {
        var lastFailure = "未识别到游戏公告"
        repeat(INITIAL_LOAD_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            ctx.log("加载游戏：第 ${attempt + 1}/$INITIAL_LOAD_ATTEMPTS 次，强制关闭并重新启动游戏")
            if (!ctx.device.launchApp(Packages.OFFICIAL, forceStop = true)) {
                lastFailure = "无法强制重启游戏，请检查 Shizuku 授权和游戏安装状态"
                ctx.log("加载游戏：$lastFailure")
            } else {
                ctx.log("加载游戏：固定等待40秒")
                delay(INITIAL_LOAD_DELAY_MS)
                ctx.log("加载游戏：每2秒一轮查找游戏公告，最多查找20秒；固定等待加查找共60秒")
                val announcementClose = waitForGameAnnouncement(ctx)
                if (announcementClose != null) {
                    ctx.log("加载游戏：已确认游戏公告，直接使用本帧位置关闭 X")
                    // No new pre-click capture: use the X located by the successful loading probe.
                    val closed = ctx.closeConfirmedAnnouncement(announcementClose, logPrefix = "加载游戏：")
                    ctx.log(
                        if (closed) "加载游戏：公告已关闭，继续登录"
                        else "加载游戏：公告仍在，交由原公告关闭流程继续识别处理",
                    )
                    return null
                }
                lastFailure = "固定等待40秒后查找20秒仍未识别到游戏公告"
                ctx.log("加载游戏：$lastFailure")
            }
            if (attempt + 1 < INITIAL_LOAD_ATTEMPTS) {
                ctx.log("加载游戏：准备重启重试")
            }
        }
        return TaskResult(
            title, false,
            "加载游戏失败：共尝试$INITIAL_LOAD_ATTEMPTS 次，$lastFailure，已放弃首次启动",
            retryable = false,
        )
    }

    private suspend fun waitForGameAnnouncement(ctx: BotContext): Point? {
        val deadline = ctx.elapsedRealtime() + ANNOUNCEMENT_SEARCH_MS
        // Avoid waitUntil/deadlineAfter: their grace period would extend this fixed 20-second search window.
        return withTimeoutOrNull(ANNOUNCEMENT_SEARCH_MS) {
            var round = 0
            while (ctx.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                val roundStartedAt = ctx.elapsedRealtime()
                val roundBudget = minOf(ANNOUNCEMENT_POLL_MS, deadline - roundStartedAt)
                if (roundBudget <= 0) break
                round++
                val found = withTimeoutOrNull(roundBudget) {
                    ctx.findGameAnnouncement()
                }
                if (found != null && ctx.elapsedRealtime() < deadline) return@withTimeoutOrNull found
                ctx.log("加载游戏：第 $round 轮未确认游戏公告，继续等待")
                // Recognition time is part of the interval, rather than an extra two-second delay.
                val nextRoundAt = minOf(deadline, roundStartedAt + ANNOUNCEMENT_POLL_MS)
                val remaining = nextRoundAt - ctx.elapsedRealtime()
                if (remaining > 0) delay(remaining)
            }
            null
        }
    }

    private suspend fun completePasswordLogin(ctx: BotContext, passwordPage: PasswordPage): TaskResult {
        if (!enterPassword(ctx, passwordPage)) {
            return TaskResult(title, false, "密码未能写入输入框")
        }
        if (!clickLogin(ctx)) {
            return TaskResult(title, false, "找不到密码页的登录按钮")
        }
        waitForPasswordPageExit(ctx)
        if (isFirstLoginFlow()) {
            ctx.log("已确认离开密码页，先核验当前账号，再进入游戏")
            return when (val entry = waitForLoginEntry(ctx, false, verificationAfterSubmission = true)) {
                is LoginEntry.GameEntered -> if (entry.verifiedAccountId == account.id) {
                    TaskResult(title, true, "账号已核验，已识别到游戏主界面菜单")
                } else TaskResult.uncertain(title, "首次登陆流程未确认当前账号身份")
                is LoginEntry.Failure -> TaskResult.uncertain(title, entry.reason)
                else -> TaskResult.uncertain(title, "提交登录后仍出现登录输入页面，停止且不重复提交")
            }
        }
        ctx.log("已确认离开密码页，继续验证进入游戏")
        return waitEntered(ctx)
    }

    private suspend fun waitForPasswordPageExit(ctx: BotContext) {
        var absentFrames = 0
        val passwordTemplate = ctx.templates.get("pwd_password")
        while (true) {
            currentCoroutineContext().ensureActive()
            delay(500)
            val screen = ctx.device.screenshot()
            if (screen == null) {
                absentFrames = 0
                continue
            }
            val passwordPageVisible = try {
                val passwordFieldVisible = listOf("lib_password", "lib_pwd", "lib_login_password")
                    .any { ctx.device.viewInfo(it) != null }
                passwordFieldVisible || AccountScreenDetector.findPasswordSubmit(screen) != null ||
                    (passwordTemplate != null && TemplateMatcher.match(
                        screen,
                        passwordTemplate,
                        0.76f,
                        region = SearchRegion(0.20f, 0.12f, 0.80f, 0.88f),
                        referenceHeight = TemplateMatcher.LOGIN_REFERENCE_HEIGHT,
                    ) != null)
            } finally {
                screen.recycle()
            }
            absentFrames = if (passwordPageVisible) 0 else absentFrames + 1
            if (absentFrames >= 2) return
        }
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
        verificationAfterSubmission: Boolean = false,
    ): LoginEntry {
        var accountChecked = false
        var waitingForUserCenter = false
        var lastUserCenterTap = 0L
        var settingsOpenAttempts = 0
        var userCenterOpenAttempts = 0
        var settingsExitAttempts = 0
        var switchAttempts = 0
        var switchedAccount = accountSwitchTriggered
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
                if (ctx.hasEnteredGame(requiredFrames = if (isFirstLoginFlow()) 1 else 3)) {
                    if (forceSwitchWithoutVerification && !switchedAccount) {
                        return LoginEntry.Failure("尚未执行切换账号就返回了游戏主界面")
                    }
                    if (isFirstLoginFlow()) {
                        ctx.log("账号已核对，当前已在游戏场景，进入首次加载的最终确认")
                        ctx.awaitFirstLoginGameMenu()
                    } else ctx.log("菜单连续确认，已进入游戏主界面")
                    return LoginEntry.GameEntered(loginIdentity.verifiedAccountId)
                }
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
            if (ctx.dismissRewardRecoveryPopup()) continue
            if (!accountChecked && !accountFlowActive && accountOverlay(ctx) {
                    TitleScreenDetector.findUnobstructedEntry(it, checkBlockingPopups = !isFirstLoginFlow()) != null
                }) {
                if (settingsOpenAttempts++ >= retryLimit(3)) return LoginEntry.Failure("无法从登陆首页打开设置")
                waitingForUserCenter = true
                ctx.log("已确认无遮挡登陆首页，点击齿轮核对账号")
                tapTitleSettingsGear(ctx)
                ctx.log("齿轮后识别用户中心按钮")
                tapRecognizedUserCenter(ctx)
                lastUserCenterTap = ctx.elapsedRealtime()
                delay(700)
                continue
            }
            if (waitingForOtherLogin) {
                if (verificationAfterSubmission) {
                    return LoginEntry.Failure("提交登录后仍出现账号登录页面，停止且不重复提交", outcomeUnknown = true)
                }
                val otherLoginVisible = ctx.device.viewInfo("lib_other_login_method_layout") != null ||
                    ctx.device.viewInfo("lib_other_login") != null ||
                    accountOverlay(ctx, AccountScreenDetector::isQuickLogin)
                if (otherLoginVisible) {
                    ctx.log("添加新账号后，点击其他登录方式")
                    requireLoginStep(ctx, "login:other_method")
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
                        if (addAccountAttempts++ >= retryLimit(3)) {
                            return LoginEntry.Failure("多次点击添加新账号后仍停留在账号列表")
                        }
                        ctx.log("最近账号页未出现，仍在账号列表，重新点击添加新账号")
                        requireLoginStep(ctx, "login:add_account")
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
                if (verificationAfterSubmission) {
                    return LoginEntry.Failure("提交登录后返回账号列表，停止且不再次添加账号", outcomeUnknown = true)
                }
                loginIdentity.invalidate()
                if (addAccountAttempts++ >= retryLimit(3)) {
                    return LoginEntry.Failure("无法从账号列表进入添加新账号")
                }
                ctx.log("识别到账号列表，点击添加新账号")
                requireLoginStep(ctx, "login:add_account")
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
                if (verificationAfterSubmission) {
                    return LoginEntry.Failure("提交登录后仍出现快捷登录页，停止且不重复点击登录", outcomeUnknown = true)
                }
                if (otherLoginAttempts++ >= retryLimit(3)) {
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
                        if (titleEntryTapAttempts++ >= retryLimit(5)) {
                            if (titleAccountRecoveryAttempts >= retryLimit(3)) {
                                return LoginEntry.Failure("切换账号后多次点击登陆首页仍未弹出账号页面")
                            }
                            ctx.log("多次点击登陆首页仍未弹出账号页面，重新通过齿轮和用户中心触发切换账号")
                            titleAccountRecoveryActive = false
                            titleEntryTapAttempts = 0
                            delay(400)
                            continue
                        }
                        ctx.log("切换账号后账号页面尚未弹出，点击登陆首页等待账号页面出现")
                        requireLoginStep(ctx, "login:title_entry")
                        ctx.device.tap(titleEntry.point.x, titleEntry.point.y)
                        delay(900)
                        continue
                    }

                    if (titleAccountRecoveryAttempts++ >= retryLimit(3)) {
                        return LoginEntry.Failure("切换账号后停留在登陆首页，无法重新打开账号页面")
                    }
                    ctx.log("切换账号后只返回登陆首页且账号页面未弹出，点击右侧齿轮重新触发")
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
                    ctx.log("切换账号后等待账号列表或登陆首页稳定")
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
                    requireLoginStep(ctx, "login:close_user_center")
                    if (!ctx.device.clickView("lib_close") &&
                        !ctx.device.clickView("lib_goback")
                    ) {
                        tapAccountAnchor(ctx, 211f, 28f)
                    }
                    accountChecked = true
                    delay(600)
                    val enteredFromTitle = leaveSettingsAndEnter(ctx)
                    if (isFirstLoginFlow() && enteredFromTitle) {
                        return LoginEntry.GameEntered(loginIdentity.verifiedAccountId)
                    }
                } else {
                    if (verificationAfterSubmission) {
                        if (shown?.contains(Regex("[0-9]{3}\\*+[0-9]{4}|[0-9]{11}")) == true) {
                            return LoginEntry.Failure("提交登录后当前账号不匹配或掩码存在冲突", outcomeUnknown = true)
                        }
                        delay(500)
                        continue
                    }
                    loginIdentity.invalidate()
                    if (switchAttempts++ >= retryLimit(3)) {
                        return LoginEntry.Failure("无法切换当前账号")
                    }
                    requireLoginStep(ctx, "login:switch_account")
                    switchedAccount = true
                    accountSwitchTriggered = true
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
            if (isFirstLoginFlow() && !accountFlowActive) {
                val userCenterButton = findUserCenterButton(ctx, 700)
                if (userCenterButton != null) {
                    if (accountChecked) {
                        if (settingsExitAttempts++ >= retryLimit(3)) {
                            return LoginEntry.Failure("账号已核对，但无法退出设置页")
                        }
                        if (leaveSettingsAndEnter(ctx) && isFirstLoginFlow()) {
                            return LoginEntry.GameEntered(loginIdentity.verifiedAccountId)
                        }
                    } else {
                        if (userCenterOpenAttempts++ >= retryLimit(3)) {
                            return LoginEntry.Failure("无法打开用户中心核对账号")
                        }
                        ctx.log("首次登陆流程：识别到用户中心按钮，点击核对账号")
                        ctx.device.tap(userCenterButton.point.x, userCenterButton.point.y)
                        waitingForUserCenter = true
                        lastUserCenterTap = ctx.elapsedRealtime()
                    }
                    delay(700)
                    continue
                }
            }
            // Outside the first-login scope, keep the existing MENU/Settings routing.
            val gameScreen = ctx.gameScreen()
            if (!accountChecked &&
                !accountFlowActive &&
                (gameScreen == GameScreen.HUD || gameScreen == GameScreen.MENU)
            ) {
                if (settingsOpenAttempts++ >= retryLimit(4)) {
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
                    if (userCenterOpenAttempts++ >= retryLimit(3)) {
                        return LoginEntry.Failure("无法打开用户中心核对账号")
                    }
                    ctx.log("设置页已识别，识别并点击用户中心")
                    waitingForUserCenter = true
                    tapRecognizedUserCenter(ctx)
                    lastUserCenterTap = ctx.elapsedRealtime()
                } else {
                    if (settingsExitAttempts++ >= retryLimit(3)) {
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
                    requireLoginStep(ctx, "navigation:close_menu")
                    ctx.device.tap((size.x - 195 * scale).toInt(), (90 * scale).toInt())
                    delay(500)
                    continue
                }
                GameScreen.SETTINGS, GameScreen.OTHER -> Unit
            }
            val titleEntry = ctx.waitUntil(700, 250, TitleScreenDetector::findEntry)
            if (titleEntry != null) {
                if (!accountChecked) {
                    if (settingsOpenAttempts >= retryLimit(3)) {
                        return LoginEntry.Failure("无法从登陆首页打开设置")
                    }
                    settingsOpenAttempts++
                    waitingForUserCenter = true
                    ctx.log("识别到登陆首页，点击齿轮核对账号")
                    tapTitleSettingsGear(ctx)
                    // The title gear opens settings. Go to User Center directly even if
                    // the generic settings classifier missed the transitional frame.
                    ctx.log("齿轮后识别用户中心按钮")
                    tapRecognizedUserCenter(ctx)
                    lastUserCenterTap = ctx.elapsedRealtime()
                    delay(700)
                } else {
                    if (ctx.tapTitleEntry(titleEntry.point) && isFirstLoginFlow()) {
                        return LoginEntry.GameEntered(loginIdentity.verifiedAccountId)
                    }
                }
                continue
            }
            if (waitingForUserCenter && ctx.elapsedRealtime() - lastUserCenterTap > 2_000) {
                if (userCenterOpenAttempts++ >= retryLimit(4)) {
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
            requireLoginStep(ctx, "login:other_method")
            if (!ctx.device.clickText("其他登录方式") &&
                !ctx.device.clickView("lib_other_login_method_layout")
            ) {
                tapAccountAnchor(ctx, 0f, 332f)
            }
        } else {
            ctx.log("未执行切换账号，使用最近账号登录")
            requireLoginStep(ctx, "login:recent_login")
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

    private suspend fun findUserCenterButton(ctx: BotContext, timeoutMs: Long = 1_500): MatchResult? {
        val template = ctx.templates.get("settings_user_center") ?: return null
        return ctx.waitUntil(timeoutMs, 300) { screen ->
            AccountTransitionScreenDetector.findUserCenter(screen, template)
        }
    }

    private suspend fun tapRecognizedUserCenter(ctx: BotContext): Boolean {
        val button = findUserCenterButton(ctx)
        if (button == null) {
            ctx.log("当前截图未识别到用户中心按钮，等待页面稳定后重试")
            return false
        }
        ctx.log("识别到用户中心 score=${"%.2f".format(button.score)}，点击 (${button.point.x},${button.point.y})")
        requireLoginStep(ctx, "account:open_user_center")
        ctx.device.tap(button.point.x, button.point.y)
        return true
    }

    private suspend fun tapTitleSettingsGear(ctx: BotContext) {
        val size = ctx.device.screenSize()
        val x = (size.x - size.y * 62f / 525f).toInt().coerceIn(0, size.x - 1)
        val y = (size.y * 115f / 525f).toInt().coerceIn(0, size.y - 1)
        requireLoginStep(ctx, "login:title_settings")
        ctx.device.tap(x, y)
        delay(1_200)
    }

    /** Opens User Center from a resumed HUD or an already expanded game menu. */
    private suspend fun openUserCenterFromGame(ctx: BotContext): Boolean {
        if (isFirstLoginFlow()) return openFirstLoginUserCenter(ctx)
        val hudMenu = ctx.hudTemplates() ?: return false
        val settingsMenu = ctx.templates.get("menu_settings_entry") ?: return false
        val userCenter = ctx.templates.get("settings_user_center") ?: return false

        repeat(4) {
            val userCenterButton = ctx.waitUntil(900, 300) { screen ->
                AccountTransitionScreenDetector.findUserCenter(screen, userCenter)
            }
            if (userCenterButton != null) {
                ctx.log("识别到用户中心，点击 (${userCenterButton.point.x},${userCenterButton.point.y})")
                requireLoginStep(ctx, "account:open_user_center")
                ctx.device.tap(userCenterButton.point.x, userCenterButton.point.y)
                return true
            }

            val settingsButton = ctx.waitUntil(900, 300) { screen ->
                AccountTransitionScreenDetector.findSettingsMenu(screen, settingsMenu)
            }
            if (settingsButton != null) {
                ctx.log("识别到设置入口，点击后继续寻找用户中心")
                requireLoginStep(ctx, "account:open_settings")
                ctx.device.tap(settingsButton.point.x, settingsButton.point.y)
                delay(800)
                return@repeat
            }

            val menuButton = ctx.waitUntil(5_000, 300) { screen ->
                AccountTransitionScreenDetector.findHudMenu(screen, hudMenu)
            }
            if (menuButton != null) {
                ctx.log("识别到游戏主界面菜单，点击后继续寻找设置")
                requireLoginStep(ctx, "navigation:open_menu")
                ctx.device.tap(menuButton.point.x, menuButton.point.y)
                delay(700)
                return@repeat
            }
            delay(400)
        }
        return false
    }

    /** Menu opening and Settings use the action sequence; only HUD and User Center are recognized. */
    private suspend fun openFirstLoginUserCenter(ctx: BotContext): Boolean {
        if (ctx.hudTemplates() == null || ctx.templates.get("settings_user_center") == null) return false
        var menuOpened = false
        repeat(4) {
            if (ctx.dismissAnnouncement()) return@repeat
            if (tapRecognizedUserCenter(ctx)) return true
            if (!menuOpened) {
                val menu = ctx.waitUntil(5_000, 300) { screen -> ctx.inspectHud(screen).menu }
                    ?: return@repeat
                ctx.log("首次登陆流程：识别到主界面菜单，点击后按顺序进入设置")
                ctx.device.tap(menu.point.x, menu.point.y)
                menuOpened = true
                delay(700)
            }
            val size = ctx.device.screenSize()
            // Current 1280x720 menu: Settings is centered at (1076,420), anchored to the right.
            val x = (size.x - size.y * 204f / 720f).toInt().coerceIn(0, size.x - 1)
            val y = (size.y * 420f / 720f).toInt().coerceIn(0, size.y - 1)
            ctx.log("首次登陆流程：固定坐标点击设置 ($x,$y)")
            ctx.device.tap(x, y)
            delay(800)
        }
        // The last Settings click still needs its retained User Center result check.
        return tapRecognizedUserCenter(ctx)
    }

    private suspend fun leaveSettingsAndEnter(ctx: BotContext): Boolean {
        val size = ctx.device.screenSize()
        // The settings exit is the back arrow at the upper-left of the game view.
        val x = (45f * size.y / 525f).toInt().coerceIn(0, size.x - 1)
        val y = (28f * size.y / 525f).toInt().coerceIn(0, size.y - 1)
        ctx.log("账号已核对，点击设置页左上角返回")
        requireLoginStep(ctx, "account:back")
        ctx.device.tap(x, y)
        delay(800)
        return ctx.enterFromTitle()
    }

    private suspend fun agreeToTerms(ctx: BotContext, page: PhonePage): Boolean {
        val checkbox = ctx.device.viewInfo("lib_cb_user_protocol")
        if (checkbox != null) {
            if (checkbox.checked) return true
            requireLoginStep(ctx, "login:agree_terms")
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
        requireLoginStep(ctx, "login:agree_terms")
        ctx.device.tap(point.x, point.y)
        delay(350)
        ctx.log("已点协议勾选框；当前设备未开放勾选状态读取")
        return true
    }

    private suspend fun enterPhone(ctx: BotContext, page: PhonePage, phone: String): Boolean {
        val field = ctx.device.viewInfo("lib_account")
        if (field?.text == phone) return true
        requireLoginStep(ctx, "login:enter_phone")
        if (field != null && ctx.device.setViewText("lib_account", phone)) {
            delay(250)
            if (ctx.device.viewInfo("lib_account")?.text == phone) {
                ctx.log("已确认手机号填入")
                return true
            }
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
        delay(250)
        val observed = ctx.device.viewInfo("lib_account")?.text
        if (observed != null && observed != phone) return false
        ctx.log(if (observed == phone) "已确认手机号填入" else "手机号已输入，无法读取当前控件值")
        return true
    }

    private suspend fun clickNext(ctx: BotContext, page: PhonePage): Boolean {
        requireLoginStep(ctx, "login:next")
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

    private suspend fun waitForPasswordPage(ctx: BotContext): PasswordPage {
        while (true) {
            currentCoroutineContext().ensureActive()
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
    }

    private suspend fun enterPassword(ctx: BotContext, page: PasswordPage): Boolean {
        requireLoginStep(ctx, "login:enter_password")
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
        requireLoginStep(ctx, "login:submit_password")
        for (id in listOf("lib_login", "lib_btn_login", "lib_login_btn")) {
            markCredentialsSubmitted(ctx)
            if (ctx.device.clickView(id)) return true
        }
        val button = ctx.waitUntil(2_000, 400, AccountScreenDetector::findPasswordSubmit)
        if (button != null) {
            ctx.log("已识别密码页蓝色登录按钮，点击 (${button.point.x},${button.point.y})")
            markCredentialsSubmitted(ctx)
            ctx.device.tap(button.point.x, button.point.y)
            return true
        }
        val match = ctx.waitMatch("pwd_login", 4_000, 0.76f) ?: return false
        ctx.log("命中 pwd_login score=${"%.2f".format(match.score)}")
        markCredentialsSubmitted(ctx)
        ctx.device.tap(match.point.x, match.point.y)
        return true
    }

    private suspend fun waitEntered(ctx: BotContext): TaskResult {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (ctx.hasEnteredGame(requiredFrames = if (isFirstLoginFlow()) 1 else 3)) {
                if (isFirstLoginFlow()) ctx.awaitFirstLoginGameMenu()
                else ctx.log("菜单连续确认，已进入游戏主界面")
                return TaskResult(title, true, "已进入游戏主界面")
            }
            if (ctx.dismissAnnouncement()) continue
            if (ctx.dismissRewardRecoveryPopup()) continue
            if (ctx.enterFromTitle()) {
                if (isFirstLoginFlow()) return TaskResult(title, true, "已识别到游戏主界面菜单")
                continue
            }
            delay(500)
        }
    }

    private fun relativeToNext(ctx: BotContext, next: Point, dx: Float, dy: Float): Point {
        val scale = ctx.device.screenSize().y / TemplateMatcher.LOGIN_REFERENCE_HEIGHT.toFloat()
        return Point((next.x + dx * scale).toInt(), (next.y + dy * scale).toInt())
    }

    private fun retryLimit(firstLoginLimit: Int): Int = if (isFirstLoginFlow()) firstLoginLimit else 2

    private fun requireLoginStep(ctx: BotContext, stepId: String) {
        if (!isFirstLoginFlow() && !ctx.tryTaskStep(stepId)) {
            throw IllegalStateException("登录步骤 $stepId 重试1次后仍失败")
        }
    }

    private fun markCredentialsSubmitted(ctx: BotContext) {
        if (isFirstLoginFlow() || credentialsSubmitted) return
        credentialsSubmitted = true
        ctx.markActionSubmitted("login:credentials")
    }

}
