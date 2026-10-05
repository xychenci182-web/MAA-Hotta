# HUD screenshot regression

Run `python tests/hud/run.py` from this checkout. Requires JDK 21 (JAVA_HOME) and the Gradle-cached Kotlin 2.1.10 compiler dependencies. The runner compiles the production Kotlin detector and matcher with a small Java2D-backed Android graphics adapter; it is not an Android device test.

Checks the supplied main screen at 360/720/1080 heights, rejects the supplied supply page and a confirmation overlay, and rejects frames missing either the minimap controls or menu. PNG fixtures remain local. Pixel interpolation on Android can differ from Java2D; device verification is still required.
