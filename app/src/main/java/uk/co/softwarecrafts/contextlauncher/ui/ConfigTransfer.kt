package uk.co.softwarecrafts.contextlauncher.ui

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.softwarecrafts.contextlauncher.Graph
import java.time.LocalDate

/**
 * Export and import of the JSON config through the system file picker
 * (Storage Access Framework), so no storage permission is needed.
 *
 * Construct it during the fragment's onCreate: the result launchers must be
 * registered before the fragment is started.
 */
class ConfigTransfer(
    private val fragment: Fragment,
    private val onMessage: (String) -> Unit,
) {
    private val create: ActivityResultLauncher<String> =
        fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeExport(uri)
        }

    private val open: ActivityResultLauncher<Array<String>> =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readImport(uri)
        }

    fun export() {
        create.launch("context-launcher-${LocalDate.now()}.json")
    }

    fun import() {
        open.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    fun resetToSeed() {
        fragment.lifecycleScope.launch {
            Graph.config(fragment.requireContext()).resetToSeed()
            onMessage("Config reset to defaults")
        }
    }

    private fun writeExport(uri: Uri) {
        val context = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = Graph.config(context).exportJson()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                        ?: error("could not open file")
                }
            }
            onMessage(result.fold({ "Config exported" }, { "Export failed: ${it.message}" }))
        }
    }

    private fun readImport(uri: Uri) {
        val context = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("could not open file")
                    Graph.config(context).importJson(text)
                }
            }
            onMessage(result.fold(
                { problems -> if (problems.isEmpty()) "Config imported" else "Import rejected: " + problems.first() },
                { "Import failed: ${it.message}" },
            ))
        }
    }
}
