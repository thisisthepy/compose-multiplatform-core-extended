package hello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.exit
import platform.posix.getenv
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Opens one window and hosts a Material 3 screen in it.
 *
 * With HELLO_SELF_CHECK set it exits on its own: once the content has composed and a frame
 * drawn after that has reached the window, it prints `gradle-macos check ok` with the frame's
 * size, and if that has not happened within 30 seconds it prints why and exits with 1.
 */
@OptIn(ExperimentalForeignApi::class)
fun main() {
    val selfCheck = getenv("HELLO_SELF_CHECK") != null
    val application = NSApplication.sharedApplication()
    // An executable outside a bundle is not put in front or given the keyboard unless asked.
    application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)

    var composed = false
    val window = MacosWindow(name = "Hello from Gradle", width = 480, height = 320)
    if (selfCheck) {
        window.onFrameDrawn = { width, height ->
            if (composed) {
                println("gradle-macos check ok: Material 3 content drawn at ${width}x$height pixels")
                exit(0)
            }
        }
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 30_000_000_000L), dispatch_get_main_queue()) {
            println("gradle-macos check failed: ${if (composed) "no frame reached the window" else "the content never composed"} in 30 seconds")
            exit(1)
        }
    }
    window.setContent {
        Greeting()
        SideEffect {
            if (!composed) {
                composed = true
                window.requestFrame()
            }
        }
    }

    application.activateIgnoringOtherApps(true)
    application.run()
}

@Composable
private fun Greeting() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var clicks by remember { mutableStateOf(0) }
                Text("Compose on Kotlin/Native, in the fork's AppKit window", style = MaterialTheme.typography.titleMedium)
                Text("Built by Gradle against org.thisisthepy.compose.window:native-macos")
                Button(onClick = { clicks++ }) { Text(if (clicks == 0) "Click me" else "Clicked $clicks times") }
            }
        }
    }
}
