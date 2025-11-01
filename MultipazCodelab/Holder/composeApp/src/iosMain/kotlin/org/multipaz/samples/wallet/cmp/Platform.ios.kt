package org.multipaz.samples.wallet.cmp

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.toComposeImageBitmap
import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun loadImageBitmapFromBytes(bytes: ByteArray): ImageBitmap? {
    return try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Throwable) {
        null
    }
}