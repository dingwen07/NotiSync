package net.extrawdw.apps.notisync.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined `signature`, kept local so the tool icon does not depend on a font. */
internal val SignatureIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "MaterialSymbolsOutlined.Signature",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).apply {
        addGroup(translationY = 960f)
        addPath(
            pathData = PathParser().parsePathString(SIGNATURE_PATH).toNodes(),
            fill = SolidColor(Color.Black),
        )
        clearGroup()
    }.build()
}

private const val SIGNATURE_PATH =
    "M563-491q73-54 114-118.5T718-738q0-32-10.5-47T679-800q-47 0-83 79.5T560-541q0 14 .5 26.5T563-491Z" +
        "M120-120v-80h80v80h-80Zm160 0v-80h80v80h-80Zm160 0v-80h80v80h-80Zm160 0v-80h80v80h-80Z" +
        "M760-120v-80h80v80h-80ZM136-280l-56-56 64-64-64-64 56-56 64 64 64-64 56 56-64 64 64 64-56 56-64-64-64 64Z" +
        " M618-320q-30 0-55-11.5T520-369q-25 14-51.5 25T414-322l-28-75q28-10 53.5-21.5T489-443q-5-22-7.5-48t-2.5-56" +
        "q0-144 57-238.5T679-880q52 0 85 38.5T797-734q0 86-54.5 170T591-413q7 7 14.5 10.5T621-399q26 0 60.5-33t62.5-87" +
        "l73 34q-7 17-11 41t1 42q10-5 23.5-17t27.5-30l63 49q-26 36-60 58t-63 22q-21 0-37.5-12.5T733-371q-28 25-57 38t-58 13Z"
