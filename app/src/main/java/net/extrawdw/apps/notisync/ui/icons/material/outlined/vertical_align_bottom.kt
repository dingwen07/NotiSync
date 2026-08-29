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
public val vertical_align_bottom: ImageVector
  get() {
    if (_vertical_align_bottom != null) {
      return _vertical_align_bottom!!
    }
    _vertical_align_bottom =
      ImageVector.Builder(
          name = "vertical_align_bottom",
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
            moveTo(4f, 21f)
            verticalLineTo(19f)
            horizontalLineTo(20f)
            verticalLineToRelative(2f)
            horizontalLineTo(4f)
            close()
            moveToRelative(8f, -4f)
            lineTo(7f, 12f)
            lineTo(8.4f, 10.6f)
            lineTo(11f, 13.2f)
            verticalLineTo(3f)
            horizontalLineToRelative(2f)
            verticalLineTo(13.2f)
            lineToRelative(2.6f, -2.6f)
            lineTo(17f, 12f)
            lineToRelative(-5f, 5f)
            close()
          }
        }
        .build()
    return _vertical_align_bottom!!
  }

private var _vertical_align_bottom: ImageVector? = null
