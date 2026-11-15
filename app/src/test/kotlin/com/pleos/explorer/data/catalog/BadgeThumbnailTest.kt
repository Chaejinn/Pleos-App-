package com.pleos.explorer.data.catalog

import com.pleos.explorer.di.ExplorerApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=ExplorerApplication::class)
class BadgeThumbnailTest {
    @Before fun strictImages() { ShadowBitmapFactory.setAllowInvalidImageData(false) }
    @After fun restoreImages() { ShadowBitmapFactory.setAllowInvalidImageData(true) }
    @Test fun largeArtworkIsDecodedAsBoundedThumbnailAndStreamsClose() {
        val bytes = transparentPng(2048,1024)
        var opened=0; var closed=0
        val result = requireNotNull(BadgeThumbnail.decode {
            opened++
            object : ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } }
        })
        assertTrue(result.width in 1..192)
        assertTrue(result.height in 1..192)
        assertEquals(opened,closed)
        result.recycle()
    }
    @Test fun corruptArtworkReturnsNoBitmap() {
        assertNull(BadgeThumbnail.decode { ByteArrayInputStream(byteArrayOf(1,2,3)) })
    }
    private fun transparentPng(width: Int,height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type: String,bytes: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(bytes.size); data.write(name); data.write(bytes)
            val crc = CRC32().apply { update(name); update(bytes) }
            data.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply { writeInt(width);writeInt(height);write(byteArrayOf(8,6,0,0,0)) }
        chunk("IHDR",header.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { zip -> repeat(height) { zip.write(ByteArray(1+width*4)) } }
        chunk("IDAT",compressed.toByteArray())
        chunk("IEND",byteArrayOf())
        return output.toByteArray()
    }
}
