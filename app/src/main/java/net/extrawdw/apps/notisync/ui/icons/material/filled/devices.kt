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
public val devices: ImageVector
  get() {
    if (_devices != null) {
      return _devices!!
    }
    _devices =
      ImageVector.Builder(
          name = "devices",
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
            moveTo(5f, 17f)
            quadTo(4.18f, 17f, 3.59f, 16.41f)
            reflectiveQuadTo(3f, 15f)
            verticalLineTo(6f)
            quadTo(3f, 5.18f, 3.59f, 4.59f)
            reflectiveQuadTo(5f, 4f)
            horizontalLineTo(19f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(21f, 5.18f, 21f, 6f)
            horizontalLineTo(15.5f)
            quadTo(14.05f, 6f, 13.03f, 7.02f)
            reflectiveQuadTo(12f, 9.5f)
            verticalLineTo(17f)
            horizontalLineTo(5f)
            close()
            moveToRelative(10.5f, 3f)
            quadToRelative(-0.63f, 0f, -1.06f, -0.44f)
            reflectiveQuadTo(14f, 18.5f)
            verticalLineToRelative(-9f)
            quadTo(14f, 8.88f, 14.44f, 8.44f)
            reflectiveQuadTo(15.5f, 8f)
            horizontalLineToRelative(5f)
            quadToRelative(0.63f, 0f, 1.06f, 0.44f)
            reflectiveQuadTo(22f, 9.5f)
            verticalLineToRelative(9f)
            quadToRelative(0f, 0.63f, -0.44f, 1.06f)
            reflectiveQuadTo(20.5f, 20f)
            horizontalLineToRelative(-5f)
            close()
            moveTo(2f, 20f)
            verticalLineTo(18f)
            horizontalLineTo(12f)
            verticalLineToRelative(2f)
            horizontalLineTo(2f)
            close()
            moveTo(18f, 12.5f)
            quadToRelative(0.32f, 0f, 0.54f, -0.23f)
            reflectiveQuadToRelative(0.21f, -0.52f)
            quadToRelative(0f, -0.33f, -0.21f, -0.54f)
            reflectiveQuadTo(18f, 11f)
            quadToRelative(-0.3f, 0f, -0.52f, 0.21f)
            quadToRelative(-0.23f, 0.21f, -0.23f, 0.54f)
            quadToRelative(0f, 0.3f, 0.23f, 0.52f)
            reflectiveQuadTo(18f, 12.5f)
            close()
          }
        }
        .build()
    return _devices!!
  }

private var _devices: ImageVector? = null
