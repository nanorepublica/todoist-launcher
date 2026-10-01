package uk.co.softwarecrafts.contextlauncher.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.createDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.hideStatusBar
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.showStatusBar
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.gate.AllowSource
import uk.co.softwarecrafts.contextlauncher.core.gate.AllowedEntry
import uk.co.softwarecrafts.contextlauncher.core.config.AppGroup
import uk.co.softwarecrafts.contextlauncher.core.config.GroupKind
import uk.co.softwarecrafts.contextlauncher.core.gate.TimedSession
import uk.co.softwarecrafts.contextlauncher.core.stage.Activation
import uk.co.softwarecrafts.contextlauncher.core.stage.TaskSnapshot
import uk.co.softwarecrafts.contextlauncher.data.AppPrefs
import uk.co.softwarecrafts.contextlauncher.core.todoist.TodayTasks
import uk.co.softwarecrafts.contextlauncher.engine.StageState
import uk.co.softwarecrafts.contextlauncher.voice.Ramble
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The text-only home: current stage, clock, running timed session, today's
 * tasks and the apps this stage allows. Gestures: swipe up or left for the
 * app list, down for notifications, long-press for settings, double-tap to
 * lock. Swipe right is reserved for the filtered notification list (v2).
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private var installed: Map<String, AppModel.App> = emptyMap()
    private var allowed: List<AllowedEntry> = emptyList()
    private var unrestrictedGroups: List<AppGroup> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        // The root watches every touch (rows included) and takes over sideways drags,
        // and vertical ones while the column fits the screen; otherwise the ScrollView scrolls.
        binding.mainLayout.gestures = gestureListener(requireContext())
        binding.mainLayout.verticalScrollable = { binding.homeScroll.canScrollVertically(1) || binding.homeScroll.canScrollVertically(-1) }
        binding.clock.setOnClickListener { openAlarmApp(requireContext()) }
        binding.date.setOnClickListener { openCalendar(requireContext()) }
        binding.tvStage.setOnClickListener {
            val state = Graph.stageEngine(requireContext()).state.value
            when {
                state is StageState.SetupNeeded -> findNavController().navigate(R.id.action_mainFragment_to_onboardingFragment)
                state is StageState.Ready && state.resolution.current is Activation.TaskDue -> openReview()
            }
        }
        binding.tvTasksHeader.setOnClickListener {
            if (!Graph.todoist(requireContext()).hasToken)
                findNavController().navigate(R.id.action_mainFragment_to_onboardingFragment)
        }
        binding.tvHint.isVisible = prefs.firstSettingsOpen
        binding.actionSpeak.setOnClickListener { findNavController().navigate(R.id.action_mainFragment_to_speakFragment) }
        binding.actionAddTask.setOnClickListener { startRamble() }
        binding.actionAddTask.setOnLongClickListener { showQuickAdd(); true }

        viewModel.appList.observe(viewLifecycleOwner) { apps ->
            installed = apps.orEmpty().filterIsInstance<AppModel.App>().associateBy { it.appPackage }
            renderApps()
        }
        observeStage()
        observeTasks()
        observeSession()
        observeClock()
    }

    override fun onResume() {
        super.onResume()
        val appContext = requireContext().applicationContext
        Graph.gate(appContext).onLauncherResumed()
        Graph.stageEngine(appContext).refresh()
        viewLifecycleOwner.lifecycleScope.launch { Graph.todoist(appContext).syncIfStale() }
        renderDate()
        if (prefs.showStatusBar) requireActivity().window.showStatusBar() else requireActivity().window.hideStatusBar()
    }

    // ---- clock (follows Graph.clock, so debug time travel shows on the home) ----

    private fun observeClock() {
        val twentyFour = android.text.format.DateFormat.is24HourFormat(requireContext())
        val format = DateTimeFormatter.ofPattern(if (twentyFour) "HH:mm" else "h:mm")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastDay = Graph.clock.today()
                while (isActive) {
                    val now = Graph.clock.localNow()
                    binding.clock.text = now.format(format)
                    if (now.toLocalDate() != lastDay) { lastDay = now.toLocalDate(); renderDate() }
                    delay(1_000L - (System.currentTimeMillis() % 1_000L))
                }
            }
        }
    }

    private fun renderDate() {
        _binding?.date?.text = Graph.clock.today().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
    }

    // ---- stage line and allowed apps ----

    private fun observeStage() {
        val engine = Graph.stageEngine(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.state.collect { state ->
                    binding.tvStage.text = when (state) {
                        is StageState.Loading -> ""
                        is StageState.SetupNeeded -> getString(R.string.stage_setup_needed, state.reason)
                        is StageState.Ready -> {
                            val until = state.resolution.nextChangeAt
                                ?.let { LocalDateTime.ofInstant(it, engine.zone).toLocalTime().format(TIME) }
                            if (until != null) getString(R.string.stage_until, state.resolution.stage.name, until)
                            else state.resolution.stage.name
                        }
                    }
                    allowed = Graph.gate(requireContext()).allowed()
                    unrestrictedGroups = Graph.config(requireContext()).load().groups(GroupKind.UNRESTRICTED)
                    renderApps()
                    maybeOpenReview(state)
                }
            }
        }
    }

    /** The weekly review is forced: it opens itself once each time a @phone/review task falls due. */
    private fun maybeOpenReview(state: StageState) {
        val due = ((state as? StageState.Ready)?.resolution?.current as? Activation.TaskDue) ?: return
        val prefs = AppPrefs(requireContext())
        if (prefs.reviewOpenedForTask == due.taskId) return
        if (findNavController().currentDestination?.id != R.id.mainFragment) return
        prefs.reviewOpenedForTask = due.taskId
        openReview()
    }

    private fun openReview() {
        try {
            findNavController().navigate(R.id.action_mainFragment_to_reviewFragment)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun renderApps() {
        val list = _binding?.appsList ?: return
        list.removeAllViews()
        val stageRows = allowed.filter { it.source != AllowSource.UNRESTRICTED }
            .mapNotNull { entry -> installed[entry.packageName]?.let { entry to it } }
            .sortedBy { (_, app) -> app.appLabel.lowercase() }
        binding.tvAppsHeader.text = if (stageRows.isEmpty()) getString(R.string.apps_header_empty) else getString(R.string.apps_header)
        stageRows.forEach { (entry, app) -> list.addView(appRow(entry, app)) }

        // Unrestricted groups, each under its own name
        val unrestricted = allowed.filter { it.source == AllowSource.UNRESTRICTED }.associateBy { it.packageName }
        unrestrictedGroups.forEach { group ->
            val rows = group.apps.mapNotNull { a -> unrestricted[a.packageName]?.let { e -> installed[a.packageName]?.let { e to it } } }
                .sortedBy { (_, app) -> app.appLabel.lowercase() }
            if (rows.isEmpty()) return@forEach
            list.addView(TextView(requireContext(), null, 0, R.style.TextSmallBold).apply {
                text = group.name
                setPadding(3.dpToPx(), 16.dpToPx(), 3.dpToPx(), 4.dpToPx())
            })
            rows.forEach { (entry, app) -> list.addView(appRow(entry, app)) }
        }
    }

    private fun appRow(entry: AllowedEntry, app: AppModel.App): TextView = row(buildString {
        append(app.appLabel)
        entry.capMinutes?.let { append("  ·  ").append(getString(R.string.cap_minutes, it)) }
    }) { launchAllowed(entry, app) }

    private fun launchAllowed(entry: AllowedEntry, app: AppModel.App) {
        val appContext = requireContext().applicationContext
        val cap = entry.capMinutes
        if (cap == null) {
            viewModel.selectedApp(app, Constants.FLAG_LAUNCH_APP)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            Graph.gate(appContext).startCapped(app.appPackage, app.appLabel, cap)
            appContext.showToast(getString(R.string.capped_session_started, app.appLabel, cap))
            viewModel.selectedApp(app, Constants.FLAG_LAUNCH_APP)
        }
    }

    // ---- tasks ----

    private fun observeTasks() {
        val appContext = requireContext().applicationContext
        val engine = Graph.stageEngine(appContext)
        val todoist = Graph.todoist(appContext)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(todoist.snapshotFlow(), engine.state) { tasks, state -> tasks to state }
                    .collect { (tasks, state) -> renderTasks(tasks, state) }
            }
        }
    }

    private fun renderTasks(tasks: List<TaskSnapshot>, state: StageState) {
        val list = binding.tasksList
        list.removeAllViews()
        if (!Graph.todoist(requireContext()).hasToken) {
            binding.tvTasksHeader.text = getString(R.string.connect_todoist)
            return
        }
        val gatingLabel = (state as? StageState.Ready)?.resolution?.stage?.doneLabel
        val rows = TodayTasks.rows(tasks, Graph.clock.today(), gatingLabel)
        binding.tvTasksHeader.text = if (rows.isEmpty()) getString(R.string.no_tasks_today) else getString(R.string.today_header)
        rows.take(MAX_TASK_ROWS).forEach { r ->
            list.addView(row(buildString {
                append(if (r.gating) "● " else "○ ")
                append(r.task.content)
                r.task.dueTime?.let { append("  ").append(it.format(TIME)) }
                if (r.overdue) append(getString(R.string.overdue_suffix))
            }) { confirmComplete(r.task) })
        }
        if (rows.size > MAX_TASK_ROWS) {
            list.addView(TextView(requireContext(), null, 0, R.style.TextSmallLight).apply {
                text = getString(R.string.more_tasks, rows.size - MAX_TASK_ROWS)
                setPadding(3.dpToPx(), 6.dpToPx(), 3.dpToPx(), 6.dpToPx())
            })
        }
    }

    private fun confirmComplete(task: TaskSnapshot) {
        val appContext = requireContext().applicationContext
        requireContext().createDialog(title = R.string.complete_task_title, action = R.string.complete, onAction = {
            viewLifecycleOwner.lifecycleScope.launch {
                val result = runCatching { Graph.todoist(appContext).complete(task.id) }
                appContext.showToast(result.fold(
                    { getString(R.string.task_completed, task.content) },
                    { getString(R.string.task_complete_failed, it.message ?: "network") },
                ))
            }
        }) { container ->
            TextView(container.context, null, 0, R.style.TextSmall).apply {
                text = task.content
                setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 8.dpToPx())
            }
        }.showRespectingStatusBar()
    }

    // ---- running session ----

    private fun observeSession() {
        val gate = Graph.gate(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                gate.session.collect { session ->
                    if (session == null) {
                        binding.tvSession.isVisible = false
                    } else {
                        binding.tvSession.isVisible = true
                        while (isActive && gate.session.value == session) {
                            binding.tvSession.text = sessionText(session)
                            delay(1_000)
                        }
                    }
                }
            }
        }
    }

    private fun sessionText(session: TimedSession): String {
        val label = installed[session.packageName]?.appLabel ?: session.packageName
        val remaining = session.remaining(Graph.clock.now())
        val mm = remaining.toMinutes()
        val ss = remaining.minusMinutes(mm).seconds
        return getString(R.string.session_remaining, label, String.format(Locale.ROOT, "%d:%02d", mm, ss))
    }

    // ---- add task ----

    private fun startRamble() {
        val ctx = requireContext()
        when (Ramble.addTask(ctx)) {
            Ramble.Route.RAMBLE, Ramble.Route.ADD_TASK_URL -> Unit
            Ramble.Route.TODOIST -> ctx.showToast(getString(R.string.ramble_missing))
            Ramble.Route.NONE -> ctx.showToast(getString(R.string.todoist_missing))
        }
    }

    private fun showQuickAdd() {
        val appContext = requireContext().applicationContext
        var input: EditText? = null
        val dialog = requireContext().createDialog(title = R.string.quick_add_title, action = R.string.add, onAction = {
            val text = input?.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@createDialog
            input?.hideKeyboard()
            viewLifecycleOwner.lifecycleScope.launch {
                val result = runCatching { Graph.todoist(appContext).quickAdd(text) }
                appContext.showToast(result.fold(
                    { getString(R.string.task_added, it.content) },
                    { getString(R.string.task_add_failed, it.message ?: "network") },
                ))
            }
        }) { container ->
            EditText(container.context, null, 0, R.style.TextSmall).apply {
                hint = getString(R.string.quick_add_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 2
                setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 8.dpToPx())
                input = this
            }
        }
        dialog.showRespectingStatusBar()
        input?.requestFocus()
        input?.showKeyboard()
    }

    // ---- helpers ----

    private fun row(text: String, onClick: () -> Unit): TextView =
        TextView(requireContext(), null, 0, R.style.TextSmall).apply {
            this.text = text
            setPadding(3.dpToPx(), 8.dpToPx(), 3.dpToPx(), 8.dpToPx())
            setOnClickListener { onClick() }
        }

    private fun gestureListener(context: Context): View.OnTouchListener = object : OnSwipeTouchListener(context) {
        override fun onSwipeUp() = openAppList()
        override fun onSwipeLeft() = openAppList()
        override fun onSwipeDown() = expandNotificationDrawer(context)
        override fun onSwipeRight() = Unit // reserved: filtered notifications (v2)
        override fun onLongClick() {
            try {
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                viewModel.firstOpen(false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        override fun onDoubleClick() {
            if (prefs.lockModeOn) binding.lock.performClick()
        }
    }

    private fun openAppList() {
        try {
            findNavController().navigate(R.id.action_mainFragment_to_appListFragment)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val MAX_TASK_ROWS = 6
    }
}
