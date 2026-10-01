package uk.co.softwarecrafts.contextlauncher.ui.settings

import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.config.GroupKind
import uk.co.softwarecrafts.contextlauncher.core.config.StageTrigger
import java.time.format.DateTimeFormatter

/** Lists stages or app groups; tap to edit, "Add" to create. */
class ConfigListFragment : FormFragment() {

    private val section: String get() = arguments?.getString(ARG_SECTION) ?: SECTION_STAGES
    override val title: String get() = getString(if (section == SECTION_GROUPS) R.string.settings_groups else R.string.settings_stages)
    override val showSave: Boolean = false

    override fun load() = render()

    override fun onResume() {
        super.onResume()
        render() // back from an edit screen
    }

    private fun render() {
        val ctx = requireContext().applicationContext
        launch {
            val config = Graph.config(ctx).load()
            form {
                if (section == SECTION_GROUPS) {
                    note(getString(R.string.settings_groups_note))
                    config.appGroups.forEach { g ->
                        val kind = when (g.kind) { GroupKind.NORMAL -> ""; GroupKind.UNRESTRICTED -> getString(R.string.kind_unrestricted); GroupKind.OCCASIONAL -> getString(R.string.kind_occasional) }
                        row(g.name, listOf(resources.getQuantityString(R.plurals.n_apps, g.apps.size, g.apps.size), kind).filter { it.isNotEmpty() }.joinToString("  ·  ")) {
                            findNavController().navigate(R.id.groupEditFragment, bundleOf(GroupEditFragment.ARG_GROUP_ID to g.id))
                        }
                    }
                    action(getString(R.string.settings_add_group)) { findNavController().navigate(R.id.groupEditFragment, bundleOf(GroupEditFragment.ARG_GROUP_ID to "")) }
                } else {
                    note(getString(R.string.settings_stages_note))
                    config.stages.sortedByDescending { it.rank }.forEach { s ->
                        val detail = buildString {
                            append(triggerSummary(s.trigger))
                            append("  ·  ").append(getString(R.string.settings_rank_short, s.rank))
                            if (!s.enabled) append("  ·  ").append(getString(R.string.settings_disabled))
                        }
                        row(s.name, detail) { findNavController().navigate(R.id.stageEditFragment, bundleOf(StageEditFragment.ARG_STAGE_ID to s.id)) }
                    }
                    action(getString(R.string.settings_add_stage)) { findNavController().navigate(R.id.stageEditFragment, bundleOf(StageEditFragment.ARG_STAGE_ID to "")) }
                }
            }
        }
    }

    private fun triggerSummary(t: StageTrigger): String = when (t) {
        is StageTrigger.Calendar -> getString(R.string.trigger_calendar_short, t.eventTitle)
        is StageTrigger.FixedTime -> "${t.start.format(TIME)}–${t.end.format(TIME)}"
        is StageTrigger.TasksDone -> getString(R.string.trigger_tasks_done_short, t.label, t.until.format(TIME))
        is StageTrigger.TaskDue -> getString(R.string.trigger_task_due_short, t.label)
        is StageTrigger.Default -> getString(if (t.days == uk.co.softwarecrafts.contextlauncher.core.config.DayKind.WEEKDAY) R.string.trigger_default_weekday else R.string.trigger_default_weekend)
    }

    companion object {
        const val ARG_SECTION = "section"
        const val SECTION_STAGES = "stages"
        const val SECTION_GROUPS = "groups"
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
