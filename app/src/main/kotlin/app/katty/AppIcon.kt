package app.katty

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.slf4j.LoggerFactory
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private val logger = LoggerFactory.getLogger("app.katty.AppIcon")

/**
 * The logo of the application, `logo.png` of the root of the repository, which the build puts in the resources of the application: it is the icon of
 * the windows, and of the Dock and the application switcher while the application runs. The icon of the application that is packaged is made from
 * the same file by the build (see `macIcon` in `app/build.gradle.kts`).
 */
internal object AppIcon {
    /** Where the logo is in the resources. */
    const val RESOURCE = "/app/katty/logo.png"

    /** The logo, or `null` when it is not in the resources. */
    fun image(): BufferedImage? = AppIcon::class.java.getResourceAsStream(RESOURCE)?.use { ImageIO.read(it) }

    /** The logo as the icon of a window, or `null` when it is not in the resources. */
    fun painter(): Painter? = image()?.toComposeImageBitmap()?.let(::BitmapPainter)

    /**
     * Makes the logo the icon that macOS shows in the Dock and in the application switcher (⌘Tab) for this process. An application that is packaged has
     * the icon of its bundle, but one that is started by Gradle, or from a jar, would be a Java program with the icon of Java. Nothing is done where the
     * system has no such icon to set (a machine with no screen, for instance): it is a nicety, and it must not stop the application.
     */
    fun installInDock() {
        try {
            if (!Taskbar.isTaskbarSupported()) return
            val taskbar = Taskbar.getTaskbar()
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
            image()?.let { taskbar.iconImage = it } ?: logger.warn("The logo is not in the resources: {}", RESOURCE)
        } catch (e: Exception) {
            logger.warn("Could not set the icon of the Dock", e)
        }
    }
}
