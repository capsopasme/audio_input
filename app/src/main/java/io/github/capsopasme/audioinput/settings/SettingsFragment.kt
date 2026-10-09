/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 capsopasme and Fcitx5 for Android Contributors
 */
package io.github.capsopasme.audioinput.settings

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.capsopasme.audioinput.BuildConfig
import io.github.capsopasme.audioinput.R
import io.github.capsopasme.audioinput.asr.AudioCapture
import io.github.capsopasme.audioinput.asr.ModelPaths
import io.github.capsopasme.audioinput.asr.QnnCrashGuard
import io.github.capsopasme.audioinput.asr.SpeechModel
import io.github.capsopasme.audioinput.asr.VoiceClient
import io.github.capsopasme.audioinput.model.ModelManager
import io.github.capsopasme.audioinput.model.ModelManager.State
import kotlinx.coroutines.launch
import java.io.File

class SettingsFragment : PreferenceFragmentCompat() {

    private val prefs by lazy { Prefs.get(requireContext()) }

    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>

    private val modelPrefs = mutableMapOf<SpeechModel, Preference>()

    private var testDialog: AlertDialog? = null
    private var testClient: VoiceClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val appCtx = requireContext().applicationContext
            ModelManager.importArchive(appCtx, uri) { result ->
                // called on a background thread; toasts need the main thread
                mainHandler.post {
                    result
                        .onSuccess {
                            Toast.makeText(appCtx, appCtx.getString(R.string.import_done, appCtx.getString(it.titleRes)), Toast.LENGTH_SHORT).show()
                        }
                        .onFailure {
                            Toast.makeText(appCtx, appCtx.getString(R.string.import_failed, it.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                        }
                }
            }
        }
        super.onCreate(savedInstanceState)
    }

    private fun click(key: String, block: () -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener {
            block()
            true
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        val ctx = requireContext()

        val models = findPreference<PreferenceCategory>("cat_models")!!
        SpeechModel.entries.forEachIndexed { i, model ->
            val p = Preference(ctx).apply {
                key = "model_entry_${model.name}"
                title = getString(model.titleRes)
                isPersistent = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                order = i
                setOnPreferenceClickListener {
                    onModelClicked(model)
                    true
                }
            }
            models.addPreference(p)
            modelPrefs[model] = p
        }

        click("setup_enable") { openImeSettings() }
        click("setup_permission") { PermissionActivity.launch(ctx) }
        click("setup_model") { onModelClicked(prefs.model) }
        click("setup_try") { showTryDialog() }
        click("import_archive") {
            try {
                importLauncher.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, R.string.import_no_picker, Toast.LENGTH_SHORT).show()
            }
        }
        findPreference<EditTextPreference>(Prefs.KEY_DOWNLOAD_PREFIX)?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> {
                it.text?.takeIf { t -> t.isNotBlank() } ?: getString(R.string.download_prefix_none)
            }
        findPreference<Preference>("device_info")?.summary = deviceInfo()
        findPreference<Preference>("about_version")?.summary = BuildConfig.VERSION_NAME
        click("about_source") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
            } catch (_: ActivityNotFoundException) {
            }
        }
        click("about_licenses") {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.about_licenses)
                .setMessage(R.string.licenses_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        updateModelDependentPrefs()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            ModelManager.states.collect { updateModelSummaries(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ModelManager.importState.collect { s ->
                findPreference<Preference>("import_archive")?.summary = when (s) {
                    is State.Working -> progressText(R.string.importing, s)
                    else -> getString(R.string.import_archive_summary)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSetup()
        ModelManager.refreshAsync(requireContext())
    }

    // --- setup -----------------------------------------------------------------------------------

    private fun isImeEnabled(): Boolean {
        val ctx = requireContext()
        val imm = ctx.getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
    }

    private fun isImeCurrent(): Boolean {
        val ctx = requireContext()
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return ComponentName.unflattenFromString(id)?.packageName == ctx.packageName
    }

    private fun updateSetup() {
        val ctx = requireContext()
        findPreference<Preference>("setup_enable")?.summary = getString(
            when {
                !isImeEnabled() -> R.string.setup_enable_no
                isImeCurrent() -> R.string.setup_enable_current
                else -> R.string.setup_enable_yes
            }
        )
        findPreference<Preference>("setup_permission")?.summary = getString(
            if (AudioCapture.hasPermission(ctx)) R.string.setup_permission_ok else R.string.setup_permission_missing
        )
        updateSetupModel()
    }

    private fun updateSetupModel() {
        val ctx = context ?: return
        val model = prefs.model
        val installed = ModelPaths.isInstalled(ctx, model)
        findPreference<Preference>("setup_model")?.summary = getString(
            if (installed) R.string.setup_model_ok else R.string.setup_model_missing,
            getString(model.titleRes)
        )
    }

    private fun openImeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.setup_enable_manual, Toast.LENGTH_LONG).show()
        }
    }

    private fun showImePicker() {
        requireContext().getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun showTryDialog() {
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val edit = EditText(ctx).apply {
            hint = getString(R.string.try_hint)
            minLines = 3
        }
        val switchButton = MaterialButton(ctx).apply {
            text = getString(R.string.try_switch_ime)
            setOnClickListener { showImePicker() }
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(edit, LinearLayout.LayoutParams(-1, -2))
            addView(switchButton, LinearLayout.LayoutParams(-2, -2))
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.setup_try)
            .setView(layout)
            .setPositiveButton(R.string.close, null)
            .show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        edit.requestFocus()
    }

    // --- models ----------------------------------------------------------------------------------

    private fun progressText(label: Int, s: State.Working): String {
        val ctx = requireContext()
        val done = Formatter.formatShortFileSize(ctx, s.done)
        return if (s.total > 0) {
            val pct = (s.done * 100 / s.total).coerceIn(0, 100)
            getString(label) + " $pct% ($done / ${Formatter.formatShortFileSize(ctx, s.total)})"
        } else {
            getString(label) + " $done"
        }
    }

    private fun updateModelSummaries(states: Map<SpeechModel, State>) {
        val ctx = context ?: return
        val selected = prefs.model
        val npuTripped = QnnCrashGuard.isTripped(ctx)
        modelPrefs.forEach { (model, pref) ->
            val state = states[model] ?: State.NotInstalled
            val text = when (state) {
                State.NotInstalled -> getString(R.string.model_not_installed, model.downloadSizeMb)
                is State.Installed -> getString(R.string.model_installed, Formatter.formatShortFileSize(ctx, state.bytes))
                is State.Working -> progressText(R.string.downloading, state)
                is State.Failed -> getString(R.string.model_failed, state.message)
            }
            val sb = StringBuilder()
            if (model == selected) sb.append("✔ ").append(getString(R.string.model_selected)).append(" · ")
            sb.append(text).append('\n').append(getString(model.descRes))
            if (model.isQnn && npuTripped && state is State.Installed) {
                sb.append('\n').append(getString(R.string.model_npu_tripped))
            }
            pref.summary = sb
        }
        updateSetupModel()
    }

    private fun onModelClicked(model: SpeechModel) {
        val ctx = requireContext()
        val installed = ModelPaths.isInstalled(ctx, model)
        val busy = ModelManager.isBusy(model)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when {
            busy -> actions += getString(R.string.cancel_download) to { ModelManager.cancel(model) }
            installed -> {
                if (model != prefs.model) actions += getString(R.string.use_model) to { selectModel(model) }
                actions += getString(R.string.self_test) to { runSelfTest(model) }
                actions += getString(R.string.delete_model) to { confirmDelete(model) }
            }
            else -> actions += getString(R.string.download, model.downloadSizeMb) to { confirmDownload(model) }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(model.titleRes)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDownload(model: SpeechModel) {
        val ctx = requireContext()
        val metered = ctx.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
        if (!metered) {
            startDownload(model)
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(model.titleRes)
            .setMessage(getString(R.string.download_metered, model.downloadSizeMb))
            .setPositiveButton(R.string.download_anyway) { _, _ -> startDownload(model) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startDownload(model: SpeechModel) {
        val ctx = requireContext()
        ModelManager.download(ctx, model, prefs.downloadPrefix)
        // the first model downloaded becomes the active one
        if (!ModelPaths.isInstalled(ctx, prefs.model)) selectModel(model)
    }

    private fun selectModel(model: SpeechModel) {
        prefs.model = model
        updateModelSummaries(ModelManager.states.value)
        updateModelDependentPrefs()
    }

    private fun updateModelDependentPrefs() {
        val hasOptions = prefs.model.hasLanguageOptions
        findPreference<ListPreference>(Prefs.KEY_LANGUAGE)?.isEnabled = hasOptions
        findPreference<SwitchPreferenceCompat>(Prefs.KEY_ITN)?.isEnabled = hasOptions
    }

    private fun confirmDelete(model: SpeechModel) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(model.titleRes)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete_model) { _, _ -> ModelManager.delete(requireContext(), model) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runSelfTest(model: SpeechModel) {
        val ctx = requireContext()
        finishTest()
        val client = VoiceClient(ctx, object : VoiceClient.Listener {
            override fun onTestResult(
                text: String, backend: String, loadMillis: Long, decodeMillis: Long, audioMillis: Long
            ) {
                testDialog?.dismiss()
                val rtf = if (audioMillis > 0) decodeMillis.toFloat() / audioMillis else 0f
                showResult(
                    getString(R.string.self_test_result, backend, loadMillis, audioMillis / 1000f, decodeMillis, rtf, text)
                )
                finishTest()
                // a passing NPU test clears the crash marker
                updateModelSummaries(ModelManager.states.value)
            }

            override fun onError(message: String) {
                testDialog?.dismiss()
                showResult(getString(R.string.status_error, message))
                finishTest()
            }

            override fun onServiceCrashed() {
                testDialog?.dismiss()
                showResult(getString(R.string.status_crashed))
                finishTest()
                updateModelSummaries(ModelManager.states.value)
            }
        })
        testClient = client
        testDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.self_test)
            .setMessage(R.string.self_test_running)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finishTest() }
            .show()
        client.selfTest(model, prefs.language, prefs.itn)
    }

    private fun finishTest() {
        testClient?.unbind()
        testClient = null
    }

    private fun showResult(message: String) {
        val ctx = context ?: return
        if (!isAdded) return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.self_test)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun deviceInfo(): String {
        val ctx = requireContext()
        val libDir = ctx.applicationInfo.nativeLibraryDir
        val sherpa = File(libDir, "libsherpa-onnx-jni.so").exists()
        val qnn = File(libDir, "libQnnHtpV75Skel.so").exists()
        return getString(
            R.string.device_info_summary,
            SpeechModel.socModel.ifEmpty { "?" },
            getString(if (sherpa) R.string.yes else R.string.no),
            getString(if (qnn) R.string.yes else R.string.no)
        )
    }

    override fun onDestroy() {
        testDialog?.dismiss()
        finishTest()
        super.onDestroy()
    }

    companion object {
        const val SOURCE_URL = "https://github.com/capsopasme/audio_input"
    }
}
