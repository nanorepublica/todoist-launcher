package uk.co.softwarecrafts.contextlauncher.ui.settings

import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.showToast
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.config.AllowedApp
import uk.co.softwarecrafts.contextlauncher.core.config.AppGroup
import uk.co.softwarecrafts.contextlauncher.core.config.GroupKind

/** Create or edit one app group: name, kind, members with optional caps. */
class GroupEditFragment : FormFragment() {

    private val groupId: String get() = arguments?.getString(ARG_GROUP_ID).orEmpty()
    private val isNew get() = groupId.isEmpty()
    override val title: String get() = getString(if (isNew) R.string.settings_new_group else R.string.settings_edit_group)

    private var draft: AppGroup = AppGroup(id = "", name = "")

    override fun load() {
        val ctx = requireContext().applicationContext
        launch {
            Graph.config(ctx).load().group(groupId)?.let { draft = it }
            binding.delete.isVisible = !isNew
            render()
        }
    }

    private fun update(change: (AppGroup) -> AppGroup) {
        draft = change(draft)
        render()
    }

    private fun render() {
        val ctx = requireContext()
        val d = draft
        form {
            row(getString(R.string.settings_name), d.name.ifEmpty { getString(R.string.settings_unset) }) {
                FormDialogs.text(ctx, getString(R.string.settings_name), d.name) { v -> update { it.copy(name = v) } }
            }
            row(getString(R.string.settings_kind), kindName(d.kind)) {
                val options = GroupKind.values().map { Option(it.name, kindName(it)) }
                FormDialogs.choice(ctx, getString(R.string.settings_kind), options, d.kind.name) { id -> update { it.copy(kind = GroupKind.valueOf(id)) } }
            }
            note(getString(R.string.settings_kind_note))

            header(getString(R.string.settings_group_apps))
            note(getString(R.string.settings_allowed_apps_note))
            d.apps.sortedBy { appLabel(it.packageName).lowercase() }.forEach { app ->
                row(appLabel(app.packageName), app.capMinutes?.let { getString(R.string.cap_minutes, it) } ?: getString(R.string.settings_uncapped),
                    onLong = { update { it.copy(apps = it.apps.filterNot { a -> a.packageName == app.packageName }) } }) {
                    FormDialogs.number(ctx, appLabel(app.packageName), app.capMinutes, getString(R.string.settings_zero_for_none)) { n ->
                        update { it.copy(apps = it.apps.map { a -> if (a.packageName == app.packageName) a.copy(capMinutes = n) else a }) }
                    }
                }
            }
            action(getString(R.string.settings_choose_apps)) {
                FormDialogs.multiChoice(ctx, getString(R.string.settings_group_apps), appOptions(), d.apps.map { it.packageName }.toSet()) { chosen ->
                    update { g -> g.copy(apps = chosen.map { pkg -> g.apps.firstOrNull { it.packageName == pkg } ?: AllowedApp(pkg) }) }
                }
            }
        }
    }

    private fun kindName(k: GroupKind) = getString(when (k) {
        GroupKind.NORMAL -> R.string.kind_normal
        GroupKind.UNRESTRICTED -> R.string.kind_unrestricted
        GroupKind.OCCASIONAL -> R.string.kind_occasional
    })

    override fun save() {
        val ctx = requireContext().applicationContext
        val d = draft
        launch {
            val ok = commit(if (isNew) "group added: ${d.name}" else "group edited: ${d.name}") { cfg ->
                val id = if (isNew) slug(d.name, cfg.appGroups.map { it.id }) else groupId
                val group = d.copy(id = id)
                cfg.copy(appGroups = if (isNew) cfg.appGroups + group else cfg.appGroups.map { if (it.id == groupId) group else it })
            }
            if (ok) {
                ctx.showToast(getString(R.string.settings_saved))
                findNavController().popBackStack()
            }
        }
    }

    override fun delete() {
        val ctx = requireContext().applicationContext
        FormDialogs.confirm(requireContext(), getString(R.string.settings_delete_group), getString(R.string.settings_delete_group_note, draft.name), getString(R.string.settings_delete)) {
            launch {
                val ok = commit("group deleted: ${draft.name}") { cfg ->
                    cfg.copy(
                        appGroups = cfg.appGroups.filterNot { it.id == groupId },
                        stages = cfg.stages.map { s -> s.copy(allowedGroups = s.allowedGroups - groupId, onDonePerk = s.onDonePerk?.takeIf { it.groupId != groupId }) },
                        labelGroups = cfg.labelGroups.filterNot { it.groupId == groupId },
                    )
                }
                if (ok) {
                    ctx.showToast(getString(R.string.settings_deleted))
                    findNavController().popBackStack()
                }
            }
        }
    }

    companion object {
        const val ARG_GROUP_ID = "groupId"
    }
}
