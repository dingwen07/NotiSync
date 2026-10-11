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
public val browse_activity: ImageVector
  get() {
    if (_browse_activity != null) {
      return _browse_activity!!
    }
    _browse_activity =
      ImageVector.Builder(
          name = "browse_activity",
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
            moveTo(2f, 9f)
            verticalLineTo(5f)
            quadTo(2f, 4.17f, 2.59f, 3.59f)
            reflectiveQuadTo(4f, 3f)
            horizontalLineTo(20f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(22f, 5f)
            verticalLineTo(9f)
            horizontalLineTo(20f)
            verticalLineTo(5f)
            horizontalLineTo(4f)
            verticalLineTo(9f)
            horizontalLineTo(2f)
            close()
            moveToRelative(2f, 9f)
            quadTo(3.18f, 18f, 2.59f, 17.41f)
            reflectiveQuadTo(2f, 16f)
            verticalLineTo(11f)
            horizontalLineTo(4f)
            verticalLineToRelative(5f)
            horizontalLineTo(20f)
            verticalLineTo(11f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(5f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(20f, 18f)
            horizontalLineTo(4f)
            close()
            moveTo(1f, 21f)
            verticalLineTo(19f)
            horizontalLineTo(23f)
            verticalLineToRelative(2f)
            horizontalLineTo(1f)
            close()
            moveTo(12f, 10.5f)
            close()
            moveTo(2f, 11f)
            verticalLineTo(9f)
            horizontalLineTo(8f)
            quadTo(8.28f, 9f, 8.53f, 9.15f)
            reflectiveQuadTo(8.9f, 9.55f)
            lineToRelative(1.18f, 2.33f)
            lineTo(13.15f, 6.5f)
            quadTo(13.28f, 6.27f, 13.5f, 6.14f)
            reflectiveQuadTo(14f, 6f)
            quadToRelative(0.28f, 0f, 0.53f, 0.14f)
            reflectiveQuadTo(14.9f, 6.55f)
            lineTo(16.13f, 9f)
            horizontalLineTo(22f)
            verticalLineToRelative(2f)
            horizontalLineTo(15.5f)
            quadToRelative(-0.27f, 0f, -0.52f, -0.14f)
            quadTo(14.73f, 10.73f, 14.6f, 10.45f)
            lineTo(13.95f, 9.13f)
            lineTo(10.88f, 14.5f)
            quadToRelative(-0.13f, 0.25f, -0.38f, 0.38f)
            reflectiveQuadTo(9.98f, 15f)
            reflectiveQuadTo(9.46f, 14.85f)
            reflectiveQuadTo(9.1f, 14.45f)
            lineTo(7.38f, 11f)
            horizontalLineTo(2f)
            close()
          }
        }
        .build()
    return _browse_activity!!
  }

private var _browse_activity: ImageVector? = null
