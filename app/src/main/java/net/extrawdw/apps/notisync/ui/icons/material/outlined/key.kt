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
public val key: ImageVector
  get() {
    if (_key != null) {
      return _key!!
    }
    _key =
      ImageVector.Builder(
          name = "key",
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
            moveTo(5.59f, 13.41f)
            quadTo(5f, 12.83f, 5f, 12f)
            reflectiveQuadTo(5.59f, 10.59f)
            reflectiveQuadTo(7f, 10f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(9f, 11.18f, 9f, 12f)
            reflectiveQuadTo(8.41f, 13.41f)
            quadTo(7.83f, 14f, 7f, 14f)
            reflectiveQuadTo(5.59f, 13.41f)
            close()
            moveTo(7f, 18f)
            quadTo(4.5f, 18f, 2.75f, 16.25f)
            reflectiveQuadTo(1f, 12f)
            reflectiveQuadTo(2.75f, 7.75f)
            reflectiveQuadTo(7f, 6f)
            quadToRelative(1.68f, 0f, 3.04f, 0.82f)
            reflectiveQuadTo(12.2f, 9f)
            horizontalLineTo(21f)
            lineToRelative(3f, 3f)
            lineToRelative(-4.5f, 4.5f)
            lineTo(17.5f, 15f)
            lineToRelative(-2f, 1.5f)
            lineTo(13.38f, 15f)
            horizontalLineTo(12.2f)
            quadToRelative(-0.8f, 1.35f, -2.16f, 2.18f)
            reflectiveQuadTo(7f, 18f)
            close()
            moveTo(7f, 16f)
            quadToRelative(1.4f, 0f, 2.46f, -0.85f)
            reflectiveQuadTo(10.88f, 13f)
            horizontalLineTo(14f)
            lineToRelative(1.45f, 1.02f)
            lineTo(17.5f, 12.5f)
            lineToRelative(1.78f, 1.38f)
            lineTo(21.15f, 12f)
            lineToRelative(-1f, -1f)
            horizontalLineTo(10.88f)
            quadTo(10.53f, 9.7f, 9.46f, 8.85f)
            reflectiveQuadTo(7f, 8f)
            quadTo(5.35f, 8f, 4.18f, 9.17f)
            reflectiveQuadTo(3f, 12f)
            reflectiveQuadToRelative(1.17f, 2.82f)
            reflectiveQuadTo(7f, 16f)
            close()
          }
        }
        .build()
    return _key!!
  }

private var _key: ImageVector? = null
