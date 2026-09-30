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

    private val requestCalendar = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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
        binding.stepScheduleAction.setOnClickListener { writeSchedule() }
        binding.finish.setOnClickListener { finish() }

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
        binding.stepCalendarCreate.isEnabled = hasPermission
        binding.stepCalendarPick.isEnabled = hasPermission
        binding.stepScheduleAction.isEnabled = false

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
            binding.stepScheduleAction.isEnabled = info != null && !hasSchedule
        }
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
            result.onFailure { ctx.showToast("Could not create calendar: ${it.message}") }
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
            LinearLayout(container.context).apply {
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
        }
        pickerDialog = dialog
        dialog.showRespectingStatusBar()
    }

    private fun chooseCalendar(chosen: CalendarStore.CalendarInfo) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveCalendarName(chosen.name) }
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
        Graph.stageEngine(requireContext()).refresh()
        findNavController().popBackStack()
    }

    override fun onDestroyView() {
        pickerDialog?.dismiss()
        pickerDialog = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
