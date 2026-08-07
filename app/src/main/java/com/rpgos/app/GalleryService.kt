package com.rpgos.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryService(private val context: Context) {

    fun saveGeneratedImage(result: GeneratedImageResult, kind: String, relatedEntityUid: String?): Uri {
        val resolver = context.contentResolver
        val safeTitle = result.title.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${stamp}_${safeTitle}.png"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, result.mimeType.ifBlank { "image/png" })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RPG OS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Nie udało się utworzyć wpisu galerii.")

        val bytes = Base64.decode(result.base64Data, Base64.DEFAULT)
        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "Nie udało się otworzyć pliku obrazu." }
            out.write(bytes)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
