package uk.co.softwarecrafts.contextlauncher.ui.settings

import app.olauncher.R
import app.olauncher.helper.showToast
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.config.LabelGroup
import uk.co.softwarecrafts.contextlauncher.core.config.LauncherConfig
import uk.co.softwarecrafts.contextlauncher.data.todoist.TodoistRepository

/**
 * The rest of the config: stage calendar, always-allowed apps, label to group
 * mappings, Claude package and the stages that hide it. Each change saves
 * at once.
 */
class ContextSettingsFragment : FormFragment() {

    override val title: String get() = getString(R.string.settings_context)
    override val showSave: Boolean = false

    override fun load() = render()

    private fun render() {
        val ctx = requireContext()
        val appContext = ctx.applicationContext
        launch {
            val cfg = Graph.config(appContext).load()
            form {
                header(getString(R.string.settings_calendar))
                row(cfg.settings.calendarName ?: getString(R.string.settings_unset)) { pickCalendar() }
                note(getString(R.string.settings_calendar_note))

                header(getString(R.string.settings_always_allowed))
                row(cfg.alwaysAllowed.joinToString { appLabel(it) }.ifEmpty { getString(R.string.settings_none) }) {
                    FormDialogs.multiChoice(ctx, getString(R.string.settings_always_allowed), appOptions(), cfg.alwaysAllowed.toSet(), max = LauncherConfig.MAX_ALWAYS_ALLOWED) { chosen ->
                        change("always allowed changed") { it.copy(alwaysAllowed = chosen.toList()) }
                    }
                }
                note(getString(R.string.settings_always_allowed_note, LauncherConfig.MAX_ALWAYS_ALLOWED))

                header(getString(R.string.settings_label_groups))
                note(getString(R.string.settings_label_groups_note))
                cfg.labelGroups.forEach { lg ->
                    row("@${lg.label}", cfg.group(lg.groupId)?.name ?: lg.groupId,
                        onLong = { change("label mapping removed: ${lg.label}") { c -> c.copy(labelGroups = c.labelGroups.filterNot { it.label == lg.label }) } }) {
                        pickGroup(cfg, lg.groupId) { gid -> change("label mapping changed: ${lg.label}") { c -> c.copy(labelGroups = c.labelGroups.map { if (it.label == lg.label) LabelGroup(lg.label, gid) else it }) } }
                    }
                }
                action(getString(R.string.settings_add_label_group)) {
                    val known = (TodoistRepository.labelsIn(cfg) - cfg.labelGroups.map { it.label }.toSet()).sorted()
                    val options = known.map { Option(it, "@$it") } + Option(CUSTOM, getString(R.string.settings_type_label))
                    FormDialogs.choice(ctx, getString(R.string.settings_label), options, null) { id ->
                        val onLabel = { label: String ->
                            if (label.isNotEmpty()) pickGroup(cfg, null) { gid -> change("label mapping added: $label") { c -> c.copy(labelGroups = c.labelGroups + LabelGroup(label, gid)) } }
                        }
                        if (id == CUSTOM) FormDialogs.text(ctx, getString(R.string.settings_label), "", "errand") { v -> onLabel(v.trimStart('@')) } else onLabel(id)
                    }
                }

                header(getString(R.string.settings_claude))
                row(getString(R.string.settings_claude_package), cfg.settings.claudePackage) {
                    FormDialogs.text(ctx, getString(R.string.settings_claude_package), cfg.settings.claudePackage) { v -> if (v.isNotEmpty()) change("claude package changed") { it.copy(settings = it.settings.copy(claudePackage = v)) } }
                }
                row(getString(R.string.settings_claude_hidden), cfg.stages.filter { it.id in cfg.settings.claudeHiddenStages }.joinToString { it.name }.ifEmpty { getString(R.string.settings_none) }) {
                    FormDialogs.multiChoice(ctx, getString(R.string.settings_claude_hidden), cfg.stages.sortedByDescending { it.rank }.map { Option(it.id, it.name) }, cfg.settings.claudeHiddenStages.toSet()) { chosen ->
                        change("claude hidden stages changed") { it.copy(settings = it.settings.copy(claudeHiddenStages = chosen.toList())) }
                    }
                }
                note(getString(R.string.settings_claude_note))
            }
        }
    }

    private fun pickGroup(cfg: LauncherConfig, current: String?, onPick: (String) -> Unit) {
        FormDialogs.choice(requireContext(), getString(R.string.settings_group), cfg.appGroups.map { Option(it.id, it.name) }, current, onPick)
    }

    private fun pickCalendar() {
        val ctx = requireContext()
        val calendar = Graph.calendar(ctx)
        if (!calendar.hasPermission()) {
            ctx.showToast(getString(R.string.onboarding_permission_denied))
            return
        }
        val calendars = calendar.listCalendars()
        if (calendars.isEmpty()) {
            ctx.showToast(getString(R.string.onboarding_no_calendars))
            return
        }
        val options = calendars.map { Option(it.name, if (it.isLocal) "${it.name} (this phone)" else "${it.name} (${it.account})") }
        launch {
            val current = Graph.config(ctx.applicationContext).load().settings.calendarName
            FormDialogs.choice(ctx, getString(R.string.choose_calendar), options, current) { name ->
                change("calendar changed: $name") { it.copy(settings = it.settings.copy(calendarName = name)) }
            }
        }
    }

    private fun change(what: String, edit: (LauncherConfig) -> LauncherConfig) {
        launch {
            commit(what, edit)
            render()
        }
    }

    private companion object {
        const val CUSTOM = "__custom__"
    }
}
