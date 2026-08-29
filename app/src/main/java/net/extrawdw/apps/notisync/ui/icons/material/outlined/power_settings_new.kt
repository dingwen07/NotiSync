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
public val power_settings_new: ImageVector
  get() {
    if (_power_settings_new != null) {
      return _power_settings_new!!
    }
    _power_settings_new =
      ImageVector.Builder(
          name = "power_settings_new",
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
            moveTo(12f, 22f)
            quadTo(9.93f, 22f, 8.1f, 21.21f)
            quadTo(6.28f, 20.43f, 4.93f, 19.08f)
            quadTo(3.58f, 17.73f, 2.79f, 15.9f)
            reflectiveQuadTo(2f, 12f)
            quadTo(2f, 9.9f, 2.79f, 8.09f)
            reflectiveQuadTo(4.93f, 4.93f)
            lineToRelative(1.4f, 1.4f)
            quadTo(5.23f, 7.43f, 4.61f, 8.88f)
            reflectiveQuadTo(4f, 12f)
            quadToRelative(0f, 3.35f, 2.33f, 5.68f)
            reflectiveQuadTo(12f, 20f)
            reflectiveQuadToRelative(5.68f, -2.32f)
            reflectiveQuadTo(20f, 12f)
            quadTo(20f, 10.33f, 19.39f, 8.88f)
            reflectiveQuadTo(17.68f, 6.32f)
            lineToRelative(1.4f, -1.4f)
            quadToRelative(1.35f, 1.35f, 2.14f, 3.16f)
            reflectiveQuadTo(22f, 12f)
            quadToRelative(0f, 2.07f, -0.79f, 3.9f)
            reflectiveQuadToRelative(-2.14f, 3.17f)
            quadToRelative(-1.35f, 1.35f, -3.17f, 2.14f)
            reflectiveQuadTo(12f, 22f)
            close()
            moveTo(11f, 13f)
            verticalLineTo(2f)
            horizontalLineToRelative(2f)
            verticalLineTo(13f)
            horizontalLineTo(11f)
            close()
          }
        }
        .build()
    return _power_settings_new!!
  }

private var _power_settings_new: ImageVector? = null
