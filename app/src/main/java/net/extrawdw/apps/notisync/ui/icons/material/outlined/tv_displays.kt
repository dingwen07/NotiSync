package net.extrawdw.apps.notisync.ui.icons.material.outlined

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
public val tv_displays: ImageVector
  get() {
    if (_tv_displays != null) {
      return _tv_displays!!
    }
    _tv_displays =
      ImageVector.Builder(
          name = "tv_displays",
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
            moveTo(2f, 16f)
            verticalLineTo(4f)
            quadTo(2f, 3.17f, 2.59f, 2.59f)
            reflectiveQuadTo(4f, 2f)
            horizontalLineTo(18f)
            verticalLineTo(4f)
            horizontalLineTo(4f)
            verticalLineTo(16f)
            horizontalLineTo(2f)
            close()
            moveToRelative(9f, 6f)
            verticalLineTo(20f)
            horizontalLineTo(8f)
            quadTo(7.18f, 20f, 6.59f, 19.41f)
            reflectiveQuadTo(6f, 18f)
            verticalLineTo(8f)
            quadTo(6f, 7.18f, 6.59f, 6.59f)
            reflectiveQuadTo(8f, 6f)
            horizontalLineTo(21f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(23f, 7.18f, 23f, 8f)
            verticalLineTo(18f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(21f, 20f)
            horizontalLineTo(18f)
            verticalLineToRelative(2f)
            horizontalLineTo(11f)
            close()
            moveTo(8f, 18f)
            horizontalLineTo(21f)
            verticalLineTo(8f)
            horizontalLineTo(8f)
            verticalLineTo(18f)
            close()
            moveToRelative(6.5f, -5f)
            close()
          }
        }
        .build()
    return _tv_displays!!
  }

private var _tv_displays: ImageVector? = null
