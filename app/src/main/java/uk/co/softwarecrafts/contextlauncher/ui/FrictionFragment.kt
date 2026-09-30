package uk.co.softwarecrafts.contextlauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.databinding.FragmentFrictionBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.hideKeyboard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.gate.Decision
import uk.co.softwarecrafts.contextlauncher.core.gate.Escalation
import uk.co.softwarecrafts.contextlauncher.core.gate.TimeLimitOptions
import uk.co.softwarecrafts.contextlauncher.engine.StageState

/**
 * The bypass screen for an off-list app: optional reason, required time
 * limit capped by the stage, and a countdown that grows with each bypass in
 * the current stage block (0, 10, 15, 30 s).
 */
class FrictionFragment : Fragment() {

    private var _binding: FragmentFrictionBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private var chosenMinutes: Int? = null
    private var countdownDone = false
    private var countdownJob: Job? = null
    private val pills = mutableListOf<TextView>()

    private val packageName get() = requireArguments().getString(ARG_PACKAGE).orEmpty()
    private val label get() = requireArguments().getString(ARG_LABEL).orEmpty()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFrictionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        val gate = Graph.gate(requireContext())
        binding.appName.text = label
        binding.cancel.setOnClickListener { findNavController().popBackStack() }
        binding.open.setOnClickListener { open() }

        val stageName = (Graph.stageEngine(requireContext()).state.value as? StageState.Ready)?.resolution?.stage?.name ?: "this stage"
        val bypassNumber = gate.nextBypassNumber()
        binding.offList.text = getString(R.string.friction_off_list, stageName, bypassNumber)

        viewLifecycleOwner.lifecycleScope.launch {
            val max = (gate.decide(packageName) as? Decision.Friction)?.maxMinutes
            buildPills(TimeLimitOptions.minutes(max))
            startCountdown(Escalation.waitSeconds(bypassNumber))
        }
    }

    private fun buildPills(options: List<Int>) {
        binding.limits.removeAllViews()
        pills.clear()
        options.chunked(4).forEach { chunk ->
            val rowLayout = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { minutes ->
                val pill = TextView(requireContext(), null, 0, R.style.SetupButton).apply {
                    text = getString(R.string.cap_minutes, minutes)
                    setBackgroundResource(R.drawable.bg_button_outline)
                    setPadding(18.dpToPx(), 10.dpToPx(), 18.dpToPx(), 10.dpToPx())
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.setMargins(0, 10.dpToPx(), 10.dpToPx(), 0)
                    layoutParams = params
                    alpha = 0.5f
                    setOnClickListener { choose(minutes) }
                }
                pills += pill
                rowLayout.addView(pill)
            }
            binding.limits.addView(rowLayout)
        }
        updateOpenButton()
    }

    private fun choose(minutes: Int) {
        chosenMinutes = minutes
        pills.forEach { it.alpha = if (it.text == getString(R.string.cap_minutes, minutes)) 1f else 0.5f }
        updateOpenButton()
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            var left = seconds
            while (left > 0) {
                binding.countdown.text = getString(R.string.friction_wait, left)
                delay(1_000)
                left--
            }
            countdownDone = true
            binding.countdown.text = if (seconds == 0) getString(R.string.friction_no_wait) else getString(R.string.friction_ready)
            updateOpenButton()
        }
    }

    private fun updateOpenButton() {
        val ready = countdownDone && chosenMinutes != null
        binding.open.isEnabled = ready
        binding.open.alpha = if (ready) 1f else 0.45f
        chosenMinutes?.let { binding.open.text = getString(R.string.friction_open_for, it) }
    }

    private fun open() {
        val minutes = chosenMinutes ?: return
        if (!countdownDone) return
        val appContext = requireContext().applicationContext
        val reason = binding.reason.text?.toString()?.trim().orEmpty()
        binding.reason.hideKeyboard()
        val app = AppModel.App(
            appLabel = label, key = null, appPackage = packageName,
            activityClassName = requireArguments().getString(ARG_CLASS),
            user = getUserHandleFromString(appContext, requireArguments().getString(ARG_USER).orEmpty()),
        )
        viewLifecycleOwner.lifecycleScope.launch {
            Graph.gate(appContext).startBypass(packageName, label, reason, minutes)
            viewModel.selectedApp(app, Constants.FLAG_LAUNCH_APP)
            findNavController().popBackStack(R.id.mainFragment, false)
        }
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_PACKAGE = "package"
        const val ARG_LABEL = "label"
        const val ARG_CLASS = "class"
        const val ARG_USER = "user"
    }
}
