package uk.co.softwarecrafts.contextlauncher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.databinding.FragmentReviewBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.review.Suggestion
import uk.co.softwarecrafts.contextlauncher.review.ReviewController
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The weekly review: last seven days of bypasses, time's-ups and app time,
 * rule-based suggestions with one-tap Apply, a JSON export, and Finish, which
 * completes the @phone/review task when the review stage is active.
 */
class ReviewFragment : Fragment() {

    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!
    private lateinit var controller: ReviewController
    private var loaded: ReviewController.Loaded? = null
    private val applied = mutableSetOf<String>()

    private val createExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeExport(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller = ReviewController(requireContext())
        binding.export.setOnClickListener { createExport.launch("context-launcher-usage-${LocalDate.now()}.json") }
        binding.finish.setOnClickListener { finish() }
        binding.close.setOnClickListener { findNavController().popBackStack() }
        binding.usageAccess.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .onFailure { requireContext().showToast(getString(R.string.review_usage_settings_missing)) }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (loaded != null) load() // back from the usage-access settings screen
    }

    private fun load() {
        val zone = Graph.stageEngine(requireContext()).zone
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { controller.load() }
            val b = _binding ?: return@launch
            result.onFailure { b.summary.text = getString(R.string.review_load_failed, it.message ?: it.javaClass.simpleName); return@launch }
            val data = result.getOrThrow()
            loaded = data
            render(data, zone)
        }
    }

    private fun render(data: ReviewController.Loaded, zone: ZoneId) {
        val r = data.report
        val from = LocalDate.ofInstant(r.periodStart, zone).format(DAY)
        val to = LocalDate.ofInstant(r.periodEnd, zone).format(DAY)
        binding.period.text = getString(R.string.review_period, from, to)
        binding.finish.text = getString(if (controller.dueReviewTaskId() != null) R.string.review_finish_complete else R.string.review_finish)

        binding.summary.text = buildString {
            append(getString(R.string.review_summary_bypasses, r.totalBypasses, r.totalTimesUp)).append("\n")
            append(getString(R.string.review_summary_tasks, r.tasksCompleted)).append("\n")
            append(getString(R.string.review_summary_stages, r.stageChanges))
        }

        // Suggestions
        binding.suggestions.removeAllViews()
        binding.suggestionsEmpty.isVisible = data.suggestions.isEmpty()
        data.suggestions.forEach { s -> binding.suggestions.addView(suggestionRow(s)) }

        // Bypasses by app and stage
        binding.bypasses.text = if (r.bypasses.isEmpty()) getString(R.string.review_none) else r.bypasses.take(8).joinToString("\n") { b ->
            val stage = data.config.stage(b.stageId)?.name ?: b.stageId
            val avg = if (b.limitsChosen.isEmpty()) "" else "  ·  " + getString(R.string.review_avg_limit, b.averageLimit.toInt())
            val reason = b.reasons.lastOrNull()?.let { "\n    “$it”" } ?: ""
            "${controller.label(b.packageName)}  ·  $stage  ·  ${b.count}×$avg" + (if (b.timesUp > 0) "  ·  " + getString(R.string.review_times_up, b.timesUp) else "") + reason
        }

        // App time
        val hasAccess = controller.hasUsageAccess()
        binding.usageAccess.isVisible = !hasAccess
        binding.usage.text = when {
            !hasAccess -> getString(R.string.review_usage_no_access)
            r.usage.isEmpty() -> getString(R.string.review_none)
            else -> r.topApps(8).joinToString("\n") { u ->
                val top = u.byStage.entries.sortedByDescending { it.value }.take(2)
                    .joinToString(", ") { (stageId, d) -> "${data.config.stage(stageId)?.name ?: stageId} ${d.toMinutes()}m" }
                "${controller.label(u.packageName)}  ·  ${minutes(u.total.toMinutes())}  ·  $top"
            }
        }
    }

    private fun suggestionRow(s: Suggestion): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
        addView(TextView(context, null, 0, R.style.TextSmall).apply { text = s.text })
        addView(TextView(context, null, 0, R.style.SetupButton).apply {
            val done = s.id in applied
            text = getString(if (done) R.string.review_applied else R.string.review_apply)
            alpha = if (done) 0.45f else 1f
            isEnabled = !done
            setOnClickListener { apply(s, this) }
        })
    }

    private fun apply(s: Suggestion, button: TextView) {
        button.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val problems = runCatching { controller.apply(s) }.getOrElse { listOf(it.message ?: "error") }
            val ctx = context ?: return@launch
            if (problems.isEmpty()) {
                applied += s.id
                button.text = getString(R.string.review_applied)
                button.alpha = 0.45f
                ctx.showToast(getString(R.string.review_change_applied))
            } else {
                button.isEnabled = true
                ctx.showToast(getString(R.string.review_change_rejected, problems.first()))
            }
        }
    }

    private fun writeExport(uri: Uri) {
        val data = loaded ?: return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = controller.exportJson(data)
                    appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } ?: error("could not open file")
                }
            }
            appContext.showToast(result.fold({ getString(R.string.review_exported) }, { getString(R.string.review_export_failed, it.message) }))
        }
    }

    private fun finish() {
        binding.finish.isEnabled = false
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = controller.finish(applied.size)
            appContext.showToast(result.fold(
                { completed -> getString(if (completed) R.string.review_done_task_completed else R.string.review_done) },
                { getString(R.string.review_task_failed, it.message ?: "network") },
            ))
            if (result.isSuccess) findNavController().popBackStack() else _binding?.finish?.isEnabled = true
        }
    }

    private fun minutes(total: Long): String = if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    }
}
