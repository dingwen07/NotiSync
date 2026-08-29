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
public val contactless: ImageVector
  get() {
    if (_contactless != null) {
      return _contactless!!
    }
    _contactless =
      ImageVector.Builder(
          name = "contactless",
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
            moveTo(8.4f, 14.65f)
            quadToRelative(0.22f, -0.6f, 0.36f, -1.26f)
            reflectiveQuadTo(8.9f, 12f)
            quadToRelative(0f, -0.73f, -0.14f, -1.39f)
            reflectiveQuadTo(8.4f, 9.35f)
            lineTo(6.55f, 10.1f)
            quadTo(6.7f, 10.55f, 6.8f, 11.02f)
            reflectiveQuadTo(6.9f, 12f)
            reflectiveQuadTo(6.8f, 12.98f)
            reflectiveQuadTo(6.55f, 13.9f)
            lineTo(8.4f, 14.65f)
            close()
            moveTo(11.6f, 16f)
            quadToRelative(0.42f, -0.95f, 0.61f, -1.95f)
            reflectiveQuadTo(12.4f, 12f)
            reflectiveQuadTo(12.21f, 9.95f)
            reflectiveQuadTo(11.6f, 8f)
            lineTo(9.75f, 8.75f)
            quadToRelative(0.35f, 0.75f, 0.5f, 1.56f)
            reflectiveQuadTo(10.4f, 12f)
            reflectiveQuadToRelative(-0.15f, 1.69f)
            reflectiveQuadToRelative(-0.5f, 1.56f)
            lineTo(11.6f, 16f)
            close()
            moveToRelative(3.25f, 1.35f)
            quadToRelative(0.52f, -1.25f, 0.79f, -2.59f)
            quadTo(15.9f, 13.43f, 15.9f, 12f)
            reflectiveQuadTo(15.64f, 9.24f)
            reflectiveQuadTo(14.85f, 6.65f)
            lineTo(13f, 7.45f)
            quadToRelative(0.45f, 1.05f, 0.68f, 2.2f)
            reflectiveQuadTo(13.9f, 12f)
            reflectiveQuadToRelative(-0.22f, 2.35f)
            reflectiveQuadTo(13f, 16.55f)
            lineToRelative(1.85f, 0.8f)
            close()
            moveTo(12f, 22f)
            quadTo(9.93f, 22f, 8.1f, 21.21f)
            quadTo(6.28f, 20.43f, 4.93f, 19.08f)
            quadTo(3.58f, 17.73f, 2.79f, 15.9f)
            reflectiveQuadTo(2f, 12f)
            quadTo(2f, 9.92f, 2.79f, 8.1f)
            quadTo(3.58f, 6.27f, 4.93f, 4.93f)
            quadTo(6.28f, 3.57f, 8.1f, 2.79f)
            quadTo(9.93f, 2f, 12f, 2f)
            reflectiveQuadToRelative(3.9f, 0.79f)
            reflectiveQuadToRelative(3.17f, 2.14f)
            quadToRelative(1.35f, 1.35f, 2.14f, 3.17f)
            quadTo(22f, 9.92f, 22f, 12f)
            reflectiveQuadToRelative(-0.79f, 3.9f)
            reflectiveQuadToRelative(-2.14f, 3.17f)
            quadToRelative(-1.35f, 1.35f, -3.17f, 2.14f)
            reflectiveQuadTo(12f, 22f)
            close()
            moveToRelative(0f, -2f)
            quadToRelative(3.35f, 0f, 5.68f, -2.32f)
            reflectiveQuadTo(20f, 12f)
            reflectiveQuadTo(17.68f, 6.32f)
            reflectiveQuadTo(12f, 4f)
            reflectiveQuadTo(6.33f, 6.32f)
            reflectiveQuadTo(4f, 12f)
            reflectiveQuadToRelative(2.33f, 5.68f)
            reflectiveQuadTo(12f, 20f)
            close()
            moveToRelative(0f, -8f)
            close()
          }
        }
        .build()
    return _contactless!!
  }

private var _contactless: ImageVector? = null
