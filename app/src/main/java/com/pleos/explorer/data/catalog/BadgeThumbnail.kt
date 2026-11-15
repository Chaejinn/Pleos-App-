package com.pleos.explorer.data.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

internal object BadgeThumbnail {
    fun decode(open: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
        open().use { BitmapFactory.decodeStream(it,null,bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize=1 }
        while (bounds.outWidth/options.inSampleSize > 192 || bounds.outHeight/options.inSampleSize > 192)
            options.inSampleSize *= 2
        return open().use { BitmapFactory.decodeStream(it,null,options) }
    }
}
