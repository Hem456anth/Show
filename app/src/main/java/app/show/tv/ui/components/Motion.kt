package app.show.tv.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Apple TV-style "lift": a focused card (D-pad, keyboard, TV remote) rises with a soft spring,
 * casts a shadow and gets a white ring; a press sinks it slightly. Touch users get the press;
 * TV and keyboard users get the focus lift, so focus is always visible.
 */
@Composable
fun Modifier.lift(interaction: InteractionSource, shape: Shape, reduceMotion: Boolean = rememberReduceMotion()): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val target = when { pressed -> 0.96f; focused -> 1.07f; else -> 1f }
    val scale by animateFloatAsState(
        if (reduceMotion) 1f else target,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), label = "lift",
    )
    val elevation by animateDpAsState(if (focused) 18.dp else 0.dp, label = "liftShadow")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
}

/** True when the user turned animations off (Developer options or Accessibility "Remove animations"). */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}
