package org.thisisthepy.compose.adaptive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.thisisthepy.compose.designsystem.ButtonVariant
import org.thisisthepy.compose.designsystem.ColorRole
import org.thisisthepy.compose.designsystem.DesignSystemId
import org.thisisthepy.compose.designsystem.LocalDesignSystem
import org.thisisthepy.compose.designsystem.ShapeRole
import org.thisisthepy.compose.designsystem.SpaceRole
import org.thisisthepy.compose.designsystem.TypeRole
import org.thisisthepy.compose.material3.MaterialButton

/**
 * Neutral button that delegates to the active Layer 1 design system installed by [AdaptiveTheme].
 */
@Composable
fun AdaptiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Filled,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val system = LocalDesignSystem.current
    if (system.id == DesignSystemId.Material3) {
        MaterialButton(
            variant = variant,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            content = content,
        )
        return
    }
    val style = system.button(variant)
    val shape = system.shape(style.shape)
    val bg = if (enabled) style.container else style.container.copy(alpha = 0.38f)
    val borderMod = style.border?.let { color ->
        Modifier.border(BorderStroke(style.borderWidth, color), shape)
    } ?: Modifier
    Row(
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .then(borderMod)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 36.dp)
            .padding(
                PaddingValues(
                    horizontal = system.space(SpaceRole.Md),
                    vertical = system.space(SpaceRole.Sm),
                ),
            )
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Neutral text styled by the active Layer 1 design system's [TypeRole] and [ColorRole]. */
@Composable
fun AdaptiveText(
    text: String,
    modifier: Modifier = Modifier,
    typeRole: TypeRole = TypeRole.Body,
    colorRole: ColorRole = ColorRole.OnSurface,
) {
    val system = LocalDesignSystem.current
    val style = system.type(typeRole).copy(color = system.color(colorRole))
    BasicText(text = text, modifier = modifier, style = style)
}

/** Neutral surface painted with the active Layer 1 design system's colour and shape roles. */
@Composable
fun AdaptiveSurface(
    modifier: Modifier = Modifier,
    colorRole: ColorRole = ColorRole.Surface,
    shapeRole: ShapeRole = ShapeRole.Medium,
    content: @Composable BoxScope.() -> Unit,
) {
    val system = LocalDesignSystem.current
    val shape = system.shape(shapeRole)
    Box(
        modifier = modifier
            .clip(shape)
            .background(system.color(colorRole), shape),
        content = content,
    )
}

/** Neutral divider using the active Layer 1 design system's thickness and outline colour. */
@Composable
fun AdaptiveDivider(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    thickness: Dp? = null,
    color: Color? = null,
) {
    val style = LocalDesignSystem.current.divider()
    val stroke = thickness ?: style.thickness
    val ink = color ?: style.color
    val sized = if (vertical) {
        modifier.fillMaxHeight().width(stroke)
    } else {
        modifier.fillMaxWidth().height(stroke)
    }
    Box(modifier = sized.background(ink))
}
