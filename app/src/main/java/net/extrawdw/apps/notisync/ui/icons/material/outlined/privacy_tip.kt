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
public val privacy_tip: ImageVector
  get() {
    if (_privacy_tip != null) {
      return _privacy_tip!!
    }
    _privacy_tip =
      ImageVector.Builder(
          name = "privacy_tip",
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
            moveTo(11f, 17f)
            horizontalLineToRelative(2f)
            verticalLineTo(11f)
            horizontalLineTo(11f)
            verticalLineToRelative(6f)
            close()
            moveTo(12.71f, 8.71f)
            quadTo(13f, 8.42f, 13f, 8f)
            quadTo(13f, 7.57f, 12.71f, 7.29f)
            reflectiveQuadTo(12f, 7f)
            reflectiveQuadTo(11.29f, 7.29f)
            reflectiveQuadTo(11f, 8f)
            quadToRelative(0f, 0.42f, 0.29f, 0.71f)
            reflectiveQuadTo(12f, 9f)
            reflectiveQuadTo(12.71f, 8.71f)
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
    return _privacy_tip!!
  }

private var _privacy_tip: ImageVector? = null
