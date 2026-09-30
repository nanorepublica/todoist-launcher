package uk.co.softwarecrafts.contextlauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppListBinding
import app.olauncher.databinding.ItemAppBinding
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall
import kotlinx.coroutines.launch
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.gate.AllowedEntry
import uk.co.softwarecrafts.contextlauncher.core.gate.Decision
import java.text.Normalizer

/**
 * Every launchable app, searchable. Apps outside the current stage are greyed
 * out and go through the friction screen; typing down to a single match
 * launches it (through the same gate).
 */
class AppListFragment : Fragment() {

    private var _binding: FragmentAppListBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private lateinit var prefs: Prefs
    private val adapter = AppAdapter(::onAppTapped, ::onAppLongPressed)
    private var apps: List<AppModel.App> = emptyList()
    private var allowed: Map<String, AllowedEntry> = emptyMap()
    private var lastAutoLaunchQuery: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        prefs = Prefs(requireContext())
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        viewModel.appList.observe(viewLifecycleOwner) { list ->
            apps = list.orEmpty().filterIsInstance<AppModel.App>()
            refresh()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Graph.stageEngine(requireContext()).state.collect {
                    allowed = Graph.gate(requireContext()).allowed().associateBy { it.packageName }
                    refresh()
                }
            }
        }
        binding.search.doAfterTextChanged { refresh() }
        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                adapter.rows.firstOrNull()?.let { onAppTapped(it) }
                true
            } else false
        }
    }

    override fun onStart() {
        super.onStart()
        binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    override fun onStop() {
        binding.search.hideKeyboard()
        super.onStop()
    }

    private fun refresh() {
        val query = binding.search.text?.toString().orEmpty()
        val matching = if (query.isBlank()) apps else apps.filter { matches(it.appLabel, query) }
        adapter.submit(matching.map { AppRow(it, allowed[it.appPackage], allowed.isEmpty() || it.appPackage in allowed) })
        if (query.isNotBlank() && !query.startsWith(" ") && matching.size == 1 && lastAutoLaunchQuery != query) {
            lastAutoLaunchQuery = query
            onAppTapped(adapter.rows.first())
        }
    }

    private fun matches(label: String, query: String): Boolean {
        if (label.contains(query.trim(), ignoreCase = true)) return true
        val q = query.normalized()
        return q.isNotEmpty() && label.normalized().contains(q, ignoreCase = true)
    }

    private fun String.normalized(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD).replace(DIACRITICS, "").replace(SEPARATORS, "")

    private fun onAppTapped(row: AppRow) {
        val appContext = requireContext().applicationContext
        val app = row.app
        viewLifecycleOwner.lifecycleScope.launch {
            when (val decision = Graph.gate(appContext).decide(app.appPackage)) {
                is Decision.Allowed -> launch(app)
                is Decision.AllowedCapped -> {
                    Graph.gate(appContext).startCapped(app.appPackage, app.appLabel, decision.minutes)
                    appContext.showToast(getString(R.string.capped_session_started, app.appLabel, decision.minutes))
                    launch(app)
                }
                is Decision.Friction -> {
                    binding.search.hideKeyboard()
                    findNavController().navigate(
                        R.id.action_appListFragment_to_frictionFragment,
                        bundleOf(
                            FrictionFragment.ARG_PACKAGE to app.appPackage,
                            FrictionFragment.ARG_LABEL to app.appLabel,
                            FrictionFragment.ARG_CLASS to app.activityClassName,
                            FrictionFragment.ARG_USER to app.user.toString(),
                        ),
                    )
                }
            }
        }
    }

    private fun launch(app: AppModel.App) {
        binding.search.hideKeyboard()
        viewModel.selectedApp(app, Constants.FLAG_LAUNCH_APP)
        findNavController().popBackStack()
    }

    private fun onAppLongPressed(row: AppRow, anchor: View) {
        val app = row.app
        anchor.showPopupMenu(configure = { menu ->
            menu.add(Menu.NONE, 1, 1, getString(R.string.info))
            if (!requireContext().isSystemApp(app.appPackage, app.user)) menu.add(Menu.NONE, 2, 2, getString(R.string.delete))
        }) { item ->
            when (item.itemId) {
                1 -> openAppInfo(requireContext(), app.user, app.appPackage)
                2 -> requireContext().uninstall(app.appPackage)
            }
        }
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
        val SEPARATORS = Regex("[-_+,.`'\\s\\p{Z}]")
    }
}

data class AppRow(val app: AppModel.App, val entry: AllowedEntry?, val allowed: Boolean)

private class AppAdapter(
    private val onClick: (AppRow) -> Unit,
    private val onLongClick: (AppRow, View) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    var rows: List<AppRow> = emptyList()
        private set

    fun submit(newRows: List<AppRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.binding.appLabel.text = buildString {
            append(row.app.appLabel)
            row.entry?.capMinutes?.let { append("  ·  ").append(holder.binding.root.context.getString(R.string.cap_minutes, it)) }
        }
        holder.binding.appLabel.alpha = if (row.allowed) 1f else 0.4f
        holder.binding.root.setOnClickListener { onClick(row) }
        holder.binding.root.setOnLongClickListener { onLongClick(row, it); true }
    }

    class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)
}
