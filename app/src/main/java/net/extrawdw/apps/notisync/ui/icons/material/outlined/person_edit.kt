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
public val person_edit: ImageVector
  get() {
    if (_person_edit != null) {
      return _person_edit!!
    }
    _person_edit =
      ImageVector.Builder(
          name = "person_edit",
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
            moveTo(12f, 18f)
            close()
            moveTo(4f, 20f)
            verticalLineTo(17.2f)
            quadTo(4f, 16.35f, 4.44f, 15.64f)
            quadTo(4.88f, 14.93f, 5.6f, 14.55f)
            quadTo(7.15f, 13.77f, 8.75f, 13.39f)
            reflectiveQuadTo(12f, 13f)
            quadToRelative(0.93f, 0f, 1.83f, 0.11f)
            quadToRelative(0.9f, 0.11f, 1.8f, 0.36f)
            lineToRelative(-1.67f, 1.7f)
            quadTo(13.45f, 15.1f, 12.98f, 15.05f)
            reflectiveQuadTo(12f, 15f)
            quadToRelative(-1.4f, 0f, -2.78f, 0.34f)
            reflectiveQuadTo(6.5f, 16.35f)
            quadTo(6.28f, 16.48f, 6.14f, 16.7f)
            quadTo(6f, 16.93f, 6f, 17.2f)
            verticalLineTo(18f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(2f)
            horizontalLineTo(4f)
            close()
            moveToRelative(10f, 1f)
            verticalLineTo(17.93f)
            lineToRelative(5.53f, -5.5f)
            quadToRelative(0.22f, -0.22f, 0.5f, -0.32f)
            reflectiveQuadTo(20.58f, 12f)
            quadToRelative(0.3f, 0f, 0.57f, 0.11f)
            quadToRelative(0.27f, 0.11f, 0.5f, 0.34f)
            lineToRelative(0.93f, 0.93f)
            quadToRelative(0.2f, 0.22f, 0.31f, 0.5f)
            reflectiveQuadTo(23f, 14.43f)
            reflectiveQuadToRelative(-0.1f, 0.56f)
            quadToRelative(-0.1f, 0.29f, -0.32f, 0.51f)
            lineTo(17.08f, 21f)
            horizontalLineTo(14f)
            close()
            moveToRelative(7.5f, -6.58f)
            lineTo(20.58f, 13.5f)
            lineToRelative(0.92f, 0.92f)
            close()
            moveToRelative(-6f, 5.08f)
            horizontalLineToRelative(0.95f)
            lineToRelative(3.03f, -3.05f)
            lineTo(19.03f, 15.98f)
            lineTo(18.55f, 15.53f)
            lineTo(15.5f, 18.55f)
            verticalLineTo(19.5f)
            close()
            moveToRelative(3.53f, -3.53f)
            lineTo(18.55f, 15.53f)
            lineToRelative(0.93f, 0.92f)
            lineTo(19.03f, 15.98f)
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
          }
        }
        .build()
    return _person_edit!!
  }

private var _person_edit: ImageVector? = null
