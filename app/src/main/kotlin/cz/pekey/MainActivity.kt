package cz.pekey

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val reqDict = 1
    private val reqLib = 2

    private lateinit var status: TextView
    private val suggester by lazy { Suggester(this) }

    private val dictDir: File get() = File(filesDir, "dicts").apply { mkdirs() }
    private val libDir: File get() = File(filesDir, "lib").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        root.addView(Button(this).apply {
            text = "1. Povolit Pekey"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Vybrat Pekey"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        root.addView(EditText(this).apply { hint = "Zkušební pole" })

        root.addView(TextView(this).apply {
            text = "Doplňky (volitelné)"
            textSize = 18f
            setPadding(0, pad * 2, 0, pad / 2)
        })
        root.addView(Button(this).apply {
            text = "Přidat slovník (.dict)"
            setOnClickListener { pick(reqDict) }
        })
        root.addView(Button(this).apply {
            text = "Přidat knihovnu gest (.so)"
            setOnClickListener { pick(reqLib) }
        })
        root.addView(Button(this).apply {
            text = "Smazat všechny přidané soubory"
            setOnClickListener {
                deleteAll(dictDir)
                deleteAll(libDir)
                refresh()
            }
        })
        root.addView(Button(this).apply {
            text = "Otestovat knihovnu"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(GestureLib.load(this@MainActivity))
                    .setPositiveButton("OK", null)
                    .show()
            }
        })
        root.addView(Button(this).apply {
            text = "Otestovat návrhy"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(suggester.selfTest())
                    .setPositiveButton("OK", null)
                    .show()
            }
        })

        status = TextView(this).apply { setPadding(0, pad, 0, 0) }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onDestroy() {
        suggester.close()
        super.onDestroy()
    }

    private fun pick(req: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, req)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            reqDict -> import(uri, dictDir, ".dict")
            reqLib -> import(uri, libDir, ".so")
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun deleteAll(dir: File) {
        dir.listFiles()?.forEach {
            it.setWritable(true)
            it.delete()
        }
    }

    private fun import(uri: Uri, dir: File, extension: String) {
        val name = displayName(uri)?.substringAfterLast('/')
        if (name == null || !name.endsWith(extension)) {
            Toast.makeText(this, "Soubor musí mít příponu $extension", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            val message = try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (target.exists()) {
                    target.setWritable(true)
                    target.delete()
                }
                check(tmp.renameTo(target)) { "Soubor se nepodařilo uložit" }
                if (extension == ".so") target.setReadOnly()
                "Přidáno: $name"
            } catch (e: Exception) {
                tmp.delete()
                "Chyba: ${e.message}"
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun listing(dir: File): String {
        val files = dir.listFiles { f -> !f.name.endsWith(".tmp") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return "  (nic)"
        return files.joinToString("\n") { "  ${it.name}  (${it.length() / 1024} kB)" }
    }

    private fun refresh() {
        status.text = "Přidané slovníky:\n${listing(dictDir)}\n\nPřidaná knihovna gest:\n${listing(libDir)}"
    }
}