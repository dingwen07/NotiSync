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
public val chevron_right: ImageVector
  get() {
    if (_chevron_right != null) {
      return _chevron_right!!
    }
    _chevron_right =
      ImageVector.Builder(
          name = "chevron_right",
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
            moveTo(12.6f, 12f)
            lineTo(8f, 7.4f)
            lineTo(9.4f, 6f)
            lineToRelative(6f, 6f)
            lineToRelative(-6f, 6f)
            lineTo(8f, 16.6f)
            lineTo(12.6f, 12f)
            close()
          }
        }
        .build()
    return _chevron_right!!
  }

private var _chevron_right: ImageVector? = null
