package cz.pekey

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class ImportManager(private val context: Context) {

    private val dictDir: File
        get() = File(context.filesDir, "dicts").apply { mkdirs() }

    private val libDir: File
        get() = File(context.filesDir, "lib").apply { mkdirs() }

    val libFile: File
        get() = File(libDir, "libjni_latinimegoogle.so")

    fun listDicts(): List<File> =
        dictDir.listFiles { f -> f.isFile && f.name.endsWith(".dict") }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun hasLib(): Boolean = libFile.exists()

    fun importDict(uri: Uri): Result<String> = runCatching {
        val name = displayName(uri) ?: "dict_${System.currentTimeMillis()}.dict"
        require(name.endsWith(".dict")) { "Soubor musí mít příponu .dict" }
        val target = File(dictDir, sanitize(name))
        copy(uri, target)
        target.name
    }

    fun importLib(uri: Uri): Result<String> = runCatching {
        val name = displayName(uri) ?: "lib.so"
        require(name.endsWith(".so")) { "Soubor musí mít příponu .so" }
        val target = libFile
        if (target.exists()) {
            target.setWritable(true)
            target.delete()
        }
        copy(uri, target)
        // Novější Android odmítne načíst zapisovatelnou nativní knihovnu
        target.setReadOnly()
        target.name
    }

    fun deleteDict(file: File): Boolean = file.delete()

    fun deleteLib(): Boolean {
        val f = libFile
        f.setWritable(true)
        return f.delete()
    }

    private fun copy(uri: Uri, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Soubor se nepodařilo otevřít" }
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.exists()) {
            target.setWritable(true)
            target.delete()
        }
        check(tmp.renameTo(target)) { "Soubor se nepodařilo uložit" }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}