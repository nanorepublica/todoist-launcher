package uk.co.softwarecrafts.contextlauncher.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.databinding.FragmentFormBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.core.log.EventType
import uk.co.softwarecrafts.contextlauncher.core.log.LogEvent

/**
 * Base for the settings screens: a title, a column of tappable rows that open
 * dialogs, and Save / Delete / Cancel. Subclasses hold a draft, render it
 * into rows, and write it back through ConfigRepository (validated).
 */
abstract class FormFragment : Fragment() {

    private var _binding: FragmentFormBinding? = null
    protected val binding get() = _binding!!
    protected lateinit var viewModel: MainViewModel

    abstract val title: String
    open val showSave: Boolean = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        if (viewModel.appList.value == null) viewModel.getAppList()
        binding.title.text = title
        binding.save.isVisible = showSave
        binding.delete.isVisible = false
        binding.save.setOnClickListener { save() }
        binding.delete.setOnClickListener { delete() }
        binding.cancel.setOnClickListener { findNavController().popBackStack() }
        binding.cancel.text = getString(if (showSave) R.string.cancel else R.string.close)
        load()
    }

    /** Loads the config and renders. Called once the view exists. */
    abstract fun load()
    open fun save() = Unit
    open fun delete() = Unit

    /** Rebuilds the rows from scratch; cheap for a dozen rows and keeps state in one place (the draft). */
    protected fun form(fill: LinearLayout.() -> Unit) {
        val fields = _binding?.fields ?: return
        fields.removeAllViews()
        fields.fill()
    }

    protected fun LinearLayout.header(text: CharSequence) {
        addView(TextView(context, null, 0, R.style.TextSmallBold).apply {
            this.text = text
            setPadding(3.dpToPx(), 20.dpToPx(), 3.dpToPx(), 4.dpToPx())
        })
    }

    protected fun LinearLayout.note(text: CharSequence) {
        addView(TextView(context, null, 0, R.style.TextSmallLight).apply {
            this.text = text
            setPadding(3.dpToPx(), 2.dpToPx(), 3.dpToPx(), 6.dpToPx())
        })
    }

    /** "Label · value" row. Tap edits, long-press (when given) removes. */
    protected fun LinearLayout.row(label: CharSequence, value: CharSequence? = null, onLong: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
        addView(TextView(context, null, 0, R.style.TextSmall).apply {
            text = if (value.isNullOrEmpty()) label else "$label  ·  $value"
            setPadding(3.dpToPx(), 10.dpToPx(), 3.dpToPx(), 10.dpToPx())
            if (onClick != null) setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        })
    }

    protected fun LinearLayout.action(label: CharSequence, onClick: () -> Unit) {
        addView(TextView(context, null, 0, R.style.SetupButton).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    // ---- shared data ----

    protected fun installedApps(): List<AppModel.App> =
        viewModel.appList.value.orEmpty().filterIsInstance<AppModel.App>().distinctBy { it.appPackage }.sortedBy { it.appLabel.lowercase() }

    protected fun appLabel(packageName: String): String =
        installedApps().firstOrNull { it.appPackage == packageName }?.appLabel ?: packageName

    protected fun appOptions(): List<Option> = installedApps().map { Option(it.appPackage, it.appLabel) }

    /** Validates and saves through the repository; shows the first problem and returns false when rejected. */
    protected suspend fun commit(what: String, change: (LauncherConfig) -> LauncherConfig): Boolean {
        val ctx = requireContext().applicationContext
        val problems = runCatching { Graph.config(ctx).update(change) }.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
        if (problems.isNotEmpty()) {
            ctx.showToast(getString(R.string.settings_not_saved, problems.first()))
            return false
        }
        Graph.eventLog(ctx).record(LogEvent(at = Graph.clock.now(), type = EventType.CONFIG_CHANGE, detail = what))
        Graph.stageEngine(ctx).refresh()
        return true
    }

    protected fun launch(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch { block() }
    }

    protected fun slug(name: String, taken: Collection<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "item" }
        var id = base
        var n = 2
        while (id in taken) id = "${base}_${n++}"
        return id
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
