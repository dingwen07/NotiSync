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
public val account_tree: ImageVector
  get() {
    if (_account_tree != null) {
      return _account_tree!!
    }
    _account_tree =
      ImageVector.Builder(
          name = "account_tree",
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
            moveTo(15f, 21f)
            verticalLineTo(18f)
            horizontalLineTo(11f)
            verticalLineTo(8f)
            horizontalLineTo(9f)
            verticalLineToRelative(3f)
            horizontalLineTo(2f)
            verticalLineTo(3f)
            horizontalLineTo(9f)
            verticalLineTo(6f)
            horizontalLineToRelative(6f)
            verticalLineTo(3f)
            horizontalLineToRelative(7f)
            verticalLineToRelative(8f)
            horizontalLineTo(15f)
            verticalLineTo(8f)
            horizontalLineTo(13f)
            verticalLineToRelative(8f)
            horizontalLineToRelative(2f)
            verticalLineTo(13f)
            horizontalLineToRelative(7f)
            verticalLineToRelative(8f)
            horizontalLineTo(15f)
            close()
            moveTo(4f, 5f)
            verticalLineTo(9f)
            verticalLineTo(5f)
            close()
            moveTo(17f, 15f)
            verticalLineToRelative(4f)
            verticalLineTo(15f)
            close()
            moveTo(17f, 5f)
            verticalLineTo(9f)
            verticalLineTo(5f)
            close()
            moveToRelative(0f, 4f)
            horizontalLineToRelative(3f)
            verticalLineTo(5f)
            horizontalLineTo(17f)
            verticalLineTo(9f)
            close()
            moveToRelative(0f, 10f)
            horizontalLineToRelative(3f)
            verticalLineTo(15f)
            horizontalLineTo(17f)
            verticalLineToRelative(4f)
            close()
            moveTo(4f, 9f)
            horizontalLineTo(7f)
            verticalLineTo(5f)
            horizontalLineTo(4f)
            verticalLineTo(9f)
            close()
          }
        }
        .build()
    return _account_tree!!
  }

private var _account_tree: ImageVector? = null
