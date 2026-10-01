package uk.co.softwarecrafts.contextlauncher.ui.settings

import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.showToast
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.config.AllowedApp
import uk.co.softwarecrafts.contextlauncher.core.config.DayKind
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.core.config.Perk
import uk.co.softwarecrafts.contextlauncher.core.config.Stage
import uk.co.softwarecrafts.contextlauncher.core.config.StageTrigger
import uk.co.softwarecrafts.contextlauncher.data.todoist.TodoistRepository
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Create or edit one stage. Everything in [Stage] is a row here. */
class StageEditFragment : FormFragment() {

    private val stageId: String get() = arguments?.getString(ARG_STAGE_ID).orEmpty()
    private val isNew get() = stageId.isEmpty()
    override val title: String get() = getString(if (isNew) R.string.settings_new_stage else R.string.settings_edit_stage)

    private var config: LauncherConfig? = null
    private var draft: Stage = Stage(id = "", name = "", trigger = StageTrigger.Calendar(""), rank = 50)

    override fun load() {
        val ctx = requireContext().applicationContext
        launch {
            val cfg = Graph.config(ctx).load()
            config = cfg
            cfg.stage(stageId)?.let { draft = it }
            binding.delete.isVisible = !isNew
            render()
        }
    }

    private fun update(change: (Stage) -> Stage) {
        draft = change(draft)
        render()
    }

    private fun render() {
        val ctx = requireContext()
        val cfg = config ?: return
        val d = draft
        form {
            row(getString(R.string.settings_name), d.name.ifEmpty { getString(R.string.settings_unset) }) {
                FormDialogs.text(ctx, getString(R.string.settings_name), d.name) { v -> update { it.copy(name = v) } }
            }
            row(getString(R.string.settings_enabled), getString(if (d.enabled) R.string.enabled else R.string.disabled)) {
                update { it.copy(enabled = !it.enabled) }
            }
            row(getString(R.string.settings_rank), d.rank.toString()) {
                FormDialogs.number(ctx, getString(R.string.settings_rank), d.rank, getString(R.string.settings_rank_hint)) { n -> if (n != null) update { it.copy(rank = n) } }
            }
            note(getString(R.string.settings_rank_note))

            header(getString(R.string.settings_trigger))
            row(getString(R.string.settings_trigger_type), triggerName(d.trigger)) { pickTriggerType() }
            when (val t = d.trigger) {
                is StageTrigger.Calendar -> row(getString(R.string.settings_event_title), t.eventTitle.ifEmpty { getString(R.string.settings_unset) }) {
                    FormDialogs.text(ctx, getString(R.string.settings_event_title), t.eventTitle.ifEmpty { d.name }) { v -> update { it.copy(trigger = StageTrigger.Calendar(v)) } }
                }
                is StageTrigger.FixedTime -> {
                    row(getString(R.string.settings_start), t.start.format(TIME)) { FormDialogs.time(ctx, t.start) { v -> update { it.copy(trigger = t.copy(start = v)) } } }
                    row(getString(R.string.settings_end), t.end.format(TIME)) { FormDialogs.time(ctx, t.end) { v -> update { it.copy(trigger = t.copy(end = v)) } } }
                }
                is StageTrigger.TasksDone -> {
                    row(getString(R.string.settings_label), label(t.label)) { pickLabel(t.label) { v -> update { it.copy(trigger = t.copy(label = v)) } } }
                    row(getString(R.string.settings_until), t.until.format(TIME)) { FormDialogs.time(ctx, t.until) { v -> update { it.copy(trigger = t.copy(until = v)) } } }
                }
                is StageTrigger.TaskDue -> row(getString(R.string.settings_label), label(t.label)) { pickLabel(t.label) { v -> update { it.copy(trigger = StageTrigger.TaskDue(v)) } } }
                is StageTrigger.Default -> Unit
            }

            header(getString(R.string.settings_done))
            row(getString(R.string.settings_done_label), d.doneLabel?.let { "@$it" } ?: getString(R.string.settings_none)) {
                pickLabel(d.doneLabel.orEmpty(), allowNone = true) { v -> update { it.copy(doneLabel = v.ifEmpty { null }) } }
            }
            note(getString(R.string.settings_done_note))
            val perk = d.onDonePerk
            row(getString(R.string.settings_perk), perk?.let { "${cfg.group(it.groupId)?.name ?: it.groupId}, ${getString(R.string.cap_minutes, it.minutes)}" } ?: getString(R.string.settings_none)) {
                val options = listOf(Option("", getString(R.string.settings_none))) + cfg.appGroups.map { Option(it.id, it.name) }
                FormDialogs.choice(ctx, getString(R.string.settings_perk_group), options, perk?.groupId) { gid ->
                    if (gid.isEmpty()) update { it.copy(onDonePerk = null) }
                    else FormDialogs.number(ctx, getString(R.string.settings_perk_minutes), perk?.minutes ?: 10) { n -> update { it.copy(onDonePerk = Perk(gid, n ?: 10)) } }
                }
            }

            header(getString(R.string.settings_bypass))
            row(getString(R.string.settings_max_bypass), d.maxBypassMinutes?.let { getString(R.string.cap_minutes, it) } ?: getString(R.string.settings_no_limit)) {
                FormDialogs.number(ctx, getString(R.string.settings_max_bypass), d.maxBypassMinutes, getString(R.string.settings_zero_for_none)) { n -> update { it.copy(maxBypassMinutes = n) } }
            }

            header(getString(R.string.settings_allowed_groups))
            row(cfg.appGroups.filter { it.id in d.allowedGroups }.joinToString { it.name }.ifEmpty { getString(R.string.settings_none) }) {
                FormDialogs.multiChoice(ctx, getString(R.string.settings_allowed_groups), cfg.appGroups.map { Option(it.id, it.name) }, d.allowedGroups.toSet()) { chosen ->
                    update { it.copy(allowedGroups = cfg.appGroups.map { g -> g.id }.filter { id -> id in chosen }) }
                }
            }

            header(getString(R.string.settings_allowed_apps))
            note(getString(R.string.settings_allowed_apps_note))
            d.allowedApps.sortedBy { appLabel(it.packageName).lowercase() }.forEach { app ->
                row(appLabel(app.packageName), app.capMinutes?.let { getString(R.string.cap_minutes, it) } ?: getString(R.string.settings_uncapped),
                    onLong = { update { it.copy(allowedApps = it.allowedApps.filterNot { a -> a.packageName == app.packageName }) } }) {
                    FormDialogs.number(ctx, appLabel(app.packageName), app.capMinutes, getString(R.string.settings_zero_for_none)) { n ->
                        update { it.copy(allowedApps = it.allowedApps.map { a -> if (a.packageName == app.packageName) a.copy(capMinutes = n) else a }) }
                    }
                }
            }
            action(getString(R.string.settings_choose_apps)) {
                FormDialogs.multiChoice(ctx, getString(R.string.settings_allowed_apps), appOptions(), d.allowedApps.map { it.packageName }.toSet()) { chosen ->
                    update { s -> s.copy(allowedApps = chosen.map { pkg -> s.allowedApps.firstOrNull { it.packageName == pkg } ?: AllowedApp(pkg) }) }
                }
            }
        }
    }

    private fun label(l: String) = if (l.isEmpty()) getString(R.string.settings_unset) else "@$l"

    private fun triggerName(t: StageTrigger): String = getString(when (t) {
        is StageTrigger.Calendar -> R.string.trigger_calendar
        is StageTrigger.FixedTime -> R.string.trigger_fixed
        is StageTrigger.TasksDone -> R.string.trigger_tasks_done
        is StageTrigger.TaskDue -> R.string.trigger_task_due
        is StageTrigger.Default -> if (t.days == DayKind.WEEKDAY) R.string.trigger_default_weekday else R.string.trigger_default_weekend
    })

    private fun pickTriggerType() {
        val t = draft.trigger
        val current = when (t) {
            is StageTrigger.Calendar -> "calendar"; is StageTrigger.FixedTime -> "fixed"; is StageTrigger.TasksDone -> "tasksDone"
            is StageTrigger.TaskDue -> "taskDue"; is StageTrigger.Default -> if (t.days == DayKind.WEEKDAY) "weekday" else "weekend"
        }
        val options = listOf(
            Option("calendar", getString(R.string.trigger_calendar)), Option("fixed", getString(R.string.trigger_fixed)),
            Option("tasksDone", getString(R.string.trigger_tasks_done)), Option("taskDue", getString(R.string.trigger_task_due)),
            Option("weekday", getString(R.string.trigger_default_weekday)), Option("weekend", getString(R.string.trigger_default_weekend)),
        )
        FormDialogs.choice(requireContext(), getString(R.string.settings_trigger_type), options, current) { id ->
            if (id == current) return@choice
            val label = (t as? StageTrigger.TasksDone)?.label ?: (t as? StageTrigger.TaskDue)?.label ?: draft.doneLabel.orEmpty()
            update { s -> s.copy(trigger = when (id) {
                "calendar" -> StageTrigger.Calendar(s.name)
                "fixed" -> StageTrigger.FixedTime(LocalTime.of(21, 0), LocalTime.of(6, 0))
                "tasksDone" -> StageTrigger.TasksDone(label, LocalTime.of(21, 0))
                "taskDue" -> StageTrigger.TaskDue(label)
                "weekend" -> StageTrigger.Default(DayKind.WEEKEND)
                else -> StageTrigger.Default(DayKind.WEEKDAY)
            }) }
        }
    }

    /** Known labels from the config first, then "type one". Stored without the @. */
    private fun pickLabel(current: String, allowNone: Boolean = false, onPick: (String) -> Unit) {
        val ctx = requireContext()
        val known = (TodoistRepository.labelsIn(config ?: return) + listOfNotNull(current.ifEmpty { null })).sorted()
        val options = buildList {
            if (allowNone) add(Option("", getString(R.string.settings_none)))
            known.forEach { add(Option(it, "@$it")) }
            add(Option(CUSTOM, getString(R.string.settings_type_label)))
        }
        FormDialogs.choice(ctx, getString(R.string.settings_label), options, current) { id ->
            if (id == CUSTOM) FormDialogs.text(ctx, getString(R.string.settings_label), current, "phone/morning") { v -> onPick(v.trimStart('@')) }
            else onPick(id)
        }
    }

    override fun save() {
        val ctx = requireContext().applicationContext
        val d = draft
        launch {
            val ok = commit(if (isNew) "stage added: ${d.name}" else "stage edited: ${d.name}") { cfg ->
                val id = if (isNew) slug(d.name, cfg.stages.map { it.id }) else stageId
                val stage = d.copy(id = id)
                cfg.copy(stages = if (isNew) cfg.stages + stage else cfg.stages.map { if (it.id == stageId) stage else it })
            }
            if (ok) {
                ctx.showToast(getString(R.string.settings_saved))
                findNavController().popBackStack()
            }
        }
    }

    override fun delete() {
        val ctx = requireContext().applicationContext
        FormDialogs.confirm(requireContext(), getString(R.string.settings_delete_stage), draft.name, getString(R.string.settings_delete)) {
            launch {
                val ok = commit("stage deleted: ${draft.name}") { cfg ->
                    cfg.copy(stages = cfg.stages.filterNot { it.id == stageId }, settings = cfg.settings.copy(claudeHiddenStages = cfg.settings.claudeHiddenStages - stageId))
                }
                if (ok) {
                    ctx.showToast(getString(R.string.settings_deleted))
                    findNavController().popBackStack()
                }
            }
        }
    }

    companion object {
        const val ARG_STAGE_ID = "stageId"
        private const val CUSTOM = "__custom__"
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
