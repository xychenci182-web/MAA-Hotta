package com.aliothmoon.maahotta.vision

import android.graphics.Bitmap
import android.graphics.Point

data class PageObservation(val state: PageState, val controls: Map<String, MatchResult> = emptyMap())

/** All evidence and click targets belong to the same screenshot. Specific pages precede HUD. */
object PageStateDetector {
    /** Extra failure-only checks do not add work to each normal task-navigation frame. */
    fun inspectForRecovery(screen: Bitmap, template: (String) -> Bitmap?, hud: HudTemplates?,
        hudExclusions: HudExclusions = HudExclusions.TIMER): PageObservation {
        val lineTitle = template("line_switch_title")?.let {
            TemplateMatcher.match(screen, it, threshold = 0.80f,
                region = SearchRegion(0.12f, 0.16f, 0.50f, 0.38f), referenceHeight = 561)
        }
        if (lineTitle != null) {
            val cancel = template("line_switch_cancel")?.let {
                TemplateMatcher.match(screen, it, threshold = 0.78f,
                    region = SearchRegion(0.20f, 0.54f, 0.53f, 0.79f), referenceHeight = 561)
            } ?: MatchResult(Point((screen.width / 2f - screen.height * 0.18f).toInt(),
                (screen.height * 0.665f).toInt()), 1f)
            return PageObservation(PageState.LINE_SELECTION, mapOf("cancel" to cancel))
        }
        if (AnnouncementDetector.hasLayout(screen)) {
            val heading = template("announcement_title")?.let {
                TemplateMatcher.match(screen, it, threshold = 0.64f,
                    region = SearchRegion(screen.height * 0.03f / screen.width, 0.09f,
                        screen.height * 0.70f / screen.width, 0.29f),
                    referenceHeight = TemplateMatcher.LOGIN_REFERENCE_HEIGHT)
            }
            if (heading != null) {
                val close = AnnouncementDetector.findClose(screen) ?: template("announcement_close")?.let {
                    TemplateMatcher.match(screen, it, threshold = 0.60f,
                        region = SearchRegion(1f - screen.height * 0.42f / screen.width, 0.07f,
                            1f - screen.height * 0.01f / screen.width, 0.32f),
                        referenceHeight = TemplateMatcher.LOGIN_REFERENCE_HEIGHT)
                }
                return PageObservation(PageState.ANNOUNCEMENT,
                    close?.let { mapOf("close" to it) } ?: emptyMap())
            }
        }
        // These panels must win over a HUD icon still visible behind them.
        if (AccountScreenDetector.isUserCenter(screen) || AccountScreenDetector.isQuickLogin(screen) ||
            AccountScreenDetector.isAccountList(screen) || AccountScreenDetector.findPasswordSubmit(screen) != null) {
            return PageObservation(PageState.LOGIN_ACCOUNT)
        }
        TrialsScreenDetector.findResultSuccess(screen, template("trials_result_success"))?.takeIf { it.score >= 0.70f }?.let {
            return PageObservation(PageState.TRIALS_RESULT)
        }
        if (CheckInScreenDetector.isRewardPopup(screen)) return PageObservation(PageState.REWARD)
        TitleScreenDetector.findUnobstructedEntry(screen)?.let {
            return PageObservation(PageState.LOGIN_TITLE, mapOf("entry" to it))
        }
        return inspect(screen, template, hud, hudExclusions = hudExclusions)
    }

    fun inspect(screen: Bitmap, template: (String) -> Bitmap?, hud: HudTemplates?, focus: NavigationGoal? = null,
        hudExclusions: HudExclusions = HudExclusions.TIMER): PageObservation {
        fun observed(state: PageState, vararg controls: Pair<String, MatchResult?>) =
            PageObservation(state, controls.mapNotNull { (key, hit) -> hit?.let { key to it } }.toMap())
        fun expandedMenu(): PageObservation? {
            val social = MailScreenDetector.findSocialMenu(screen, template("menu_social_entry"))
            val guild = GuildScreenDetector.findMenuGuild(screen, template("guild_menu_entry"))
            if (social != null && guild != null)
                return observed(PageState.MENU, "social" to social, "guild" to guild)
            val settings = AccountTransitionScreenDetector.findSettingsMenu(screen, template("menu_settings_entry"))
            return if (listOfNotNull(social, guild, settings).size >= 2)
                observed(PageState.MENU, "social" to social, "guild" to guild, "settings" to settings)
            else null
        }
        fun selectedGuildPage(): PageObservation? {
            if (GuildScreenDetector.isInfoTabSelected(screen)) {
                val daily = GuildScreenDetector.findDailyTab(screen, template("guild_daily_tab"))
                val row = GuildScreenDetector.findRewardsRow(screen, template("guild_rewards_row"))
                if (daily != null && row != null) return observed(PageState.GUILD_INFO, "daily" to daily)
            }
            if (GuildScreenDetector.isDailyTabSelected(screen)) {
                val info = GuildScreenDetector.findInfoTab(screen, template("guild_info_tab"))
                val donation = GuildScreenDetector.findDonateNow(screen, template("guild_donate_now"))
                    ?: GuildScreenDetector.findDonateZero(screen, template("guild_donate_zero"), template("guild_donate_one"))
                if (info != null && donation != null) return observed(PageState.GUILD_DAILY)
                if (info != null && (GuildScreenDetector.findWeeklyClaimed(screen, template("guild_weekly_claimed")) != null ||
                        GuildScreenDetector.findWeeklyOpen(screen, template("guild_weekly_open")) != null)) {
                    return observed(PageState.GUILD_WELFARE, "daily" to GuildScreenDetector.findDonationTab(screen, template("guild_donation_tab")))
                }
            }
            return null
        }
        // Inside a confirmed mail flow, only inspect its own controls and result layer.
        if (focus == NavigationGoal.MAIL) {
            if (MailScreenDetector.findRewardPopup(screen, template("mail_reward_popup")) != null)
                return observed(PageState.REWARD)
            val selected = MailScreenDetector.findMailSelected(screen, template("social_mail_selected"))
            val claim = MailScreenDetector.findClaimAll(screen, template("mail_claim_all"))
            return if (selected != null && claim != null) observed(PageState.MAIL, "claim" to claim)
            else observed(PageState.UNKNOWN)
        }
        // A fixed Social title plus its mail controls identifies this family
        // without spending the navigation budget on unrelated battle templates.
        if (MailScreenDetector.findSocialPageTitle(screen, template("social_page_title")) != null) {
            if (MailScreenDetector.findRewardPopup(screen, template("mail_reward_popup")) != null)
                return observed(PageState.REWARD)
            val selected = MailScreenDetector.findMailSelected(screen, template("social_mail_selected"))
            val claim = MailScreenDetector.findClaimAll(screen, template("mail_claim_all"))
            if (selected != null && claim != null) return observed(PageState.MAIL, "claim" to claim)
            val mail = MailScreenDetector.findMailTab(screen, template("social_mail_tab"))
            if (mail != null) return observed(PageState.SOCIAL, "mail" to mail)
        }
        if (GuildScreenDetector.findPageTitle(screen, template("guild_page_title")) != null &&
            !CheckInScreenDetector.isRewardPopup(screen) && !GameScreenDetector.hasConfirmationPanel(screen)) {
            selectedGuildPage()?.let { return it }
        }
        // A pale bottom strip is a candidate only. Confirm content/title and distinguish the hub.
        if (!CheckInScreenDetector.isRewardPopup(screen) &&
            !GameScreenDetector.hasConfirmationPanel(screen) &&
            WelfareNavigationDetector.hasBottomNavigation(screen)) {
            if (WelfareNavigationDetector.hasPageTitle(screen, template("welfare_page_title"))) {
                return observed(when {
                    SupplyScreenDetector.isSupplyPage(screen) -> PageState.SUPPLY
                    CheckInScreenDetector.isSignInPage(screen) -> PageState.SIGN_IN
                    else -> PageState.WELFARE
                })
            }
            if (RequiredHubScreenDetector.hasMultipleTabs(screen, template)) return observed(PageState.HUB)
        }
        // Only the source task/page supplies negative HUD evidence.
        // Avoid scanning unrelated trial/mail templates before each of the three HUD frames.
        val detection = GameScreenDetector.inspectHud(screen, hud, exclusions = hudExclusions)
        // The expanded menu leaves the HUD anchor visible underneath. Its destination
        // controls must win over that anchor, while rejected dungeon frames stay below.
        if (detection.accepted) return expandedMenu() ?: observed(PageState.HUD, "menu" to detection.menu)
        // Modal content precedes the scene that can remain visible behind it.
        val confirm = BygoneScreenDetector.findExitConfirm(screen, template("bygone_exit_confirm"))
        if (confirm != null && BygoneScreenDetector.findExitDialog(screen, template("bygone_exit_dialog")) != null)
            return observed(PageState.BYGONE_CONFIRM, "confirm" to confirm)
        if (BygoneScreenDetector.findWarpStart(screen, template("bygone_warp_start")) != null)
            return observed(PageState.BYGONE_WARP)
        if (BygoneScreenDetector.findSceneTimer(screen, template("bygone_scene_timer")) != null)
            return observed(PageState.BYGONE_SCENE)
        // A completed battle owns its result screen; don't swallow it as a generic reward.
        val trialResult = TrialsScreenDetector.findResultSuccess(screen, template("trials_result_success"))
        if (trialResult != null && trialResult.score >= 0.70f) return observed(PageState.TRIALS_RESULT)
        val popup = MailScreenDetector.findRewardPopup(screen, template("mail_reward_popup"))
        if (popup != null && CheckInScreenDetector.isRewardPopup(screen))
            return observed(PageState.REWARD)
        if (!CheckInScreenDetector.isRewardPopup(screen) && !GameScreenDetector.hasConfirmationPanel(screen)) {
            selectedGuildPage()?.let { return it }
        }
        val selected = MailScreenDetector.findMailSelected(screen, template("social_mail_selected"))
        val claim = MailScreenDetector.findClaimAll(screen, template("mail_claim_all"))
        if (selected != null && claim != null) return observed(PageState.MAIL, "claim" to claim)
        val mail = MailScreenDetector.findMailTab(screen, template("social_mail_tab"))
        if (mail != null) return observed(PageState.SOCIAL, "mail" to mail)
        val trial = TrialsScreenDetector.findDialogLogo(screen, template("trials_dialog_logo"))
        if (trial != null) {
            val trialControls = arrayOf(
                "close" to TrialsScreenDetector.findClose(screen, template("trials_close")),
                "participate" to TrialsScreenDetector.findParticipate(screen, template("trials_participate")),
                "vitality" to TrialsScreenDetector.findVitalityInsufficient(screen, template("trials_vitality_insufficient")),
                "WEAPON" to TrialsScreenDetector.findSelectedType(screen, com.aliothmoon.maahotta.data.TrialType.WEAPON),
                "MATRIX" to TrialsScreenDetector.findSelectedType(screen, com.aliothmoon.maahotta.data.TrialType.MATRIX),
                "GOLD" to TrialsScreenDetector.findSelectedType(screen, com.aliothmoon.maahotta.data.TrialType.GOLD),
            )
            val proxy = TrialsScreenDetector.findProxyBattle(screen, template("trials_proxy_battle"))
            if (proxy != null) return observed(PageState.TRIALS_PROXY, "proxy" to proxy, *trialControls)
            return observed(PageState.TRIALS, *trialControls)
        }
        val taste = KitchenScreenDetector.findTaste(screen, template("btn_eat"))?.takeIf { it.score >= 0.70f }
        if (taste != null) return observed(PageState.KITCHEN, "taste" to taste)
        if (IslandMerchantScreenDetector.findIslandPage(screen, template("island_page_title")) != null)
            return observed(PageState.ISLAND)
        val daily = GuildScreenDetector.findDailyTab(screen, template("guild_daily_tab"))
        val info = GuildScreenDetector.findInfoTab(screen, template("guild_info_tab"))
        if (daily != null && info != null) return observed(PageState.GUILD, "daily" to daily)
        if (RequiredHubScreenDetector.hasMultipleTabs(screen, template)) return observed(PageState.HUB)
        val dive = BygoneScreenDetector.findDiveNext(screen, template("bygone_dive_next"))?.takeIf { it.score >= 0.70f }
        if (dive != null) return observed(PageState.BYGONE_FLOOR, "dive" to dive)
        expandedMenu()?.let { return it }
        val userCenter = AccountTransitionScreenDetector.findUserCenter(screen, template("settings_user_center"))
        if (userCenter != null) return observed(PageState.SETTINGS, "userCenter" to userCenter)
        return observed(PageState.UNKNOWN)
    }
}
