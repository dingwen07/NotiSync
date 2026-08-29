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
public val drag_handle: ImageVector
  get() {
    if (_drag_handle != null) {
      return _drag_handle!!
    }
    _drag_handle =
      ImageVector.Builder(
          name = "drag_handle",
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
            moveTo(4f, 15f)
            verticalLineTo(13f)
            horizontalLineTo(20f)
            verticalLineToRelative(2f)
            horizontalLineTo(4f)
            close()
            moveTo(4f, 11f)
            verticalLineTo(9f)
            horizontalLineTo(20f)
            verticalLineToRelative(2f)
            horizontalLineTo(4f)
            close()
          }
        }
        .build()
    return _drag_handle!!
  }

private var _drag_handle: ImageVector? = null
