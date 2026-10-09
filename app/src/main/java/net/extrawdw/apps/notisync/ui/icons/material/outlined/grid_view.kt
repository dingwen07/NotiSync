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
public val grid_view: ImageVector
  get() {
    if (_grid_view != null) {
      return _grid_view!!
    }
    _grid_view =
      ImageVector.Builder(
          name = "grid_view",
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
            moveTo(3f, 11f)
            verticalLineTo(3f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(8f)
            horizontalLineTo(3f)
            close()
            moveTo(3f, 21f)
            verticalLineTo(13f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(8f)
            horizontalLineTo(3f)
            close()
            moveTo(13f, 11f)
            verticalLineTo(3f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(8f)
            horizontalLineTo(13f)
            close()
            moveToRelative(0f, 10f)
            verticalLineTo(13f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(8f)
            horizontalLineTo(13f)
            close()
            moveTo(5f, 9f)
            horizontalLineTo(9f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            verticalLineTo(9f)
            close()
            moveTo(15f, 9f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(15f)
            verticalLineTo(9f)
            close()
            moveToRelative(0f, 10f)
            horizontalLineToRelative(4f)
            verticalLineTo(15f)
            horizontalLineTo(15f)
            verticalLineToRelative(4f)
            close()
            moveTo(5f, 19f)
            horizontalLineTo(9f)
            verticalLineTo(15f)
            horizontalLineTo(5f)
            verticalLineToRelative(4f)
            close()
            moveTo(15f, 9f)
            close()
            moveToRelative(0f, 6f)
            close()
            moveTo(9f, 15f)
            close()
            moveTo(9f, 9f)
            close()
          }
        }
        .build()
    return _grid_view!!
  }

private var _grid_view: ImageVector? = null
