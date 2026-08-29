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
public val phone_iphone: ImageVector
  get() {
    if (_phone_iphone != null) {
      return _phone_iphone!!
    }
    _phone_iphone =
      ImageVector.Builder(
          name = "phone_iphone",
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
            moveTo(7f, 23f)
            quadTo(6.18f, 23f, 5.59f, 22.41f)
            reflectiveQuadTo(5f, 21f)
            verticalLineTo(3f)
            quadTo(5f, 2.17f, 5.59f, 1.59f)
            reflectiveQuadTo(7f, 1f)
            horizontalLineTo(17f)
            quadToRelative(0.82f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(19f, 3f)
            verticalLineTo(6.1f)
            quadToRelative(0.45f, 0.18f, 0.73f, 0.55f)
            reflectiveQuadTo(20f, 7.5f)
            verticalLineToRelative(2f)
            quadToRelative(0f, 0.47f, -0.27f, 0.85f)
            reflectiveQuadTo(19f, 10.9f)
            verticalLineTo(21f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(17f, 23f)
            horizontalLineTo(7f)
            close()
            moveTo(7f, 21f)
            horizontalLineTo(17f)
            verticalLineTo(3f)
            horizontalLineTo(7f)
            verticalLineTo(21f)
            close()
            moveToRelative(0f, 0f)
            verticalLineTo(3f)
            verticalLineTo(21f)
            close()
            moveToRelative(3f, -1f)
            horizontalLineToRelative(4f)
            quadToRelative(0.43f, 0f, 0.71f, -0.29f)
            quadTo(15f, 19.43f, 15f, 19f)
            reflectiveQuadTo(14.71f, 18.29f)
            reflectiveQuadTo(14f, 18f)
            horizontalLineTo(10f)
            quadTo(9.58f, 18f, 9.29f, 18.29f)
            reflectiveQuadTo(9f, 19f)
            reflectiveQuadToRelative(0.29f, 0.71f)
            quadTo(9.58f, 20f, 10f, 20f)
            close()
          }
        }
        .build()
    return _phone_iphone!!
  }

private var _phone_iphone: ImageVector? = null
