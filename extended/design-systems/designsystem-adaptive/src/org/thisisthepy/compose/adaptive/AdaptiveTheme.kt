package org.thisisthepy.compose.adaptive

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.thisisthepy.compose.breeze.BreezeDesignSystem
import org.thisisthepy.compose.cupertino.CupertinoDesignSystem
import org.thisisthepy.compose.deepin.DeepinDesignSystem
import org.thisisthepy.compose.designsystem.DesignSystem
import org.thisisthepy.compose.designsystem.DesignSystemId
import org.thisisthepy.compose.designsystem.LocalDesignSystem
import org.thisisthepy.compose.fluent.FluentDesignSystem
import org.thisisthepy.compose.gnome.GnomeDesignSystem
import org.thisisthepy.compose.material3.Material3DesignSystem

/** Resolves a Layer 1 [DesignSystem] instance for this [DesignSystemId] and colour scheme. */
fun DesignSystemId.resolve(darkTheme: Boolean): DesignSystem = when (this) {
    DesignSystemId.Material3 -> if (darkTheme) Material3DesignSystem.Dark else Material3DesignSystem.Light
    DesignSystemId.Cupertino -> if (darkTheme) CupertinoDesignSystem.Dark else CupertinoDesignSystem.Light
    DesignSystemId.Fluent -> if (darkTheme) FluentDesignSystem.Dark else FluentDesignSystem.Light
    DesignSystemId.Gnome -> GnomeDesignSystem.of(darkTheme)
    DesignSystemId.Breeze -> BreezeDesignSystem.of(darkTheme)
    DesignSystemId.Deepin -> DeepinDesignSystem.of(darkTheme)
}

/** Returns the host platform's default [DesignSystem] for [darkTheme]. */
fun platformDesignSystem(darkTheme: Boolean): DesignSystem =
    platformDefaultDesignSystemId().resolve(darkTheme)

/**
 * Layer 2 entry point: installs [designSystem] into [LocalDesignSystem] and configures the
 * matching Layer 1 theme wrapper so adaptive components delegate to the active system.
 */
@Composable
fun AdaptiveTheme(
    designSystem: DesignSystem = platformDesignSystem(darkTheme = isSystemInDarkTheme()),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val resolved = if (designSystem.isDark == darkTheme) {
        designSystem
    } else {
        designSystem.id.resolve(darkTheme)
    }
    CompositionLocalProvider(LocalDesignSystem provides resolved) {
        if (resolved.id == DesignSystemId.Material3) {
            MaterialTheme(
                colorScheme = if (resolved.isDark) darkColorScheme() else lightColorScheme(),
                content = content,
            )
        } else {
            content()
        }
    }
}
