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
public val view_carousel: ImageVector
  get() {
    if (_view_carousel != null) {
      return _view_carousel!!
    }
    _view_carousel =
      ImageVector.Builder(
          name = "view_carousel",
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
            moveTo(2f, 15f)
            verticalLineTo(9f)
            quadTo(2f, 8.17f, 2.59f, 7.59f)
            reflectiveQuadTo(4f, 7f)
            quadTo(4.83f, 7f, 5.41f, 7.59f)
            reflectiveQuadTo(6f, 9f)
            verticalLineToRelative(6f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(4f, 17f)
            reflectiveQuadTo(2.59f, 16.41f)
            reflectiveQuadTo(2f, 15f)
            close()
            moveToRelative(7f, 4f)
            quadTo(8.18f, 19f, 7.59f, 18.41f)
            reflectiveQuadTo(7f, 17f)
            verticalLineTo(7f)
            quadTo(7f, 6.18f, 7.59f, 5.59f)
            reflectiveQuadTo(9f, 5f)
            horizontalLineToRelative(6f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(17f, 6.18f, 17f, 7f)
            verticalLineTo(17f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(15f, 19f)
            horizontalLineTo(9f)
            close()
            moveToRelative(9f, -4f)
            verticalLineTo(9f)
            quadTo(18f, 8.17f, 18.59f, 7.59f)
            reflectiveQuadTo(20f, 7f)
            reflectiveQuadToRelative(1.41f, 0.59f)
            reflectiveQuadTo(22f, 9f)
            verticalLineToRelative(6f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(20f, 17f)
            quadToRelative(-0.82f, 0f, -1.41f, -0.59f)
            reflectiveQuadTo(18f, 15f)
            close()
            moveTo(9f, 17f)
            horizontalLineToRelative(6f)
            verticalLineTo(7f)
            horizontalLineTo(9f)
            verticalLineTo(17f)
            close()
            moveToRelative(3f, -5f)
            close()
          }
        }
        .build()
    return _view_carousel!!
  }

private var _view_carousel: ImageVector? = null
