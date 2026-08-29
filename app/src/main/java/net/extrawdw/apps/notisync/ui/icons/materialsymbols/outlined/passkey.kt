package net.extrawdw.apps.notisync.ui.icons.materialsymbols.outlined

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
public val passkey: ImageVector
  get() {
    if (_passkey != null) {
      return _passkey!!
    }
    _passkey =
      ImageVector.Builder(
        name = "passkey",
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
            moveTo(3f, 20f)
            verticalLineTo(17.2f)
            quadTo(3f, 16.35f, 3.44f, 15.64f)
            quadTo(3.88f, 14.93f, 4.6f, 14.55f)
            quadTo(6.15f, 13.77f, 7.75f, 13.39f)
            reflectiveQuadTo(11f, 13f)
            quadToRelative(0.5f, 0f, 1f, 0.04f)
            reflectiveQuadToRelative(1f, 0.11f)
            quadToRelative(-0.1f, 1.45f, 0.53f, 2.74f)
            quadToRelative(0.63f, 1.29f, 1.83f, 2.11f)
            verticalLineToRelative(2f)
            horizontalLineTo(3f)
            close()
            moveToRelative(16f, 3f)
            lineTo(17.5f, 21.5f)
            verticalLineTo(16.85f)
            quadTo(16.4f, 16.52f, 15.7f, 15.61f)
            reflectiveQuadTo(15f, 13.5f)
            quadToRelative(0f, -1.45f, 1.03f, -2.48f)
            reflectiveQuadTo(18.5f, 10f)
            reflectiveQuadToRelative(2.48f, 1.02f)
            reflectiveQuadTo(22f, 13.5f)
            quadToRelative(0f, 1.13f, -0.64f, 2f)
            reflectiveQuadToRelative(-1.61f, 1.25f)
            lineTo(21f, 18f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(21f, 21f)
            lineToRelative(-2f, 2f)
            close()
            moveTo(11f, 12f)
            quadTo(9.35f, 12f, 8.18f, 10.83f)
            reflectiveQuadTo(7f, 8f)
            reflectiveQuadTo(8.18f, 5.18f)
            reflectiveQuadTo(11f, 4f)
            reflectiveQuadToRelative(2.83f, 1.18f)
            reflectiveQuadTo(15f, 8f)
            reflectiveQuadToRelative(-1.17f, 2.82f)
            reflectiveQuadTo(11f, 12f)
            close()
            moveToRelative(8.21f, 1.71f)
            quadTo(19.5f, 13.43f, 19.5f, 13f)
            reflectiveQuadTo(19.21f, 12.29f)
            reflectiveQuadTo(18.5f, 12f)
            reflectiveQuadToRelative(-0.71f, 0.29f)
            reflectiveQuadTo(17.5f, 13f)
            reflectiveQuadToRelative(0.29f, 0.71f)
            reflectiveQuadTo(18.5f, 14f)
            reflectiveQuadToRelative(0.71f, -0.29f)
            close()
          }
        }
        .build()
    return _passkey!!
  }

private var _passkey: ImageVector? = null
