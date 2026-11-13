package gearfinder.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.io.File
import java.net.URI

private object MacPlatform : Platform {
    override val dataDir = File(System.getProperty("user.home"), "Library/Application Support/Gear Finder").apply { mkdirs() }
    override fun openUrl(url: String) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Gear Finder", state = rememberWindowState(width = 1100.dp, height = 800.dp)) {
        App(MacPlatform)
    }
}
