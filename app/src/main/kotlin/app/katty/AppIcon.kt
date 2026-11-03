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
 * the windows. The icon of the application, which macOS shows in the Dock and the application switcher, is the logo on a rounded square, which the
 * build draws from the same file (see `appIcon` in `app/build.gradle.kts`): it is in the resources too, and the icon of the application that is
 * packaged is made from it.
 */
internal object AppIcon {
    /** Where the logo is in the resources. */
    const val RESOURCE = "/app/katty/logo.png"

    /** Where the icon of the application is in the resources. */
    const val ICON = "/app/katty/icon.png"

    /** The image at [resource], the logo by default, or `null` when it is not in the resources. */
    fun image(resource: String = RESOURCE): BufferedImage? = AppIcon::class.java.getResourceAsStream(resource)?.use { ImageIO.read(it) }

    /** The logo as the icon of a window, or `null` when it is not in the resources. */
    fun painter(): Painter? = image()?.toComposeImageBitmap()?.let(::BitmapPainter)

    /**
     * Makes the icon of the application the one that macOS shows in the Dock and in the application switcher (⌘Tab) for this process. An application
     * that is packaged has the icon of its bundle, but one that is started by Gradle, or from a jar, would be a Java program with the icon of Java.
     * Nothing is done where the system has no such icon to set (a machine with no screen, for instance): it is a nicety, and it must not stop the
     * application.
     */
    fun installInDock() {
        try {
            if (!Taskbar.isTaskbarSupported()) return
            val taskbar = Taskbar.getTaskbar()
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
            image(ICON)?.let { taskbar.iconImage = it } ?: logger.warn("The icon is not in the resources: {}", ICON)
        } catch (e: Exception) {
            logger.warn("Could not set the icon of the Dock", e)
        }
    }
}
