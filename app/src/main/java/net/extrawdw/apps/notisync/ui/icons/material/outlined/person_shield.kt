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
public val person_shield: ImageVector
  get() {
    if (_person_shield != null) {
      return _person_shield!!
    }
    _person_shield =
      ImageVector.Builder(
          name = "person_shield",
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
            moveTo(12.13f, 18f)
            close()
            moveToRelative(0.65f, 2f)
            horizontalLineTo(4f)
            verticalLineTo(17.2f)
            quadTo(4f, 16.35f, 4.44f, 15.64f)
            quadTo(4.88f, 14.93f, 5.6f, 14.55f)
            quadTo(7.15f, 13.77f, 8.75f, 13.39f)
            reflectiveQuadTo(12f, 13f)
            verticalLineToRelative(2f)
            quadToRelative(-1.4f, 0f, -2.78f, 0.34f)
            reflectiveQuadTo(6.5f, 16.35f)
            quadTo(6.28f, 16.48f, 6.14f, 16.7f)
            quadTo(6f, 16.93f, 6f, 17.2f)
            verticalLineTo(18f)
            horizontalLineToRelative(6.13f)
            quadToRelative(0.1f, 0.52f, 0.26f, 1.02f)
            reflectiveQuadTo(12.78f, 20f)
            close()
            moveTo(18f, 22f)
            quadTo(16.18f, 21.55f, 15.09f, 20f)
            reflectiveQuadTo(14f, 16.55f)
            verticalLineTo(14f)
            lineToRelative(4f, -2f)
            lineToRelative(4f, 2f)
            verticalLineToRelative(2.55f)
            quadToRelative(0f, 1.9f, -1.09f, 3.45f)
            reflectiveQuadTo(18f, 22f)
            close()
            moveToRelative(0f, -2.1f)
            quadToRelative(0.95f, -0.45f, 1.48f, -1.38f)
            reflectiveQuadTo(20f, 16.55f)
            verticalLineToRelative(-1.3f)
            lineToRelative(-2f, -1f)
            lineToRelative(-2f, 1f)
            verticalLineToRelative(1.3f)
            quadToRelative(0f, 1.05f, 0.53f, 1.97f)
            reflectiveQuadTo(18f, 19.9f)
            close()
            moveTo(9.18f, 10.83f)
            quadTo(8f, 9.65f, 8f, 8f)
            reflectiveQuadTo(9.18f, 5.18f)
            reflectiveQuadTo(12f, 4f)
            reflectiveQuadToRelative(2.83f, 1.18f)
            reflectiveQuadTo(16f, 8f)
            reflectiveQuadToRelative(-1.17f, 2.82f)
            reflectiveQuadTo(12f, 12f)
            reflectiveQuadTo(9.18f, 10.83f)
            close()
            moveTo(13.41f, 9.41f)
            quadTo(14f, 8.82f, 14f, 8f)
            reflectiveQuadTo(13.41f, 6.59f)
            reflectiveQuadTo(12f, 6f)
            reflectiveQuadTo(10.59f, 6.59f)
            quadTo(10f, 7.18f, 10f, 8f)
            reflectiveQuadToRelative(0.59f, 1.41f)
            reflectiveQuadTo(12f, 10f)
            reflectiveQuadTo(13.41f, 9.41f)
            close()
            moveTo(12f, 8f)
            close()
            moveToRelative(6f, 9.07f)
            close()
          }
        }
        .build()
    return _person_shield!!
  }

private var _person_shield: ImageVector? = null
