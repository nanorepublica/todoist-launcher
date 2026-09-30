package uk.co.softwarecrafts.contextlauncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.databinding.FragmentSpeakBinding
import app.olauncher.helper.copyToClipboard
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import uk.co.softwarecrafts.contextlauncher.Graph
import uk.co.softwarecrafts.contextlauncher.core.voice.Destination
import uk.co.softwarecrafts.contextlauncher.core.voice.Destinations
import uk.co.softwarecrafts.contextlauncher.engine.StageState
import java.util.Locale

/**
 * "speak": records with Android's built-in recognizer, preferring on-device
 * recognition, shows the transcript for a quick edit, then routes it to a
 * Todoist task, the Claude app (share) or the clipboard. No audio is kept.
 */
class SpeakFragment : Fragment() {

    private var _binding: FragmentSpeakBinding? = null
    private val binding get() = _binding!!
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else binding.status.text = getString(R.string.speak_mic_denied)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSpeakBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cancel.setOnClickListener { findNavController().popBackStack() }
        binding.listen.setOnClickListener { ensureMicThenListen() }
        binding.toTask.setOnClickListener { route(Destination.TASK) }
        binding.toClaude.setOnClickListener { route(Destination.CLAUDE) }
        binding.toCopy.setOnClickListener { route(Destination.COPY) }

        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val config = Graph.config(ctx).load()
            val stageId = (Graph.stageEngine(ctx).state.value as? StageState.Ready)?.resolution?.stage?.id
            val available = Destinations.available(config, stageId, claudeInstalled = isInstalled(config.settings.claudePackage))
            binding.toClaude.isVisible = Destination.CLAUDE in available
        }
        ensureMicThenListen()
    }

    private fun isInstalled(pkg: String): Boolean =
        runCatching { requireContext().packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun ensureMicThenListen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startListening()
        else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        val ctx = requireContext()
        stopListening()
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        val available = onDevice || SpeechRecognizer.isRecognitionAvailable(ctx)
        if (!available) {
            binding.status.text = getString(R.string.speak_unavailable)
            return
        }
        recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer?.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        binding.status.text = getString(if (onDevice) R.string.speak_listening_on_device else R.string.speak_listening)
        recognizer?.startListening(intent)
    }

    private fun stopListening() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() { _binding?.status?.text = getString(R.string.speak_hearing) }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { _binding?.status?.text = getString(R.string.speak_processing) }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (text.isNotBlank()) _binding?.transcript?.setText(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _binding?.transcript?.setText(text)
            _binding?.transcript?.setSelection(text.length)
            _binding?.status?.text = getString(R.string.speak_done)
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            _binding?.status?.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.speak_nothing_heard)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.speak_mic_denied)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> getString(R.string.speak_language_unavailable)
                else -> getString(R.string.speak_error, error)
            }
        }
    }

    private fun route(destination: Destination) {
        val text = binding.transcript.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            requireContext().showToast(getString(R.string.speak_nothing_to_send))
            return
        }
        binding.transcript.hideKeyboard()
        val ctx = requireContext().applicationContext
        when (destination) {
            Destination.COPY -> {
                ctx.copyToClipboard(text)
                ctx.showToast(getString(R.string.copied))
                findNavController().popBackStack()
            }
            Destination.CLAUDE -> viewLifecycleOwner.lifecycleScope.launch {
                val pkg = Graph.config(ctx).load().settings.claudePackage
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolved = ctx.packageManager.queryIntentActivities(send, 0).isNotEmpty()
                if (resolved) ctx.startActivity(send)
                else ctx.startActivity(Intent.createChooser(send.apply { setPackage(null) }, getString(R.string.speak_to_claude)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                findNavController().popBackStack()
            }
            Destination.TASK -> viewLifecycleOwner.lifecycleScope.launch {
                binding.status.text = getString(R.string.speak_adding_task)
                val result = runCatching { Graph.todoist(ctx).quickAdd(text) }
                ctx.showToast(result.fold({ getString(R.string.task_added, it.content) }, { getString(R.string.task_add_failed, it.message ?: "network") }))
                if (result.isSuccess) findNavController().popBackStack()
                else binding.status.text = getString(R.string.speak_done)
            }
        }
    }

    override fun onPause() {
        stopListening()
        super.onPause()
    }

    override fun onDestroyView() {
        stopListening()
        super.onDestroyView()
        _binding = null
    }
}
