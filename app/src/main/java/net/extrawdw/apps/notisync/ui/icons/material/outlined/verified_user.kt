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
public val verified_user: ImageVector
  get() {
    if (_verified_user != null) {
      return _verified_user!!
    }
    _verified_user =
      ImageVector.Builder(
          name = "verified_user",
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
            moveTo(10.95f, 15.55f)
            lineTo(16.6f, 9.9f)
            lineTo(15.18f, 8.48f)
            lineTo(10.95f, 12.7f)
            lineTo(8.85f, 10.6f)
            lineTo(7.43f, 12.02f)
            lineToRelative(3.53f, 3.53f)
            close()
            moveTo(12f, 22f)
            quadTo(8.53f, 21.13f, 6.26f, 18.01f)
            reflectiveQuadTo(4f, 11.1f)
            verticalLineTo(5f)
            lineTo(12f, 2f)
            lineToRelative(8f, 3f)
            verticalLineToRelative(6.1f)
            quadToRelative(0f, 3.8f, -2.26f, 6.91f)
            reflectiveQuadTo(12f, 22f)
            close()
            moveToRelative(0f, -2.1f)
            quadToRelative(2.6f, -0.82f, 4.3f, -3.3f)
            reflectiveQuadTo(18f, 11.1f)
            verticalLineTo(6.38f)
            lineTo(12f, 4.13f)
            lineTo(6f, 6.38f)
            verticalLineTo(11.1f)
            quadToRelative(0f, 3.03f, 1.7f, 5.5f)
            reflectiveQuadTo(12f, 19.9f)
            close()
            moveTo(12f, 12f)
            close()
          }
        }
        .build()
    return _verified_user!!
  }

private var _verified_user: ImageVector? = null
