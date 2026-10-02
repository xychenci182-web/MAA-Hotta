import android.graphics.Bitmap
import com.aliothmoon.maahotta.vision.*
import java.io.File
import javax.imageio.ImageIO

fun main() {
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
