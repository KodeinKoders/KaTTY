package app.katty

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import org.jetbrains.jewel.ui.component.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import app.katty.ui.Katty
import app.katty.ui.UiScale
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.awt.EventQueue

private val logger = LoggerFactory.getLogger("app.katty.AboutWindow")

/** Where the credits of the About window link to. */
internal const val KODEIN_KODERS_URL = "https://kodein-koders.com"

/** What the About window says of the version: the project's, and the commit it was built from (see `buildConfig` in `app/build.gradle.kts`). */
internal fun versionText(version: String = BuildConfig.VERSION, commit: String = BuildConfig.GIT_COMMIT): String =
    if (commit.isEmpty() || commit == "unknown") "Version $version" else "Version $version ($commit)"

/**
 * Makes the About item of the application menu of macOS call [onAbout], so that it shows [AboutWindow] and not the system's panel, which for a
 * Java program says Java. Nothing is done elsewhere, and a failure leaves the system's panel.
 */
internal fun takeAboutItem(onAbout: () -> Unit) {
    if (!System.getProperty("os.name").startsWith("Mac")) return
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
            Desktop.getDesktop().setAboutHandler { onAbout() }
        }
    } catch (e: Throwable) {
        logger.warn("Could not take the About item of the application menu", e)
    }
}

/**
 * Makes the Quit item of the application menu of macOS, ⌘Q, the Dock's Quit and logging out call [onQuit] before the process ends, which
 * otherwise exits at once, without writing what the application keeps. [onQuit] runs on the thread that draws, as a click on the window's close
 * button does. Nothing is done elsewhere.
 */
internal fun takeQuitItem(onQuit: () -> Unit) {
    if (!System.getProperty("os.name").startsWith("Mac")) return
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler { _, response ->
                try {
                    if (EventQueue.isDispatchThread()) onQuit() else EventQueue.invokeAndWait(onQuit)
                } finally {
                    // Not cancelled: a quit that logging out asks for would stop the logout.
                    response.performQuit()
                }
            }
        }
    } catch (e: Throwable) {
        logger.warn("Could not take the Quit item of the application menu", e)
    }
}

/**
 * The About window: the logo, the name, the version and the commit, and who made it, with a link. A window of its own, which is closed with its
 * button, Escape or ⌘W; it does not keep anything.
 */
@Composable
internal fun AboutWindow(uiScale: Float, onClose: () -> Unit) {
    val state = rememberWindowState(position = WindowPosition(Alignment.Center), width = 360.dp, height = 380.dp)
    DecoratedWindow(
        onCloseRequest = onClose,
        title = "About $APP_NAME",
        icon = remember { AppIcon.painter() },
        state = state,
        resizable = false,
        onPreviewKeyEvent = { event ->
            val close = event.type == KeyEventType.KeyDown &&
                (event.key == Key.Escape || event.isMetaPressed && !event.isShiftPressed && !event.isAltPressed && event.key == Key.W)
            if (close) onClose()
            close
        },
    ) {
        UiScale(uiScale) {
            TitleBar { Text("About $APP_NAME", color = Katty.colors.text) }
            AboutContent()
        }
    }
}

/** What the About window shows, apart from the window. */
@Composable
internal fun AboutContent(modifier: Modifier = Modifier) {
    val colors = Katty.colors
    val logo = remember { AppIcon.painter() }
    Column(
        modifier.fillMaxSize().background(colors.panel).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (logo != null) Image(logo, contentDescription = "$APP_NAME logo", modifier = Modifier.size(120.dp))
        Spacer(Modifier.height(12.dp))
        Text(APP_NAME, color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(versionText(), color = colors.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text(
            buildAnnotatedString {
                append("Made with love by ")
                withLink(
                    LinkAnnotation.Url(
                        KODEIN_KODERS_URL,
                        TextLinkStyles(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append("Kodein Koders") }
            },
            color = colors.text,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}
