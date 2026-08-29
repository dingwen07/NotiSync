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
public val qr_code_scanner: ImageVector
  get() {
    if (_qr_code_scanner != null) {
      return _qr_code_scanner!!
    }
    _qr_code_scanner =
      ImageVector.Builder(
          name = "qr_code_scanner",
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
            moveTo(2f, 7f)
            verticalLineTo(2f)
            horizontalLineTo(7f)
            verticalLineTo(4f)
            horizontalLineTo(4f)
            verticalLineTo(7f)
            horizontalLineTo(2f)
            close()
            moveTo(2f, 22f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            verticalLineToRelative(3f)
            horizontalLineTo(7f)
            verticalLineToRelative(2f)
            horizontalLineTo(2f)
            close()
            moveToRelative(15f, 0f)
            verticalLineTo(20f)
            horizontalLineToRelative(3f)
            verticalLineTo(17f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(5f)
            horizontalLineTo(17f)
            close()
            moveTo(20f, 7f)
            verticalLineTo(4f)
            horizontalLineTo(17f)
            verticalLineTo(2f)
            horizontalLineToRelative(5f)
            verticalLineTo(7f)
            horizontalLineTo(20f)
            close()
            moveTo(17.5f, 17.5f)
            horizontalLineTo(19f)
            verticalLineTo(19f)
            horizontalLineTo(17.5f)
            verticalLineTo(17.5f)
            close()
            moveToRelative(0f, -3f)
            horizontalLineTo(19f)
            verticalLineTo(16f)
            horizontalLineTo(17.5f)
            verticalLineTo(14.5f)
            close()
            moveTo(16f, 16f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(1.5f)
            horizontalLineTo(16f)
            verticalLineTo(16f)
            close()
            moveToRelative(-1.5f, 1.5f)
            horizontalLineTo(16f)
            verticalLineTo(19f)
            horizontalLineTo(14.5f)
            verticalLineTo(17.5f)
            close()
            moveTo(13f, 16f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(1.5f)
            horizontalLineTo(13f)
            verticalLineTo(16f)
            close()
            moveToRelative(3f, -3f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(1.5f)
            horizontalLineTo(16f)
            verticalLineTo(13f)
            close()
            moveToRelative(-1.5f, 1.5f)
            horizontalLineTo(16f)
            verticalLineTo(16f)
            horizontalLineTo(14.5f)
            verticalLineTo(14.5f)
            close()
            moveTo(13f, 13f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(1.5f)
            horizontalLineTo(13f)
            verticalLineTo(13f)
            close()
            moveTo(19f, 5f)
            verticalLineToRelative(6f)
            horizontalLineTo(13f)
            verticalLineTo(5f)
            horizontalLineToRelative(6f)
            close()
            moveToRelative(-8f, 8f)
            verticalLineToRelative(6f)
            horizontalLineTo(5f)
            verticalLineTo(13f)
            horizontalLineToRelative(6f)
            close()
            moveTo(11f, 5f)
            verticalLineToRelative(6f)
            horizontalLineTo(5f)
            verticalLineTo(5f)
            horizontalLineToRelative(6f)
            close()
            moveTo(9.5f, 17.5f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(-3f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(3f)
            close()
            moveToRelative(0f, -8f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(-3f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(3f)
            close()
            moveToRelative(8f, 0f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(-3f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(3f)
            close()
          }
        }
        .build()
    return _qr_code_scanner!!
  }

private var _qr_code_scanner: ImageVector? = null
