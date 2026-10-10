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
public val wifi_tethering: ImageVector
  get() {
    if (_wifi_tethering != null) {
      return _wifi_tethering!!
    }
    _wifi_tethering =
      ImageVector.Builder(
          name = "wifi_tethering",
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
            moveTo(5.1f, 20.25f)
            quadTo(3.68f, 18.88f, 2.84f, 17.01f)
            reflectiveQuadTo(2f, 13f)
            quadTo(2f, 10.93f, 2.79f, 9.1f)
            quadTo(3.58f, 7.27f, 4.93f, 5.93f)
            quadTo(6.28f, 4.57f, 8.1f, 3.79f)
            quadTo(9.93f, 3f, 12f, 3f)
            reflectiveQuadToRelative(3.9f, 0.79f)
            reflectiveQuadToRelative(3.17f, 2.14f)
            quadToRelative(1.35f, 1.35f, 2.14f, 3.17f)
            reflectiveQuadTo(22f, 13f)
            quadToRelative(0f, 2.15f, -0.84f, 4.02f)
            reflectiveQuadTo(18.9f, 20.25f)
            lineToRelative(-1.4f, -1.4f)
            quadToRelative(1.15f, -1.1f, 1.83f, -2.61f)
            reflectiveQuadTo(20f, 13f)
            quadTo(20f, 9.65f, 17.68f, 7.32f)
            reflectiveQuadTo(12f, 5f)
            reflectiveQuadTo(6.33f, 7.32f)
            reflectiveQuadTo(4f, 13f)
            quadToRelative(0f, 1.72f, 0.67f, 3.23f)
            reflectiveQuadToRelative(1.85f, 2.6f)
            lineTo(5.1f, 20.25f)
            close()
            moveTo(7.93f, 17.43f)
            quadTo(7.05f, 16.6f, 6.53f, 15.46f)
            reflectiveQuadTo(6f, 13f)
            quadTo(6f, 10.5f, 7.75f, 8.75f)
            reflectiveQuadTo(12f, 7f)
            reflectiveQuadToRelative(4.25f, 1.75f)
            reflectiveQuadTo(18f, 13f)
            quadToRelative(0f, 1.32f, -0.52f, 2.47f)
            reflectiveQuadToRelative(-1.4f, 1.95f)
            lineTo(14.65f, 16f)
            quadToRelative(0.63f, -0.58f, 0.99f, -1.35f)
            reflectiveQuadTo(16f, 13f)
            quadToRelative(0f, -1.65f, -1.17f, -2.83f)
            reflectiveQuadTo(12f, 9f)
            reflectiveQuadTo(9.18f, 10.17f)
            reflectiveQuadTo(8f, 13f)
            quadToRelative(0f, 0.9f, 0.36f, 1.66f)
            reflectiveQuadTo(9.35f, 16f)
            lineTo(7.93f, 17.43f)
            close()
            moveToRelative(2.66f, -3.01f)
            quadTo(10f, 13.83f, 10f, 13f)
            reflectiveQuadToRelative(0.59f, -1.41f)
            reflectiveQuadTo(12f, 11f)
            reflectiveQuadToRelative(1.41f, 0.59f)
            quadTo(14f, 12.18f, 14f, 13f)
            reflectiveQuadToRelative(-0.59f, 1.41f)
            reflectiveQuadTo(12f, 15f)
            reflectiveQuadTo(10.59f, 14.41f)
            close()
          }
        }
        .build()
    return _wifi_tethering!!
  }

private var _wifi_tethering: ImageVector? = null
