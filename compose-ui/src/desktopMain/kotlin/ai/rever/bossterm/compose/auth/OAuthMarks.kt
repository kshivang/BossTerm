package ai.rever.bossterm.compose.auth

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Provider marks for the sign-in buttons, built from their SVG paths so no image asset ships.
 * Google's "G" keeps its brand colours (tint it with Color.Unspecified); Apple's is one path the
 * caller tints to the button's text colour.
 */
internal object OAuthMarks {
    val google: ImageVector by lazy {
        ImageVector.Builder("GoogleG", 18.dp, 18.dp, 18f, 18f).apply {
            path("M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.87 2.68-6.62z", 0xFF4285F4)
            path("M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.83.86-3.04.86-2.34 0-4.33-1.58-5.04-3.71H.96v2.33A9 9 0 0 0 9 18z", 0xFF34A853)
            path("M3.96 10.71A5.41 5.41 0 0 1 3.68 9c0-.59.1-1.17.28-1.71V4.96H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.04l3-2.33z", 0xFFFBBC05)
            path("M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.59A9 9 0 0 0 .96 4.96l3 2.33C4.67 5.16 6.66 3.58 9 3.58z", 0xFFEA4335)
        }.build()
    }

    val apple: ImageVector by lazy {
        ImageVector.Builder("Apple", 18.dp, 18.dp, 24f, 24f).apply {
            path(
                "M16.37 1.43c0 1.14-.49 2.27-1.18 3.08-.74.9-1.99 1.57-2.99 1.57-.12 0-.23-.02-.3-.03-.01-.06-.04-.22-.04-.39 0-1.15.57-2.27 1.21-2.98.8-.94 2.14-1.64 3.25-1.68.03.13.05.28.05.43zm4.34 15.59c-.03.07-.46 1.58-1.52 3.12-.95 1.34-1.94 2.71-3.43 2.71-1.52 0-1.9-.88-3.63-.88-1.7 0-2.3.91-3.67.91-1.38 0-2.33-1.26-3.43-2.8C3.74 18.26 2.7 15.45 2.7 12.8c0-4.28 2.8-6.55 5.55-6.55 1.45 0 2.68.95 3.6.95.87 0 2.22-1.01 3.9-1.01.61 0 2.89.06 4.37 2.19-.13.09-2.38 1.37-2.38 4.19 0 3.26 2.85 4.32 2.95 4.38z",
                0xFF000000,
            )
        }.build()
    }

    private fun ImageVector.Builder.path(d: String, argb: Long) {
        addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color(argb)))
    }
}
