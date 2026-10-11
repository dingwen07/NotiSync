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
public val view_cozy: ImageVector
  get() {
    if (_view_cozy != null) {
      return _view_cozy!!
    }
    _view_cozy =
      ImageVector.Builder(
          name = "view_cozy",
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
            moveTo(5f, 10.5f)
            quadTo(4.18f, 10.5f, 3.59f, 9.91f)
            reflectiveQuadTo(3f, 8.5f)
            verticalLineTo(5f)
            quadTo(3f, 4.17f, 3.59f, 3.59f)
            reflectiveQuadTo(5f, 3f)
            horizontalLineTo(8.5f)
            quadTo(9.33f, 3f, 9.91f, 3.59f)
            reflectiveQuadTo(10.5f, 5f)
            verticalLineTo(8.5f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(8.5f, 10.5f)
            horizontalLineTo(5f)
            close()
            moveTo(5f, 21f)
            quadTo(4.18f, 21f, 3.59f, 20.41f)
            reflectiveQuadTo(3f, 19f)
            verticalLineTo(15.5f)
            quadTo(3f, 14.68f, 3.59f, 14.09f)
            reflectiveQuadTo(5f, 13.5f)
            horizontalLineTo(8.5f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadToRelative(0.59f, 0.59f, 0.59f, 1.41f)
            verticalLineTo(19f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(8.5f, 21f)
            horizontalLineTo(5f)
            close()
            moveTo(15.5f, 10.5f)
            quadToRelative(-0.82f, 0f, -1.41f, -0.59f)
            reflectiveQuadTo(13.5f, 8.5f)
            verticalLineTo(5f)
            quadToRelative(0f, -0.83f, 0.59f, -1.41f)
            reflectiveQuadTo(15.5f, 3f)
            horizontalLineTo(19f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(21f, 5f)
            verticalLineTo(8.5f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(19f, 10.5f)
            horizontalLineTo(15.5f)
            close()
            moveToRelative(0f, 10.5f)
            quadToRelative(-0.82f, 0f, -1.41f, -0.59f)
            reflectiveQuadTo(13.5f, 19f)
            verticalLineTo(15.5f)
            quadToRelative(0f, -0.83f, 0.59f, -1.41f)
            reflectiveQuadTo(15.5f, 13.5f)
            horizontalLineTo(19f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(21f, 14.68f, 21f, 15.5f)
            verticalLineTo(19f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(19f, 21f)
            horizontalLineTo(15.5f)
            close()
          }
        }
        .build()
    return _view_cozy!!
  }

private var _view_cozy: ImageVector? = null
