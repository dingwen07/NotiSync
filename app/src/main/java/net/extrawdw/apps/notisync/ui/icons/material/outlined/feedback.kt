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
public val feedback: ImageVector
  get() {
    if (_feedback != null) {
      return _feedback!!
    }
    _feedback =
      ImageVector.Builder(
          name = "feedback",
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
            moveTo(12f, 15f)
            quadToRelative(0.43f, 0f, 0.71f, -0.29f)
            reflectiveQuadTo(13f, 14f)
            reflectiveQuadTo(12.71f, 13.29f)
            reflectiveQuadTo(12f, 13f)
            reflectiveQuadToRelative(-0.71f, 0.29f)
            reflectiveQuadTo(11f, 14f)
            reflectiveQuadToRelative(0.29f, 0.71f)
            reflectiveQuadTo(12f, 15f)
            close()
            moveTo(11f, 11f)
            horizontalLineToRelative(2f)
            verticalLineTo(5f)
            horizontalLineTo(11f)
            verticalLineToRelative(6f)
            close()
            moveTo(2f, 22f)
            verticalLineTo(4f)
            quadTo(2f, 3.17f, 2.59f, 2.59f)
            reflectiveQuadTo(4f, 2f)
            horizontalLineTo(20f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(22f, 4f)
            verticalLineTo(16f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(20f, 18f)
            horizontalLineTo(6f)
            lineTo(2f, 22f)
            close()
            moveTo(5.15f, 16f)
            horizontalLineTo(20f)
            verticalLineTo(4f)
            horizontalLineTo(4f)
            verticalLineTo(17.13f)
            lineTo(5.15f, 16f)
            close()
            moveTo(4f, 16f)
            verticalLineTo(4f)
            verticalLineTo(16f)
            close()
          }
        }
        .build()
    return _feedback!!
  }

private var _feedback: ImageVector? = null
