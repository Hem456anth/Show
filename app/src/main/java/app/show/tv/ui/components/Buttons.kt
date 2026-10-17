package app.show.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Show's two button styles, the only ones the app uses:
 *  - primary:   white fill, black label (21:1 contrast)
 *  - secondary: black fill, white label, white 1.5 dp outline so it stays visible on dark surfaces
 * Both share shape, padding and a 48 dp minimum touch target.
 */
val ButtonShape = RoundedCornerShape(12.dp)
private val Padding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)

@Composable
fun ShowPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonShape, content: @Composable RowScope.() -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, shape = shape, contentPadding = Padding,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White, contentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.38f), disabledContentColor = Color.Black.copy(alpha = 0.6f),
        ),
        content = content,
    )

@Composable
fun ShowSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonShape, content: @Composable RowScope.() -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, shape = shape, contentPadding = Padding,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        border = BorderStroke(1.5.dp, if (enabled) Color.White else Color.White.copy(alpha = 0.38f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Black, contentColor = Color.White,
            disabledContainerColor = Color.Black, disabledContentColor = Color.White.copy(alpha = 0.38f),
        ),
        content = content,
    )
