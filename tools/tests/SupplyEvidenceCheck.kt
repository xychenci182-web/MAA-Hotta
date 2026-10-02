import android.graphics.Bitmap
import com.aliothmoon.maahotta.vision.SupplyScreenDetector
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Offline regression using the existing Java2D Android graphics adapter. */
fun main() {
    val completed = supplyFixture()
    check(SupplyScreenDetector.isAllDayRewardsClaimed(completed)) {
        "The real completed fixture must provide positive evidence for all seven DAY rewards"
    }
    check(SupplyScreenDetector.isAllClaimed(completed)) {
        "The real completed fixture must include cumulative reward evidence"
    }

    for (height in listOf(360, 720, 1080)) {
        val width = (completed.width.toDouble() * height / completed.height).roundToInt()
        val scaled = Bitmap.createScaledBitmap(completed, width, height, true)
        check(SupplyScreenDetector.isAllDayRewardsClaimed(scaled)) {
            "DAY completion evidence was lost at screenshot height $height"
        }
        check(SupplyScreenDetector.isAllClaimed(scaled)) {
            "Cumulative completion evidence was lost at screenshot height $height"
        }
    }

    // Derive a counterexample in memory: keep only the historical DAY 1
    // completion and remove the completion samples for DAY 2 through DAY 7.
    // The cumulative emblem stays untouched, so it cannot prove DAY completion.
    val partial = supplyFixture()
    val scale = partial.height / 525f
    val painter = partial.image.createGraphics()
    try {
        painter.color = java.awt.Color.WHITE
        for (day in 1 until 7) {
            val center = (partial.width - (212f + (6 - day) * 80.5f) * scale).roundToInt()
            painter.fillRect(
                (center - 21 * scale).roundToInt(),
                (267 * scale).roundToInt(),
                (42 * scale).roundToInt(),
                (23 * scale).roundToInt(),
            )
        }
    } finally {
        painter.dispose()
    }
    check(SupplyScreenDetector.isSupplyPage(partial)) {
        "The counterexample must retain supply page identity"
    }
    check(SupplyScreenDetector.lastClaimedDay(partial) == 1) {
        "The counterexample must retain only historical DAY 1 completion"
    }
    check(SupplyScreenDetector.findClaimable(partial) == null) {
        "The counterexample intentionally has no yellow claimable reward"
    }
    check(SupplyScreenDetector.isCumulativeClaimed(partial)) {
        "The counterexample must retain the independent cumulative overlay"
    }
    check(!SupplyScreenDetector.isAllDayRewardsClaimed(partial)) {
        "Historical DAY 1 completion cannot establish today's result"
    }
    check(!SupplyScreenDetector.isAllClaimed(partial)) {
        "The cumulative overlay alone cannot establish all DAY completion"
    }

    val hud = Bitmap(ImageIO.read(File("tests/hud/fixtures/main.png")))
    check(!SupplyScreenDetector.isAllDayRewardsClaimed(hud)) {
        "The HUD must not count as a completed supply page"
    }
    check(!SupplyScreenDetector.isAllClaimed(hud)) {
        "The HUD must not count as completed cumulative rewards"
    }
    println("Supply positive-evidence regression checks passed")
}

private fun supplyFixture(): Bitmap =
    Bitmap(ImageIO.read(File("tests/hud/fixtures/supply.png")))
