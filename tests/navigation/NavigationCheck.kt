import android.graphics.Bitmap
import com.aliothmoon.maahotta.vision.*
import java.io.File
import javax.imageio.ImageIO

fun main() {
    checkTitleScreenFastPath()
    val cache = mutableMapOf<String, Bitmap>()
    fun template(name: String): Bitmap? = cache.getOrPut(name) {
        Bitmap(ImageIO.read(File("app/src/main/assets/templates/$name.png")))
    }
    val hud = HudTemplates(template("hud_menu_body")!!,
        exitDialog=template("bygone_exit_dialog"), exitConfirm=template("bygone_exit_confirm"),
        dungeonExit=template("bygone_exit_icon"), dungeonTimer=template("bygone_scene_timer"),
        dungeonWarp=template("bygone_warp_start"))
    for ((name, expected) in listOf("main" to PageState.HUD, "main-wide" to PageState.HUD,
        "user-main-window" to PageState.HUD, "supply" to PageState.SUPPLY,
        "bygone-fixed-exit" to PageState.BYGONE_SCENE, "bygone-scene" to PageState.BYGONE_SCENE,
        "bygone-warp" to PageState.BYGONE_WARP)) {
        val frame = Bitmap(ImageIO.read(File("tests/hud/fixtures/$name.png")))
        val result = PageStateDetector.inspect(frame, ::template, hud)
        println("STATE $name: ${result.state}")
        check(result.state == expected) { "$name expected $expected but ${result.state}" }
    }
    // Regression: the first welfare destination frame must complete navigation even
    // when opening the gift consumed the previous confirmation/time budget.
    val supplyFrame = Bitmap(ImageIO.read(File("tests/hud/fixtures/supply.png")))
    var templateLookups = 0
    val welfarePage = PageStateDetector.inspect(supplyFrame, { templateLookups++; template(it) }, hud)
    check(welfarePage.state == PageState.SUPPLY)
    check(templateLookups == 3) { "Welfare fast path should inspect Social, Guild and Welfare titles only; looked up $templateLookups templates" }
    val welfareAction = NavigationPolicy.next(welfarePage.state, NavigationGoal.WELFARE)
    check(welfareAction == NavigationAction.READY)
    check(NavigationPolicy.requiredFrames(welfarePage.state, welfareAction) == 1)
    check(NavigationPolicy.requiredFrames(PageState.WELFARE, NavigationAction.READY) == 1)
    check(NavigationPolicy.requiredFrames(PageState.HUD, NavigationAction.READY) == 2)
    check(NavigationPolicy.requiredFrames(PageState.MENU, NavigationAction.OPEN_GUILD) == 2)
    check(NavigationPolicy.requiredFrames(PageState.BYGONE_SCENE, NavigationAction.EXIT_BYGONE) == 3)
    println("PASS first welfare destination frame completes; no unrelated template scans")
    // Regression: a shared pale bottom strip cannot override the hub's left tab evidence.
    val hubImage = java.awt.image.BufferedImage(1280,720,java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val hubGraphics = hubImage.createGraphics()
    hubGraphics.color = java.awt.Color(18,30,46)
    hubGraphics.fillRect(0,0,1280,720)
    hubGraphics.color = java.awt.Color(220,220,220)
    hubGraphics.fillRect(0,648,1280,72)
    for ((name,y) in listOf("hub_weekly_tab" to 0.18f,"hub_recommend_tab" to 0.33f)) {
        val original = template(name)!!
        val scaled = Bitmap.createScaledBitmap(original,original.width*720/596,original.height*720/596,true)
        hubGraphics.drawImage(scaled.image,(1280*0.075f).toInt()-scaled.width/2,
            (720*y).toInt()-scaled.height/2,null)
    }
    hubGraphics.dispose()
    val hubFrame = Bitmap(hubImage)
    check(WelfareNavigationDetector.hasBottomNavigation(hubFrame))
    val hubState = PageStateDetector.inspect(hubFrame,::template,hud).state
    println("PALE HUB classified: $hubState; tab evidence=${RequiredHubScreenDetector.hasMultipleTabs(hubFrame,::template)}")
    check(hubState == PageState.HUB)
    check(!WelfareNavigationDetector.hasPageTitle(hubFrame,template("welfare_page_title")))
    check(WelfareNavigationDetector.hasPageTitle(supplyFrame,template("welfare_page_title")))
    val genericImage = java.awt.image.BufferedImage(1280,720,java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val genericGraphics = genericImage.createGraphics()
    genericGraphics.color = java.awt.Color(18,30,46)
    genericGraphics.fillRect(0,0,1280,720)
    genericGraphics.color = java.awt.Color(220,220,220)
    genericGraphics.fillRect(0,648,1280,72)
    genericGraphics.dispose()
    check(PageStateDetector.inspect(Bitmap(genericImage),::template,hud).state == PageState.UNKNOWN)
    println("PASS pale-bottom hub is HUB; unlabelled pale-bottom screen stays UNKNOWN")
    val menuImage = java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val menuGraphics = menuImage.createGraphics()
    menuGraphics.color = java.awt.Color(18, 30, 46)
    menuGraphics.fillRect(0, 0, 1280, 720)
    for ((name, position) in listOf("menu_social_entry" to (0.675f to 0.38f),
        "guild_menu_entry" to (0.72f to 0.25f), "menu_settings_entry" to (0.845f to 0.60f))) {
        val original = template(name)!!
        val scaled = Bitmap.createScaledBitmap(original, original.width*720/596, original.height*720/596, true)
        menuGraphics.drawImage(scaled.image, (1280*position.first).toInt()-scaled.width/2,
            (720*position.second).toInt()-scaled.height/2, null)
    }
    menuGraphics.dispose()
    val menuObservation = PageStateDetector.inspect(Bitmap(menuImage), ::template, hud)
    check(menuObservation.state == PageState.MENU) { "Expanded menu was ${menuObservation.state}" }
    check(menuObservation.controls["guild"] != null && menuObservation.controls["social"] != null)
    println("PASS expanded menu recognizes social and guild on same frame")
    // A real expanded menu leaves the HUD menu anchor visible behind it.
    val expandedMenu = Bitmap(ImageIO.read(File("tests/navigation/fixtures/expanded-menu.png")))
    check(GameScreenDetector.inspectHud(expandedMenu, hud).menuScore >= 0.78f)
    val expandedObservation = PageStateDetector.inspect(expandedMenu, ::template, hud)
    check(expandedObservation.state == PageState.MENU) { "Real expanded menu was ${expandedObservation.state}" }
    check(expandedObservation.controls["social"] != null && expandedObservation.controls["guild"] != null)
    check(NavigationPolicy.next(expandedObservation.state, NavigationGoal.MAIL) == NavigationAction.OPEN_SOCIAL)
    println("PASS real expanded menu with visible HUD anchor routes directly to social")
    val mailClaimed = Bitmap(ImageIO.read(File("tests/navigation/fixtures/mail-claimed.png")))
    val mailLookups = mutableListOf<String>()
    val mailPage = PageStateDetector.inspect(mailClaimed, { mailLookups += it; template(it) }, hud)
    check(mailPage.state == PageState.MAIL && mailPage.controls["claim"] != null)
    val mailExitPage = PageStateDetector.inspect(mailClaimed, ::template, hud, NavigationGoal.HUD)
    check(mailExitPage.state == PageState.MAIL && mailExitPage.controls["menu"] == null)
    check(NavigationPolicy.next(mailExitPage.state, NavigationGoal.HUD) == NavigationAction.BACK)
    val returnedMain = Bitmap(ImageIO.read(File("tests/hud/fixtures/main.png")))
    val returnedHud = PageStateDetector.inspect(returnedMain, ::template, hud, NavigationGoal.HUD)
    check(returnedHud.state == PageState.HUD && returnedHud.controls["menu"] != null)
    check(NavigationPolicy.next(returnedHud.state, NavigationGoal.HUD) == NavigationAction.READY)
    returnedMain.recycle()
    println("PASS mail exit requires main HUD menu; mailbox cannot finish as HUD")
    check(mailLookups.none { it.startsWith("trials_") || it.startsWith("bygone_") || it.startsWith("guild_") })
    println("PASS real claimed mailbox avoids unrelated template scans")
    val friends = Bitmap(ImageIO.read(File("tests/navigation/fixtures/social-friends.png")))
    val socialLookups = mutableListOf<String>()
    val friendsPage = PageStateDetector.inspect(friends, { socialLookups += it; template(it) }, hud)
    check(friendsPage.state == PageState.SOCIAL && friendsPage.controls["mail"] != null)
    check(socialLookups.none { it.startsWith("trials_") || it.startsWith("bygone_") || it.startsWith("guild_") })
    println("PASS real friends page routes to Mail without unrelated scans")
    val guildInfo = Bitmap(ImageIO.read(File("tests/navigation/fixtures/guild-info.png")))
    val guildLookups = mutableListOf<String>()
    val guildPage = PageStateDetector.inspect(guildInfo, { guildLookups += it; template(it) }, hud)
    check(guildLookups.none { it.startsWith("trials_") || it.startsWith("bygone_") })
    println("REAL GUILD INFO: ${guildPage.state}")
    check(guildPage.state == PageState.GUILD_INFO) { "Guild info was ${guildPage.state}" }
    check(GuildScreenDetector.findDonateZero(guildInfo, template("guild_donate_zero"), template("guild_donate_one")) == null)
    check(GuildScreenDetector.findDonateNow(guildInfo, template("guild_donate_now")) == null)
    val guildDaily = Bitmap(ImageIO.read(File("tests/navigation/fixtures/guild-daily.png")))
    check(PageStateDetector.inspect(guildDaily, ::template, hud).state == PageState.GUILD_DAILY)
    check(GuildScreenDetector.findDonateNow(guildDaily, template("guild_donate_now")) != null)
    check(GuildScreenDetector.findDonateZero(guildDaily, template("guild_donate_zero"), template("guild_donate_one")) == null)
    val guildDonated = Bitmap(ImageIO.read(File("tests/navigation/fixtures/guild-donated.png")))
    check(PageStateDetector.inspect(guildDonated, ::template, hud).state == PageState.GUILD_DAILY)
    check(GuildScreenDetector.findDonateZero(guildDonated, template("guild_donate_zero"), template("guild_donate_one")) != null)
    val guildWeekly = Bitmap(ImageIO.read(File("tests/navigation/fixtures/guild-weekly.png")))
    val weeklyPage = PageStateDetector.inspect(guildWeekly, ::template, hud)
    check(weeklyPage.state == PageState.GUILD_WELFARE) { "Guild welfare was ${weeklyPage.state}" }
    check(GuildScreenDetector.findWeeklyClaimed(guildWeekly, template("guild_weekly_claimed")) != null)
    check(GuildScreenDetector.findWeeklyClaimed(guildDaily, template("guild_weekly_claimed")) == null)
    check(GuildScreenDetector.findWeeklyClaimed(guildInfo, template("guild_weekly_claimed")) == null)
    check(weeklyPage.controls["daily"] != null)
    check(NavigationPolicy.next(weeklyPage.state, NavigationGoal.GUILD) == NavigationAction.READY)
    check(NavigationPolicy.next(weeklyPage.state, NavigationGoal.GUILD_DAILY) == NavigationAction.SELECT_GUILD_DAILY)
    println("PASS guild info cannot prove donation completed")
    for (name in listOf("password-submit", "login-failure")) {
        val frame = Bitmap(ImageIO.read(File("tests/hud/fixtures/$name.png")))
        val state = PageStateDetector.inspect(frame, ::template, hud).state
        println("NEGATIVE $name: $state")
        check(state == PageState.UNKNOWN) { "Login/loading falsely classified as $state" }
    }
    // A dungeon with a perfectly matching HUD menu must still remain a dungeon.
    val dungeon = Bitmap(ImageIO.read(File("tests/hud/fixtures/bygone-fixed-exit.png")))
    val scaledMenu = Bitmap.createScaledBitmap(hud.menu,
        hud.menu.width*dungeon.height/528, hud.menu.height*dungeon.height/528, true)
    val graphics = dungeon.image.createGraphics()
    graphics.drawImage(scaledMenu.image, (dungeon.width*0.92f).toInt()-scaledMenu.width/2,
        (dungeon.height*0.048f).toInt()-scaledMenu.height/2, null)
    graphics.dispose()
    val inspection = GameScreenDetector.inspectHud(dungeon, hud)
    check(inspection.menuScore >= 0.78f) { "Synthetic dungeon menu was not matched: ${inspection.menuScore}" }
    check(!inspection.accepted && inspection.reason.contains("计时器"))
    check(PageStateDetector.inspect(dungeon, ::template, hud).state == PageState.BYGONE_SCENE)
    check(GameScreenDetector.classify(dungeon, hud) == GameScreen.OTHER)
    // Exit confirmation wins even when the scene clock remains visible behind the modal.
    val dialogGraphics = dungeon.image.createGraphics()
    for ((name, position) in listOf("bygone_exit_dialog" to (0.50f to 0.43f),
        "bygone_exit_confirm" to (0.65f to 0.55f))) {
        val original = template(name)!!
        val scaled = Bitmap.createScaledBitmap(original, original.width*dungeon.height/596,
            original.height*dungeon.height/596, true)
        dialogGraphics.drawImage(scaled.image, (dungeon.width*position.first).toInt()-scaled.width/2,
            (dungeon.height*position.second).toInt()-scaled.height/2, null)
    }
    dialogGraphics.dispose()
    check(PageStateDetector.inspect(dungeon, ::template, hud).state == PageState.BYGONE_CONFIRM)
    println("PASS exit modal precedes scene clock")
    println("PASS dungeon with strong HUD menu is excluded")
    for (goal in NavigationGoal.entries) {
        check(NavigationPolicy.next(PageState.UNKNOWN, goal) == NavigationAction.WAIT)
    }
    check(NavigationPolicy.next(PageState.TRIALS, NavigationGoal.TRIALS) == NavigationAction.READY)
    check(NavigationPolicy.next(PageState.KITCHEN, NavigationGoal.KITCHEN) == NavigationAction.READY)
    check(NavigationPolicy.next(PageState.ISLAND, NavigationGoal.ISLAND) == NavigationAction.READY)
    check(NavigationPolicy.next(PageState.TRIALS_PROXY, NavigationGoal.HUB) == NavigationAction.WAIT)
    check(NavigationPolicy.next(PageState.MAIL, NavigationGoal.GUILD_DAILY) == NavigationAction.BACK)
    check(NavigationPolicy.next(PageState.MENU, NavigationGoal.GUILD_DAILY) == NavigationAction.OPEN_GUILD)
    check(NavigationPolicy.next(PageState.GUILD, NavigationGoal.GUILD_DAILY) == NavigationAction.SELECT_GUILD_DAILY)
    check(NavigationPolicy.next(PageState.GUILD_DAILY, NavigationGoal.GUILD) == NavigationAction.READY)
    check(NavigationPolicy.next(PageState.SIGN_IN, NavigationGoal.WELFARE) == NavigationAction.READY)
    check(NavigationPolicy.next(PageState.HUB, NavigationGoal.HUB) == NavigationAction.READY)
    check(NavigationPolicy.next(PageState.UNKNOWN, NavigationGoal.GUILD_DAILY) == NavigationAction.WAIT)
    for (goal in NavigationGoal.entries.filter { it != NavigationGoal.BYGONE }) {
        check(NavigationPolicy.next(PageState.BYGONE_SCENE, goal) == NavigationAction.EXIT_BYGONE)
        check(NavigationPolicy.next(PageState.BYGONE_CONFIRM, goal) == NavigationAction.CONFIRM_BYGONE)
        check(NavigationPolicy.next(PageState.BYGONE_WARP, goal) == NavigationAction.WAIT)
    }
    println("PASS mail->menu->guild transitions, page reuse, unknown wait, dungeon priority")
}
