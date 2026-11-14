package cz.pekey

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : Activity() {

    private lateinit var importer: ImportManager
    private lateinit var statusDicts: TextView
    private lateinit var statusLib: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importer = ImportManager(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        content.addView(label("Pekey – nastavení", 24f, true, 0))

        content.addView(label("Slovníky", 18f, true, 24))
        statusDicts = label("", 14f, false, 8)
        content.addView(statusDicts)
        content.addView(button("Importovat slovník (.dict)") { pickFile(REQ_DICT) })

        content.addView(label("Gestová knihovna", 18f, true, 32))
        statusLib = label("", 14f, false, 8)
        content.addView(statusLib)
        content.addView(button("Importovat knihovnu (.so)") { pickFile(REQ_LIB) })

        val root = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        refreshStatus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun params(topDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun label(text: String, sizeSp: Float, bold: Boolean, topDp: Int) =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = params(topDp)
        }

    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            layoutParams = params(8)
            setOnClickListener { onClick() }
        }

    private fun pickFile(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return

        val result = when (requestCode) {
            REQ_DICT -> importer.importDict(uri)
            REQ_LIB -> importer.importLib(uri)
            else -> return
        }

        result.onSuccess { name ->
            Toast.makeText(this, "Naimportováno: $name", Toast.LENGTH_SHORT).show()
        }.onFailure { e ->
            Toast.makeText(this, "Chyba: ${e.message}", Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val dicts = importer.listDicts()
        statusDicts.text =
            if (dicts.isEmpty()) "Žádný slovník není naimportovaný"
            else dicts.joinToString("\n") { "• ${it.name} (${it.length() / 1024} kB)" }

        statusLib.text =
            if (importer.hasLib()) "Naimportováno: ${importer.libFile.name}"
            else "Knihovna není naimportovaná"
    }

    companion object {
        private const val REQ_DICT = 1
        private const val REQ_LIB = 2
    }
}