package uk.co.softwarecrafts.contextlauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.databinding.FragmentOnboardingBinding
import app.olauncher.helper.isDefaultLauncher
import app.olauncher.helper.openUrl
import android.widget.ScrollView
import app.olauncher.helper.OlDialog
import app.olauncher.helper.createDialog
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Duration
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.calendar.CalendarStore
import uk.co.softwarecrafts.contextlauncher.core.stage.DefaultSchedule
import uk.co.softwarecrafts.contextlauncher.data.AppPrefs
import uk.co.softwarecrafts.contextlauncher.voice.Ramble
import uk.co.softwarecrafts.contextlauncher.data.todoist.SyncStatus
import uk.co.softwarecrafts.contextlauncher.data.todoist.TodoistSyncWorker
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isAccessServiceEnabled
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter

/**
 * First-run setup, re-openable from settings. Every step is idempotent and
 * shows its current state, so leaving for a system screen and coming back is
 * fine.
 */
class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private lateinit var prefs: AppPrefs
    private lateinit var calendar: CalendarStore
    private var calendars: List<CalendarStore.CalendarInfo> = emptyList()
    private var pickerDialog: OlDialog? = null

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    private val requestCalendar = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.all { it }
        context?.showToast(getString(if (granted) R.string.onboarding_permission_granted else R.string.onboarding_permission_denied))
        refreshStatus()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        prefs = AppPrefs(requireContext())
        calendar = Graph.calendar(requireContext())

        binding.stepDefaultAction.setOnClickListener { viewModel.resetLauncherLiveData.call() }
        binding.stepPermissionAction.setOnClickListener { requestCalendar.launch(CalendarStore.PERMISSIONS) }
        binding.stepCalendarCreate.setOnClickListener { createLocalCalendar() }
        binding.stepCalendarPick.setOnClickListener { pickCalendar(it) }
        binding.stepCalendarGoogle.setOnClickListener {
            requireContext().showToast(getString(R.string.onboarding_google_hint), android.widget.Toast.LENGTH_LONG)
            requireContext().openUrl(GOOGLE_CREATE_CALENDAR_URL)
        }
        binding.stepScheduleAction.setOnClickListener { writeSchedule() }
        binding.finish.setOnClickListener { finish() }
        binding.todoistSave.setOnClickListener { saveTodoistToken() }
        binding.todoistOpenSettings.setOnClickListener { requireContext().openUrl(TODOIST_INTEGRATIONS_URL) }
        binding.todoistForget.setOnClickListener { forgetTodoistToken() }
        binding.enforceAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.enforceNotifications.setOnClickListener { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        binding.voiceAllowMic.setOnClickListener { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
        binding.voiceSpeechSettings.setOnClickListener {
            runCatching { startActivity(Intent("com.android.settings.action.SPEECH_RECOGNITION_SETTINGS")) }
                .recoverCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        }

        binding.stepScheduleList.text = DefaultSchedule.blocks.joinToString("\n") { block ->
            val days = if (block.days.contains(DayOfWeek.SATURDAY)) "Sat, Sun" else "Mon to Fri"
            "${block.title}: $days ${block.start.format(TIME)} to ${block.end.format(TIME)}"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val ctx = context ?: return
        binding.stepDefaultStatus.text = getString(if (ctx.isDefaultLauncher()) R.string.status_done else R.string.status_todo)
        val hasPermission = calendar.hasPermission()
        binding.stepPermissionStatus.text = getString(if (hasPermission) R.string.status_done else R.string.status_todo)
        binding.stepDefaultAction.setActive(!ctx.isDefaultLauncher())
        binding.stepPermissionAction.setActive(!hasPermission)
        binding.stepCalendarCreate.setActive(hasPermission)
        binding.stepCalendarPick.setActive(hasPermission)
        binding.stepScheduleAction.setActive(false)

        viewLifecycleOwner.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { Graph.config(ctx).load().settings.calendarName }
            val info = withContext(Dispatchers.IO) { if (hasPermission && name != null) calendar.findByName(name) else null }
            if (_binding == null) return@launch
            binding.stepCalendarStatus.text = when {
                name == null -> getString(R.string.status_todo)
                info == null -> getString(R.string.onboarding_calendar_missing, name)
                info.isLocal -> getString(R.string.onboarding_calendar_local, name)
                else -> getString(R.string.onboarding_calendar_synced, name, info.account)
            }
            val visible = withContext(Dispatchers.IO) { if (hasPermission) calendar.listCalendars().size else 0 }
            val blocksToday = withContext(Dispatchers.IO) {
                if (info == null) null else {
                    val now = Graph.clock.now()
                    calendar.instances(info.id, now.minus(Duration.ofHours(12)), now.plus(Duration.ofHours(12))).size
                }
            }
            binding.stepCalendarDetail.text = buildString {
                append(getString(R.string.onboarding_calendars_visible, visible))
                if (blocksToday != null) append("\n").append(getString(R.string.onboarding_blocks_today, blocksToday))
                if (info?.isLocal == true) append("\n\n").append(getString(R.string.onboarding_local_note))
            }
            val hasSchedule = info != null && withContext(Dispatchers.IO) { calendar.hasDefaultSchedule(info.id) }
            binding.stepScheduleStatus.text = getString(
                when {
                    info == null -> R.string.status_todo
                    hasSchedule -> R.string.status_done
                    else -> R.string.status_todo
                }
            )
            binding.stepScheduleAction.setActive(info != null && !hasSchedule)
        }
        refreshTodoistStatus()
        val serviceOn = isAccessServiceEnabled(ctx)
        binding.stepEnforceStatus.text = getString(if (serviceOn) R.string.onboarding_enforce_on else R.string.onboarding_enforce_off)
        binding.enforceAccessibility.setActive(!serviceOn)
        val notifyOn = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        binding.stepNotifyStatus.text = getString(if (notifyOn) R.string.onboarding_notifications_on else R.string.onboarding_notifications_off)
        binding.enforceNotifications.setActive(!notifyOn)
        refreshVoiceStatus(ctx)
    }

    private fun refreshVoiceStatus(ctx: android.content.Context) {
        val micOn = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        binding.stepMicStatus.text = getString(if (micOn) R.string.onboarding_mic_on else R.string.onboarding_mic_off)
        binding.voiceAllowMic.setActive(!micOn)
        viewLifecycleOwner.lifecycleScope.launch {
            val onDevice = runCatching { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)
            val claudePackage = withContext(Dispatchers.IO) { Graph.config(ctx).load().settings.claudePackage }
            val claudeInstalled = runCatching { ctx.packageManager.getPackageInfo(claudePackage, 0) }.isSuccess
            val shortcuts = withContext(Dispatchers.IO) { Ramble.todoistShortcuts(ctx) }
            val ramble = shortcuts.firstOrNull { it.id.contains("ramble", true) || it.label.contains("ramble", true) }
            if (_binding == null) return@launch
            binding.stepVoiceChecks.text = buildString {
                append(getString(if (onDevice) R.string.onboarding_ondevice_yes else R.string.onboarding_ondevice_no)).append("\n")
                append(if (claudeInstalled) getString(R.string.onboarding_claude_yes, claudePackage) else getString(R.string.onboarding_claude_no)).append("\n")
                when {
                    ramble != null -> append(getString(R.string.onboarding_ramble_yes, ramble.label))
                    shortcuts.isEmpty() -> append(getString(R.string.onboarding_shortcuts_none))
                    else -> append(getString(R.string.onboarding_shortcuts, shortcuts.joinToString { it.label }))
                }
                append("\n").append(getString(if (Ramble.addTaskIntent(ctx) != null) R.string.onboarding_addtask_yes else R.string.onboarding_addtask_no))
            }
        }
    }

    private fun refreshTodoistStatus() {
        val ctx = context ?: return
        val repo = Graph.todoist(ctx.applicationContext)
        binding.todoistForget.setActive(repo.hasToken)
        binding.todoistSave.setActive(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val status = repo.status.value
            val last = withContext(Dispatchers.IO) { repo.lastSyncAt() }
            if (_binding == null) return@launch
            binding.stepTodoistStatus.text = when {
                !repo.hasToken -> getString(R.string.todoist_status_none)
                status is SyncStatus.Syncing -> getString(R.string.todoist_syncing)
                status is SyncStatus.Ok -> getString(R.string.todoist_status_ok, status.taskCount, LocalDateTime.ofInstant(status.at, Graph.clock.zone).format(TIME))
                status is SyncStatus.Failed -> getString(R.string.todoist_status_failed, status.message)
                last != null -> getString(R.string.status_done)
                else -> getString(R.string.status_todo)
            }
        }
    }

    private fun saveTodoistToken() {
        val ctx = requireContext().applicationContext
        val typed = binding.todoistToken.text?.toString()?.trim().orEmpty()
        val repo = Graph.todoist(ctx)
        if (typed.isEmpty() && !repo.hasToken) {
            ctx.showToast(getString(R.string.todoist_token_hint))
            return
        }
        binding.todoistToken.hideKeyboard()
        if (typed.isNotEmpty()) {
            repo.saveToken(typed)
            if (!repo.hasToken) {
                ctx.showToast(getString(R.string.todoist_token_store_failed))
                return
            }
        }
        binding.stepTodoistStatus.text = getString(R.string.todoist_syncing)
        viewLifecycleOwner.lifecycleScope.launch {
            val status = repo.sync(forceFull = true)
            if (status is SyncStatus.Ok) {
                binding.todoistToken.text?.clear()
                TodoistSyncWorker.schedule(ctx)
            }
            refreshTodoistStatus()
        }
    }

    private fun forgetTodoistToken() {
        val ctx = requireContext().applicationContext
        Graph.todoist(ctx).saveToken(null)
        TodoistSyncWorker.cancel(ctx)
        refreshTodoistStatus()
    }

    private fun createLocalCalendar() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val info = calendar.createLocalCalendar(CalendarStore.DEFAULT_CALENDAR_NAME)
                    saveCalendarName(info.name)
                    info
                }
            }
            result.fold(
                { ctx.showToast(getString(R.string.onboarding_calendar_created, it.name)) },
                { ctx.showToast("Could not create calendar: ${it.message}") },
            )
            Graph.stageEngine(ctx).refresh()
            refreshStatus()
        }
    }

    private fun pickCalendar(@Suppress("UNUSED_PARAMETER") anchor: View) {
        calendars = calendar.listCalendars()
        if (calendars.isEmpty()) {
            requireContext().showToast(getString(R.string.onboarding_no_calendars))
            return
        }
        pickerDialog?.dismiss()
        val dialog = requireContext().createDialog(
            title = R.string.choose_calendar,
            action = R.string.close,
        ) { container ->
            val list = LinearLayout(container.context).apply {
                orientation = LinearLayout.VERTICAL
                calendars.forEach { cal ->
                    addView(TextView(context, null, 0, R.style.TextSmall).apply {
                        text = if (cal.isLocal) "${cal.name} (this phone)" else "${cal.name} (${cal.account})"
                        setPadding(24, 20, 24, 20)
                        setOnClickListener {
                            pickerDialog?.dismiss()
                            chooseCalendar(cal)
                        }
                    })
                }
            }
            ScrollView(container.context).apply {
                isVerticalScrollBarEnabled = true
                addView(list)
                // Long lists scroll inside the dialog instead of pushing it off screen
                val maxHeight = resources.displayMetrics.heightPixels / 2
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    if (calendars.size > 6) maxHeight else android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        }
        pickerDialog = dialog
        dialog.showRespectingStatusBar()
    }

    private fun chooseCalendar(chosen: CalendarStore.CalendarInfo) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveCalendarName(chosen.name) }
            ctx.showToast(getString(R.string.onboarding_calendar_chosen, chosen.name))
            Graph.stageEngine(ctx).refresh()
            refreshStatus()
        }
    }

    private suspend fun saveCalendarName(name: String) {
        val repo = Graph.config(requireContext().applicationContext)
        val cfg = repo.load()
        repo.save(cfg.copy(settings = cfg.settings.copy(calendarName = name)))
    }

    private fun writeSchedule() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val name = Graph.config(ctx).load().settings.calendarName ?: error("no calendar chosen")
                    val info = calendar.findByName(name) ?: error("calendar \"$name\" not found")
                    if (calendar.hasDefaultSchedule(info.id)) 0 else calendar.writeDefaultSchedule(info.id)
                }
            }
            added.fold(
                { ctx.showToast(getString(R.string.onboarding_schedule_added, it)) },
                { ctx.showToast("Could not add schedule: ${it.message}") },
            )
            Graph.stageEngine(ctx).refresh()
            refreshStatus()
        }
    }

    private fun finish() {
        prefs.onboardingDone = true
        requireContext().showToast(getString(R.string.onboarding_finished))
        Graph.stageEngine(requireContext()).refresh()
        findNavController().popBackStack()
    }

    override fun onDestroyView() {
        pickerDialog?.dismiss()
        pickerDialog = null
        super.onDestroyView()
        _binding = null
    }

    /** Buttons stay tappable when done (re-running is harmless) but dim to show the step is complete. */
    private fun TextView.setActive(active: Boolean) {
        alpha = if (active) 1f else 0.45f
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val GOOGLE_CREATE_CALENDAR_URL = "https://calendar.google.com/calendar/r/settings/createcalendar"
        const val TODOIST_INTEGRATIONS_URL = "https://app.todoist.com/app/settings/integrations/developer"
    }
}
