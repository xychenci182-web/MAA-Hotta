import android.graphics.Bitmap
import com.aliothmoon.maahotta.vision.HudTemplates
import com.aliothmoon.maahotta.vision.GameScreenDetector
import com.aliothmoon.maahotta.vision.GameScreen
import javax.imageio.ImageIO
import java.io.File
import kotlin.math.abs
fun main() {
 fun load(p:String)=Bitmap(ImageIO.read(File(p)))
 val template=HudTemplates(load("app/src/main/assets/templates/hud_menu_body.png"),load("app/src/main/assets/templates/hud_minimap_controls.png"),load("app/src/main/assets/templates/bygone_exit_dialog.png"),load("app/src/main/assets/templates/bygone_exit_confirm.png"),load("app/src/main/assets/templates/hud_dodge.png"),load("app/src/main/assets/templates/bygone_exit_icon.png"))
 val password = load("tests/hud/fixtures/password-submit.png")
 for (height in listOf(360, 720, 1080)) {
 val frame = Bitmap.createScaledBitmap(password, password.width * height / password.height, height, true)
 val hit = checkNotNull(com.aliothmoon.maahotta.vision.AccountScreenDetector.findPasswordSubmit(frame))
 check(abs(hit.point.y.toDouble() / height - 0.54) < 0.035)
 println("PASS password submit height=$height point=${hit.point}")
 }
 for (name in listOf("main", "supply", "confirmation", "login-failure")) {
 check(com.aliothmoon.maahotta.vision.AccountScreenDetector.findPasswordSubmit(load("tests/hud/fixtures/$name.png")) == null)
 }
 val lineTitle = load("app/src/main/assets/templates/line_switch_title.png")
 val lineCancel = load("app/src/main/assets/templates/line_switch_cancel.png")
 fun lineButton(frame: Bitmap): com.aliothmoon.maahotta.vision.MatchResult? {
 if (com.aliothmoon.maahotta.vision.TemplateMatcher.match(frame,lineTitle,threshold=0.80f,region=com.aliothmoon.maahotta.vision.SearchRegion(0.12f,0.16f,0.50f,0.38f),referenceHeight=561)==null) return null
 return com.aliothmoon.maahotta.vision.TemplateMatcher.match(frame,lineCancel,threshold=0.78f,region=com.aliothmoon.maahotta.vision.SearchRegion(0.20f,0.54f,0.53f,0.79f),referenceHeight=561)
 }
 val lineFrame=load("tests/hud/fixtures/line-switch.png")
 for (height in listOf(360,720,1080)) {
 val frame=Bitmap.createScaledBitmap(lineFrame,lineFrame.width*height/lineFrame.height,height,true)
 val hit=checkNotNull(lineButton(frame))
 check(abs(hit.point.x.toDouble()/frame.width-0.393)<0.02)
 println("PASS line switch cancel height=$height")
 }
 for(name in listOf("main","supply","confirmation","password-submit")) check(lineButton(load("tests/hud/fixtures/$name.png"))==null) { "False line dialog: $name" }
 val warp=load("tests/hud/fixtures/bygone-warp.png")
 val warpTemplate=load("app/src/main/assets/templates/bygone_warp_start.png")
 for(height in listOf(360,720,1080)) {
 val scaled=Bitmap.createScaledBitmap(warp,warp.width*height/warp.height,height,true)
 check(com.aliothmoon.maahotta.vision.BygoneScreenDetector.findWarpStart(scaled,warpTemplate)!=null)
 println("PASS warp marker height=$height")
 }
 for(name in listOf("main","supply","confirmation","password-submit","bygone-scene","login-failure")) {
 check(com.aliothmoon.maahotta.vision.BygoneScreenDetector.findWarpStart(load("tests/hud/fixtures/$name.png"),warpTemplate)==null) { "False warp marker $name" }
 }
 val skipHit=com.aliothmoon.maahotta.vision.MatchResult(android.graphics.Point(900,20),0.9f)
 val exitHit=com.aliothmoon.maahotta.vision.MatchResult(android.graphics.Point(150,40),0.9f)
 check(com.aliothmoon.maahotta.vision.BygoneScreenDetector.exclusiveEntryAction(skipHit,exitHit)==null)
 check(com.aliothmoon.maahotta.vision.BygoneScreenDetector.exclusiveEntryAction(null,null)==null)
 check(com.aliothmoon.maahotta.vision.BygoneScreenDetector.exclusiveEntryAction(skipHit,null)==skipHit)
 check(com.aliothmoon.maahotta.vision.BygoneScreenDetector.exclusiveEntryAction(null,exitHit)==exitHit)
 val sceneTimer=load("app/src/main/assets/templates/bygone_scene_timer.png")
 for(name in listOf("bygone-fixed-exit", "bygone-scene")) {
 val frame=load("tests/hud/fixtures/$name.png")
 for(height in listOf(360,720,1080)) {
 val scaled=Bitmap.createScaledBitmap(frame,frame.width*height/frame.height,height,true)
 check(com.aliothmoon.maahotta.vision.BygoneScreenDetector.findSceneTimer(scaled,sceneTimer)!=null) { "No scene timer: $name at $height" }
 }
 println("PASS scene timer $name")
 }
 for(name in listOf("main", "main-wide", "supply", "confirmation", "password-submit", "bygone-warp", "login-failure")) {
 check(com.aliothmoon.maahotta.vision.BygoneScreenDetector.findSceneTimer(load("tests/hud/fixtures/$name.png"),sceneTimer)==null) { "False scene timer: $name" }
 }
 val original=load("tests/hud/fixtures/main.png")
 for(h in listOf(360,720,1080)) {
 val frame=Bitmap.createScaledBitmap(original,h*16/9,h,true)
 val menu=checkNotNull(GameScreenDetector.findHudMenu(frame,template))
 val cross=checkNotNull(GameScreenDetector.findCrossedHudIcon(frame,template))
 val gift=checkNotNull(GameScreenDetector.findGiftHudIcon(frame,template))
 // Independent observed icon centers in the original 1280x720 fixture.
 check(abs(menu.point.x-1210*h/720.0)<6*h/720.0)
 check(abs(cross.point.x-1096*h/720.0)<6*h/720.0)
 check(abs(gift.point.x-982*h/720.0)<6*h/720.0)
 check(GameScreenDetector.classify(frame,template)==GameScreen.HUD)
 println("PASS height=$h menu=${menu.point} cross=${cross.point} gift=${gift.point}")
 }
 for(path in listOf("tests/hud/fixtures/confirmation.png","tests/hud/fixtures/supply.png")) {
 val frame=load(path)
 check(GameScreenDetector.findHudMenu(frame,template)==null) { "False HUD: $path" }
 check(GameScreenDetector.findGiftHudIcon(frame,template)==null)
 check(GameScreenDetector.findCrossedHudIcon(frame,template)==null)
 check(GameScreenDetector.classify(frame,template)!=GameScreen.HUD)
 println("PASS negative $path")
 }
 val emulatorMain=load("tests/hud/fixtures/emulator-main.png")
 val userMain = GameScreenDetector.inspectHud(load("tests/hud/fixtures/user-main-window.png"), template)
 check(userMain.menu != null) { "User screenshot: ${userMain.summary()}" }
 println("PASS user screenshot: ${userMain.summary()}")
 val liveDetection=GameScreenDetector.inspectHud(emulatorMain,template)
 check(liveDetection.menu!=null) { liveDetection.summary() }
 println("PASS emulator main: ${liveDetection.summary()}")
 val loading=GameScreenDetector.inspectHud(load("tests/hud/fixtures/login-failure.png"),template)
 check(loading.menu==null)
 println("PASS loading timeout fixture: ${loading.summary()}")
 // Change scenery color under the HUD while retaining the light fixed UI strokes.
 for(tint in listOf(0x303810,0x102850)) {
 val img=java.awt.image.BufferedImage(original.width,original.height,java.awt.image.BufferedImage.TYPE_INT_ARGB)
 val g=img.createGraphics();g.drawImage(original.image,0,0,null);g.dispose()
 for(y in 0 until 180) for(x in 0 until original.width) {
 if(x in 190..849) continue
 val c=img.getRGB(x,y);val r=(c ushr 16) and 255;val green=(c ushr 8) and 255;val b=c and 255
 if(minOf(r,green,b)<110) {
 val nr=(r+((tint ushr 16) and 255)).coerceAtMost(255)
 val ng=(green+((tint ushr 8) and 255)).coerceAtMost(255)
 val nb=(b+(tint and 255)).coerceAtMost(255)
 img.setRGB(x,y,(255 shl 24) or (nr shl 16) or (ng shl 8) or nb)
 }
 }
 // The interior of the minimap is location-dependent and must not participate.
 val paint=img.createGraphics();paint.color=java.awt.Color.MAGENTA
 paint.fillRect(53,78,84,59);paint.dispose()
 val detection=GameScreenDetector.inspectHud(Bitmap(img),template)
 check(detection.menu!=null) { "Scenery tint $tint: ${detection.summary()}" }
 println("PASS scenery tint=$tint: ${detection.summary()}")
 }
 // A missing menu may not be used as a click anchor even when HUD is accepted.
 for(name in listOf("bygone-scene", "password-submit", "line-switch")) {
 val result=GameScreenDetector.inspectHud(load("tests/hud/fixtures/$name.png"),template)
 println("exit score $name: ${com.aliothmoon.maahotta.vision.BygoneScreenDetector.findExitIcon(load("tests/hud/fixtures/$name.png"), template.dungeonExit)?.score}")
 println("negative $name: ${result.summary()}")
 check(!result.accepted)
 }
 val wide=load("tests/hud/fixtures/main-wide.png")
 println("exit score wide: ${com.aliothmoon.maahotta.vision.BygoneScreenDetector.findExitIcon(wide,template.dungeonExit)?.score}")
 val detection=GameScreenDetector.inspectHud(wide,template)
 println("wide: ${detection.summary()}")
 check(detection.accepted)
 check(GameScreenDetector.stablePair(detection,detection,wide.height))
 for(hide in listOf("menu","minimap","dodge","health","both")) {
 val img=java.awt.image.BufferedImage(wide.width,wide.height,java.awt.image.BufferedImage.TYPE_INT_ARGB)
 val g=img.createGraphics();g.drawImage(wide.image,0,0,null);g.color=java.awt.Color.BLACK
 if(hide=="menu" || hide=="both") g.fillRect((wide.width*.85).toInt(),0,wide.width, (wide.height*.13).toInt())
 if(hide=="minimap" || hide=="both") g.fillRect(0,0,(wide.width*.25).toInt(),(wide.height*.42).toInt())
 if(hide=="dodge") g.fillRect((wide.width*.80).toInt(),(wide.height*.73).toInt(),wide.width,wide.height)
 if(hide=="health") g.fillRect((wide.width*.32).toInt(),(wide.height*.88).toInt(),(wide.width*.35).toInt(),wide.height)
 g.dispose()
 val frame=Bitmap(img)
 val result=GameScreenDetector.inspectHud(frame,template)
 println("missing $hide: ${result.summary()}")
 check(result.accepted == (hide!="both" && hide!="menu"))
 if(hide=="menu" || hide=="both") {
 check(GameScreenDetector.findHudMenu(frame,template)==null)
 check(GameScreenDetector.findGiftHudIcon(frame,template)==null)
 }
 }
 val misplaced=java.awt.image.BufferedImage(wide.width,wide.height,java.awt.image.BufferedImage.TYPE_INT_ARGB)
 val pen=misplaced.createGraphics();pen.drawImage(wide.image,0,0,null)
 pen.drawImage(wide.image,100,0,100+(wide.width*.15).toInt(),(wide.height*.13).toInt(),(wide.width*.85).toInt(),0,wide.width,(wide.height*.13).toInt(),null)
 pen.color=java.awt.Color.BLACK;pen.fillRect((wide.width*.85).toInt(),0,wide.width,(wide.height*.13).toInt());pen.dispose()
 check(!GameScreenDetector.inspectHud(Bitmap(misplaced),template).accepted) { "Menu outside ROI was accepted" }
 check(GameScreenDetector.inspectHud(wide,com.aliothmoon.maahotta.vision.HudTemplates(template.menu)).accepted)
 check(!GameScreenDetector.stablePair(detection, detection.copy(accepted=false), wide.height))
 check(!GameScreenDetector.stablePair(detection, detection.copy(features=detection.features.mapValues { (_, hit) -> hit.copy(point=android.graphics.Point(hit.point.x+100,hit.point.y)) }),wide.height))
 check(GameScreenDetector.findHudMenu(original,null)==null)
 println("PASS missing menu template")
}
