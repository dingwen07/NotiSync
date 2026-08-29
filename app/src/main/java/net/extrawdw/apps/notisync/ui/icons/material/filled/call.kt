package net.extrawdw.apps.notisync.ui.icons.material.filled

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
public val call: ImageVector
  get() {
    if (_call != null) {
      return _call!!
    }
    _call =
      ImageVector.Builder(
          name = "call",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.Companion.NonZero,
          ) {
            moveTo(19.95f, 21f)
            quadToRelative(-3.13f, 0f, -6.18f, -1.36f)
            reflectiveQuadTo(8.23f, 15.78f)
            quadTo(5.73f, 13.27f, 4.36f, 10.23f)
            reflectiveQuadTo(3f, 4.05f)
            quadTo(3f, 3.6f, 3.3f, 3.3f)
            reflectiveQuadTo(4.05f, 3f)
            horizontalLineTo(8.1f)
            quadTo(8.45f, 3f, 8.73f, 3.24f)
            reflectiveQuadTo(9.05f, 3.8f)
            lineTo(9.7f, 7.3f)
            quadTo(9.75f, 7.7f, 9.68f, 7.97f)
            reflectiveQuadTo(9.4f, 8.45f)
            lineTo(6.98f, 10.9f)
            quadToRelative(0.5f, 0.93f, 1.19f, 1.79f)
            reflectiveQuadToRelative(1.51f, 1.66f)
            quadToRelative(0.78f, 0.78f, 1.63f, 1.44f)
            reflectiveQuadTo(13.1f, 17f)
            lineToRelative(2.35f, -2.35f)
            quadToRelative(0.22f, -0.23f, 0.59f, -0.34f)
            reflectiveQuadToRelative(0.71f, -0.06f)
            lineToRelative(3.45f, 0.7f)
            quadToRelative(0.35f, 0.1f, 0.57f, 0.36f)
            reflectiveQuadTo(21f, 15.9f)
            verticalLineToRelative(4.05f)
            quadToRelative(0f, 0.45f, -0.3f, 0.75f)
            reflectiveQuadTo(19.95f, 21f)
            close()
          }
        }
        .build()
    return _call!!
  }

private var _call: ImageVector? = null
