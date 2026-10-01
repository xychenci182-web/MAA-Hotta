package android.graphics
import java.awt.image.BufferedImage
import java.awt.RenderingHints
class Point(val x:Int,val y:Int) { override fun toString() = "($x,$y)" }
object Color {
 fun red(c:Int)=(c ushr 16) and 255
 fun green(c:Int)=(c ushr 8) and 255
 fun blue(c:Int)=c and 255
 fun alpha(c:Int)=(c ushr 24) and 255
 fun rgb(r:Int,g:Int,b:Int)=(255 shl 24) or (r shl 16) or (g shl 8) or b
}
class Bitmap(val image:BufferedImage) {
 val width get()=image.width
 val height get()=image.height
 fun getPixels(p:IntArray,o:Int,s:Int,x:Int,y:Int,w:Int,h:Int) { image.getRGB(x,y,w,h,p,o,s) }
 fun getPixel(x:Int,y:Int)=image.getRGB(x,y)
 fun recycle() {}
 companion object {
 fun createScaledBitmap(src:Bitmap,w:Int,h:Int,filter:Boolean):Bitmap {
 val out=BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB)
 val g=out.createGraphics()
 if(filter) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
 g.drawImage(src.image,0,0,w,h,null);g.dispose();return Bitmap(out)
 }
 }
}
