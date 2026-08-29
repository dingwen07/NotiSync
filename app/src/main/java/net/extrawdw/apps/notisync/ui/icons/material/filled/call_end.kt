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
public val call_end: ImageVector
  get() {
    if (_call_end != null) {
      return _call_end!!
    }
    _call_end =
      ImageVector.Builder(
          name = "call_end",
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
            moveTo(12f, 8f)
            quadToRelative(2.95f, 0f, 5.81f, 1.19f)
            quadToRelative(2.86f, 1.19f, 5.09f, 3.56f)
            quadToRelative(0.3f, 0.3f, 0.3f, 0.7f)
            reflectiveQuadToRelative(-0.3f, 0.7f)
            lineTo(20.6f, 16.4f)
            quadToRelative(-0.27f, 0.28f, -0.64f, 0.3f)
            quadTo(19.6f, 16.73f, 19.3f, 16.5f)
            lineTo(16.4f, 14.3f)
            quadTo(16.2f, 14.15f, 16.1f, 13.95f)
            reflectiveQuadTo(16f, 13.5f)
            verticalLineTo(10.65f)
            quadToRelative(-0.95f, -0.3f, -1.95f, -0.47f)
            reflectiveQuadTo(12f, 10f)
            reflectiveQuadTo(9.95f, 10.17f)
            reflectiveQuadTo(8f, 10.65f)
            verticalLineTo(13.5f)
            quadToRelative(0f, 0.25f, -0.1f, 0.45f)
            reflectiveQuadTo(7.6f, 14.3f)
            lineTo(4.7f, 16.5f)
            quadTo(4.4f, 16.73f, 4.04f, 16.7f)
            quadTo(3.68f, 16.68f, 3.4f, 16.4f)
            lineTo(1.1f, 14.15f)
            quadTo(0.8f, 13.85f, 0.8f, 13.45f)
            reflectiveQuadToRelative(0.3f, -0.7f)
            quadTo(3.3f, 10.38f, 6.18f, 9.19f)
            reflectiveQuadTo(12f, 8f)
            close()
          }
        }
        .build()
    return _call_end!!
  }

private var _call_end: ImageVector? = null
