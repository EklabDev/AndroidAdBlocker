package com.eklab.adblocker.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

private const val ICON_PX = 96

/**
 * Renders an app [Drawable] icon as a Compose [Image]. The Drawable is rasterized
 * once into a small bitmap and remembered per drawable instance.
 */
@Composable
fun AppIconImage(
    drawable: Drawable?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val image = remember(drawable) { drawable?.toImageBitmap() }
    if (image != null) {
        Image(bitmap = image, contentDescription = contentDescription, modifier = modifier)
    } else {
        Icon(Icons.Filled.Android, contentDescription = contentDescription, modifier = modifier)
    }
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, ICON_PX, ICON_PX)
    draw(canvas)
    return bitmap.asImageBitmap()
}
