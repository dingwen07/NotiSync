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
public val healing: ImageVector
  get() {
    if (_healing != null) {
      return _healing!!
    }
    _healing =
      ImageVector.Builder(
          name = "healing",
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
            moveTo(12f, 17.65f)
            lineTo(8.25f, 21.4f)
            quadToRelative(-0.57f, 0.58f, -1.4f, 0.58f)
            reflectiveQuadTo(5.45f, 21.4f)
            lineTo(2.6f, 18.55f)
            quadTo(2.03f, 17.98f, 2.03f, 17.15f)
            quadToRelative(0f, -0.82f, 0.58f, -1.4f)
            lineTo(6.35f, 12f)
            lineTo(2.6f, 8.25f)
            quadTo(2.03f, 7.68f, 2.03f, 6.85f)
            reflectiveQuadTo(2.6f, 5.45f)
            lineTo(5.45f, 2.6f)
            quadTo(6.03f, 2.02f, 6.85f, 2.02f)
            reflectiveQuadTo(8.25f, 2.6f)
            lineTo(12f, 6.35f)
            lineTo(15.75f, 2.6f)
            quadToRelative(0.57f, -0.57f, 1.4f, -0.57f)
            reflectiveQuadToRelative(1.4f, 0.57f)
            lineTo(21.4f, 5.45f)
            quadToRelative(0.57f, 0.57f, 0.57f, 1.4f)
            quadToRelative(0f, 0.83f, -0.57f, 1.4f)
            lineTo(17.65f, 12f)
            lineToRelative(3.75f, 3.75f)
            quadToRelative(0.57f, 0.57f, 0.57f, 1.4f)
            quadToRelative(0f, 0.83f, -0.57f, 1.4f)
            lineTo(18.55f, 21.4f)
            quadToRelative(-0.57f, 0.58f, -1.4f, 0.58f)
            reflectiveQuadTo(15.75f, 21.4f)
            lineTo(12f, 17.65f)
            close()
            moveToRelative(0.71f, -6.94f)
            quadTo(13f, 10.43f, 13f, 10f)
            quadTo(13f, 9.57f, 12.71f, 9.29f)
            reflectiveQuadTo(12f, 9f)
            reflectiveQuadTo(11.29f, 9.29f)
            reflectiveQuadTo(11f, 10f)
            reflectiveQuadToRelative(0.29f, 0.71f)
            reflectiveQuadTo(12f, 11f)
            reflectiveQuadToRelative(0.71f, -0.29f)
            close()
            moveTo(7.75f, 10.6f)
            lineTo(10.6f, 7.75f)
            lineTo(6.85f, 4f)
            lineTo(4f, 6.85f)
            lineTo(7.75f, 10.6f)
            close()
            moveTo(10f, 13f)
            quadToRelative(0.43f, 0f, 0.71f, -0.29f)
            quadTo(11f, 12.43f, 11f, 12f)
            reflectiveQuadTo(10.71f, 11.29f)
            reflectiveQuadTo(10f, 11f)
            quadTo(9.58f, 11f, 9.29f, 11.29f)
            reflectiveQuadTo(9f, 12f)
            reflectiveQuadToRelative(0.29f, 0.71f)
            quadTo(9.58f, 13f, 10f, 13f)
            close()
            moveToRelative(2.71f, 1.71f)
            quadTo(13f, 14.43f, 13f, 14f)
            reflectiveQuadTo(12.71f, 13.29f)
            reflectiveQuadTo(12f, 13f)
            reflectiveQuadToRelative(-0.71f, 0.29f)
            reflectiveQuadTo(11f, 14f)
            reflectiveQuadToRelative(0.29f, 0.71f)
            reflectiveQuadTo(12f, 15f)
            reflectiveQuadToRelative(0.71f, -0.29f)
            close()
            moveTo(14f, 13f)
            quadToRelative(0.43f, 0f, 0.71f, -0.29f)
            quadTo(15f, 12.43f, 15f, 12f)
            reflectiveQuadTo(14.71f, 11.29f)
            reflectiveQuadTo(14f, 11f)
            reflectiveQuadToRelative(-0.71f, 0.29f)
            reflectiveQuadTo(13f, 12f)
            reflectiveQuadToRelative(0.29f, 0.71f)
            reflectiveQuadTo(14f, 13f)
            close()
            moveToRelative(-0.6f, 3.25f)
            lineTo(17.15f, 20f)
            lineTo(20f, 17.15f)
            lineTo(16.25f, 13.4f)
            lineTo(13.4f, 16.25f)
            close()
            moveTo(8.48f, 8.48f)
            close()
            moveToRelative(7.05f, 7.05f)
            close()
          }
        }
        .build()
    return _healing!!
  }

private var _healing: ImageVector? = null
