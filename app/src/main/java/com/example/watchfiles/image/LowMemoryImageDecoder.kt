package com.example.watchfiles.image

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

data class DecodedImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

suspend fun decodeLowMemoryImage(
    path: Path,
    maxDimension: Int = 960,
): DecodedImage = withContext(Dispatchers.IO) {
    require(maxDimension > 0) { "预览尺寸无效" }

    var sourceWidth = 0
    var sourceHeight = 0
    var decodedBitmap: Bitmap? = null
    try {
        decodedBitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(path.toFile())) {
                decoder,
                info,
                _ ->
            sourceWidth = info.size.width
            sourceHeight = info.size.height
            require(sourceWidth > 0 && sourceHeight > 0) { "无法读取图片尺寸" }

            val longestEdge = maxOf(sourceWidth, sourceHeight)
            if (longestEdge > maxDimension) {
                val scale = maxDimension.toFloat() / longestEdge
                decoder.setTargetSize(
                    (sourceWidth * scale).roundToInt().coerceAtLeast(1),
                    (sourceHeight * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setOnPartialImageListener { false }
        }
        coroutineContext.ensureActive()
        DecodedImage(
            bitmap = decodedBitmap,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
        )
    } catch (error: Throwable) {
        decodedBitmap?.recycle()
        throw error
    }
}
