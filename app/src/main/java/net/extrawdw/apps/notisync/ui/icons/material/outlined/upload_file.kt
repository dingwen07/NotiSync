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
public val upload_file: ImageVector
  get() {
    if (_upload_file != null) {
      return _upload_file!!
    }
    _upload_file =
      ImageVector.Builder(
          name = "upload_file",
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
            moveTo(11f, 19f)
            horizontalLineToRelative(2f)
            verticalLineTo(14.83f)
            lineToRelative(1.6f, 1.6f)
            lineTo(16f, 15f)
            lineTo(12f, 11f)
            lineTo(8f, 15f)
            lineToRelative(1.43f, 1.4f)
            lineTo(11f, 14.83f)
            verticalLineTo(19f)
            close()
            moveTo(6f, 22f)
            quadTo(5.18f, 22f, 4.59f, 21.41f)
            reflectiveQuadTo(4f, 20f)
            verticalLineTo(4f)
            quadTo(4f, 3.17f, 4.59f, 2.59f)
            reflectiveQuadTo(6f, 2f)
            horizontalLineToRelative(8f)
            lineToRelative(6f, 6f)
            verticalLineTo(20f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(18f, 22f)
            horizontalLineTo(6f)
            close()
            moveTo(13f, 9f)
            verticalLineTo(4f)
            horizontalLineTo(6f)
            verticalLineTo(20f)
            horizontalLineTo(18f)
            verticalLineTo(9f)
            horizontalLineTo(13f)
            close()
            moveTo(6f, 4f)
            verticalLineTo(9f)
            verticalLineTo(4f)
            verticalLineTo(9f)
            verticalLineTo(20f)
            verticalLineTo(4f)
            close()
          }
        }
        .build()
    return _upload_file!!
  }

private var _upload_file: ImageVector? = null
