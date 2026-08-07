package com.rpgos.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

class FilePickerBridge(private val context: Context) {
    fun copyUriToTemp(uri: Uri, name: String): File {
        val tempDir = File(context.cacheDir, "rpgos_imports").apply { mkdirs() }
        val out = File(tempDir, name)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Nie można otworzyć pliku." }
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
    }

    fun copyFileToUri(file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "Nie można zapisać pliku." }
            file.inputStream().use { input -> input.copyTo(output) }
        }
    }
}
