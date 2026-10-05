import android.graphics.Bitmap
import com.aliothmoon.maahotta.vision.AccountScreenDetector
import com.aliothmoon.maahotta.vision.AnnouncementDetector
import com.aliothmoon.maahotta.vision.GameScreenDetector
import com.aliothmoon.maahotta.vision.RewardRecoveryDetector
import com.aliothmoon.maahotta.vision.TitleScreenDetector
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Synthetic title evidence with independently recognized covering layouts; no title fixture yet. */
fun checkTitleScreenFastPath() {
    val background = Color(18, 30, 46)
    val cyan = Color(60, 150, 200)
    val blue = Color(70, 150, 220)

    fun Graphics2D.box(width: Int, height: Int, left: Float, top: Float, right: Float, bottom: Float, fill: Color) {
        color = fill
        val x = (width * left).toInt()
        val y = (height * top).toInt()
        fillRect(x, y, (width * right).toInt() - x, (height * bottom).toInt() - y)
    }

    fun title(width: Int = 1280, height: Int = 720, overlay: Graphics2D.(Int, Int) -> Unit = { _, _ -> }): Bitmap {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = background
        graphics.fillRect(0, 0, width, height)
        graphics.box(width, height, 0.45f, 0.87f, 0.55f, 0.91f, Color.WHITE)
        graphics.box(width, height, 0.38f, 0.86f, 0.45f, 0.92f, cyan)
        graphics.box(width, height, 0.55f, 0.86f, 0.62f, 0.92f, cyan)
        graphics.overlay(width, height)
        graphics.dispose()
        return Bitmap(image)
    }

    fun Graphics2D.accountAnchor(width: Int, height: Int, dx: Float, yRatio: Float, fill: Color = Color.WHITE) {
        color = fill
        val radius = (height * 0.007f).toInt().coerceAtLeast(4)
        val x = (width / 2f + dx * height).toInt()
        val y = (height * yRatio).toInt()
        fillRect(x - radius, y - radius, radius * 2 + 1, radius * 2 + 1)
    }

    fun rejectsVisibleTitle(label: String, screen: Bitmap, overlayRecognized: Boolean) {
        check(overlayRecognized) { "$label overlay was not recognized by its production detector" }
        check(TitleScreenDetector.findEntry(screen) != null) { "$label must leave positive title evidence behind it" }
        check(TitleScreenDetector.findUnobstructedEntry(screen) == null) { "$label leaked into the early title route" }
    }

    for ((width, height) in listOf(960 to 540, 1280 to 720, 1920 to 1080)) {
        val frame = title(width, height)
        val original = TitleScreenDetector.findEntry(frame)
        val unobstructed = TitleScreenDetector.findUnobstructedEntry(frame)
        check(original != null && unobstructed != null)
        check(original.point.x == unobstructed.point.x && original.point.y == unobstructed.point.y)
        check(original.score == unobstructed.score)
    }

    val announcement = title { width, height ->
        color = Color(200, 200, 200)
        val left = (height * 0.54f).toInt()
        val top = (height * 0.18f).toInt()
        fillRect(left, top, width - (height * 0.18f).toInt() - left, (height * 0.72f).toInt() - top)
    }
    rejectsVisibleTitle("announcement", announcement, AnnouncementDetector.hasLayout(announcement))

    val confirmation = title { width, height ->
        box(width, height, 0.30f, 0.30f, 0.70f, 0.62f, Color(200, 200, 200))
        box(width, height, 0.53f, 0.50f, 0.80f, 0.65f, blue)
    }
    rejectsVisibleTitle("confirmation", confirmation, GameScreenDetector.hasConfirmationPanel(confirmation))

    val userCenter = title { width, height ->
        for ((dx, y) in listOf(-0.30f to 0.06f, 0f to 0.06f, 0.30f to 0.06f,
            -0.30f to 0.79f, 0.30f to 0.79f)) accountAnchor(width, height, dx, y)
    }
    rejectsVisibleTitle("user center", userCenter, AccountScreenDetector.isUserCenter(userCenter))

    val accountList = title { width, height ->
        for ((dx, y) in listOf(0f to 0.30f, 0f to 0.45f, 0f to 0.67f,
            -0.20f to 0.67f, 0.20f to 0.67f)) accountAnchor(width, height, dx, y)
    }
    rejectsVisibleTitle("account list", accountList, AccountScreenDetector.isAccountList(accountList))

    val quickLogin = title { width, height ->
        for ((dx, y) in listOf(-0.18f to 0.34f, 0.18f to 0.34f, 0f to 0.68f)) accountAnchor(width, height, dx, y)
        accountAnchor(width, height, -0.10f, 0.56f, blue)
    }
    rejectsVisibleTitle("quick login", quickLogin, AccountScreenDetector.isQuickLogin(quickLogin))

    val password = title { width, height ->
        for (side in listOf(-1, 1)) for (y in listOf(0.30f, 0.74f))
            accountAnchor(width, height, side * 0.40f, y)
        color = blue
        val halfWidth = (height * 0.30f).toInt()
        fillRect(width / 2 - halfWidth, (height * 0.45f).toInt(), halfWidth * 2 + 1, (height * 0.10f).toInt())
    }
    rejectsVisibleTitle("password submit", password, AccountScreenDetector.findPasswordSubmit(password) != null)

    val recovery = title { width, height ->
        box(width, height, 0.059f, 0.583f, 0.305f, 0.681f, Color(230, 40, 40))
        for ((left, right) in listOf(0.391f to 0.570f, 0.590f to 0.773f, 0.793f to 0.973f)) {
            box(width, height, left, 0.308f, right, 0.385f, Color.WHITE)
            box(width, height, left, 0.722f, right, 0.785f, Color(40, 90, 220))
        }
    }
    check(TitleScreenDetector.findEntry(recovery) != null)
    check(TitleScreenDetector.findUnobstructedEntry(recovery) != null) {
        "reward-recovery color blocks must not divert the title route"
    }
    val rewardTitle = Bitmap(ImageIO.read(File("app/src/main/assets/templates/reward_recovery_title.png")))
    check(RewardRecoveryDetector.match(recovery, rewardTitle) == null) {
        "solid color layout matched the reward-recovery title crop"
    }
    fun placeTitle(x: Int, y: Int): Bitmap {
        val image = BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(18, 30, 46)
        graphics.fillRect(0, 0, 1280, 720)
        graphics.drawImage(rewardTitle.image, x, y, null)
        graphics.dispose()
        return Bitmap(image)
    }
    val placed = RewardRecoveryDetector.match(
        placeTitle(RewardRecoveryDetector.CROP_LEFT, RewardRecoveryDetector.CROP_TOP),
        rewardTitle,
    )
    check(placed != null && placed.score >= RewardRecoveryDetector.THRESHOLD) {
        "fixed-position reward-recovery title was not recognized"
    }
    check(RewardRecoveryDetector.match(placeTitle(RewardRecoveryDetector.CROP_LEFT, 40), rewardTitle) == null) {
        "reward-recovery title outside the fixed crop was recognized"
    }
    for (name in listOf("main", "main-wide", "emulator-main", "user-main-window", "password-submit", "confirmation")) {
        val actual = Bitmap(ImageIO.read(File("tests/hud/fixtures/$name.png")))
        check(RewardRecoveryDetector.match(actual, rewardTitle) == null) { "$name fixture falsely matched reward recovery" }
    }

    val missingRail = title { width, height -> box(width, height, 0.38f, 0.86f, 0.45f, 0.92f, background) }
    check(TitleScreenDetector.findUnobstructedEntry(missingRail) == null)
    val blank = Bitmap(BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB))
    check(TitleScreenDetector.findUnobstructedEntry(blank) == null)
    for (name in listOf("password-submit", "confirmation", "login-failure", "main")) {
        val actual = Bitmap(ImageIO.read(File("tests/hud/fixtures/$name.png")))
        check(TitleScreenDetector.findUnobstructedEntry(actual) == null) { "$name fixture falsely became a title screen" }
    }
    println("PASS unobstructed title preserves entry; announcement/account/confirmation layouts block early routing; reward recovery matches only its fixed title crop")
}
