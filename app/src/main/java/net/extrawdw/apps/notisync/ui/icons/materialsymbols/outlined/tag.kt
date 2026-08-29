package net.extrawdw.apps.notisync.ui.icons.materialsymbols.outlined

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
public val tag: ImageVector
  get() {
    if (_tag != null) {
      return _tag!!
    }
    _tag =
      ImageVector.Builder(
        name = "tag",
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
            moveTo(6f, 20f)
            lineTo(7f, 16f)
            horizontalLineTo(3f)
            lineTo(3.5f, 14f)
            horizontalLineToRelative(4f)
            lineToRelative(1f, -4f)
            horizontalLineToRelative(-4f)
            lineTo(5f, 8f)
            horizontalLineTo(9f)
            lineTo(10f, 4f)
            horizontalLineToRelative(2f)
            lineTo(11f, 8f)
            horizontalLineToRelative(4f)
            lineTo(16f, 4f)
            horizontalLineToRelative(2f)
            lineTo(17f, 8f)
            horizontalLineToRelative(4f)
            lineToRelative(-0.5f, 2f)
            horizontalLineToRelative(-4f)
            lineToRelative(-1f, 4f)
            horizontalLineToRelative(4f)
            lineTo(19f, 16f)
            horizontalLineTo(15f)
            lineToRelative(-1f, 4f)
            horizontalLineTo(12f)
            lineToRelative(1f, -4f)
            horizontalLineTo(9f)
            lineTo(8f, 20f)
            horizontalLineTo(6f)
            close()
            moveTo(9.5f, 14f)
            horizontalLineToRelative(4f)
            lineToRelative(1f, -4f)
            horizontalLineToRelative(-4f)
            lineToRelative(-1f, 4f)
            close()
          }
        }
        .build()
    return _tag!!
  }

private var _tag: ImageVector? = null
