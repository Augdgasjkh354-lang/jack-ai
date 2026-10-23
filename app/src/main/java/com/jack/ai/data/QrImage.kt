package com.jack.ai.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** 从图片里读二维码（不用谷歌服务）。读不出返回 null。 */
object QrImage {
    /** 相册里的截图。 */
    fun decode(ctx: Context, uri: Uri): String? = runCatching {
        decodeWith { opts -> ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } }
    }.getOrNull()

    /** 网页上的图片（已经下载好的字节）。 */
    fun decode(bytes: ByteArray): String? = runCatching {
        decodeWith { opts -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
    }.getOrNull()

    /** 先量尺寸，太大的缩小到 1600 像素以内再读。 */
    private fun decodeWith(load: (BitmapFactory.Options) -> android.graphics.Bitmap?): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        load(bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = load(BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px))), hints).text
    }
}
