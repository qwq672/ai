// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.SeekBarPreference
import com.geniex.demo.R
import com.geniex.demo.utils.AppLogger
import com.geniex.demo.utils.Settings

/**
 * Standalone settings activity. Built on PreferenceFragmentCompat so the
 * preferences XML drives the entire UI, and preference values flow
 * directly into [Settings] (which wraps the default SharedPreferences).
 *
 * The fragment also wires up side effects that the static Settings
 * accessors can't trigger on their own — e.g. flipping logging off
 * calls [AppLogger.setEnabled] live, and the user can long-press the
 * log entry to share the file with a bug-report target.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar_settings))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.settings_title)
        }
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            // Wire the logging toggle to AppLogger live.
            findPreference<SwitchPreferenceCompat>(AppLogger.KEY_LOG_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Settings.loggingEnabled = enabled
                true
            }
            // Wire the share-log button.
            findPreference<Preference>("share_log")?.setOnPreferenceClickListener {
                val path = AppLogger.logFilePath()
                if (path == null) {
                    Toast.makeText(requireContext(), R.string.log_empty, Toast.LENGTH_SHORT).show()
                } else {
                    val uri = Uri.parse("file://$path")
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "GenieX Demo app.log")
                    }
                    startActivity(Intent.createChooser(share, getString(R.string.share_log)))
                }
                true
            }
            // Wire the clear-log button.
            findPreference<Preference>("clear_log")?.setOnPreferenceClickListener {
                val path = AppLogger.logFilePath()
                if (path != null) {
                    java.io.File(path).writeText("")
                    Toast.makeText(requireContext(), R.string.log_cleared, Toast.LENGTH_SHORT).show()
                }
                true
            }
            // Show summary on the NPU power mode preference.
            findPreference<ListPreference>(Settings.KEY_NPU_POWER_MODE)?.apply {
                summary = entry
                setOnPreferenceChangeListener { pref, newValue ->
                    val idx = findIndexOfValue(newValue.toString())
                    pref.summary = entries[idx]
                    // Persist + re-apply env vars (next LLM load will pick them up).
                    Settings.npuPowerMode = newValue.toString()
                    true
                }
            }
            // Live-update summaries on SeekBar preferences so users see numbers
            // next to the slider — default SeekBarPreference only updates the
            // persisted value silently.
            listOf(
                Settings.KEY_TEMPERATURE to "Temperature",
                Settings.KEY_TOP_P to "Top-P",
                Settings.KEY_TOP_K to "Top-K",
                Settings.KEY_REPETITION_PENALTY to "Repetition penalty",
                Settings.KEY_MAX_TOKENS to "Max tokens",
                Settings.KEY_VTCM_MB to "VTCM MB",
                Settings.KEY_CPU_THREADS to "CPU threads",
            ).forEach { (key, label) ->
                findPreference<SeekBarPreference>(key)?.let { sp ->
                    sp.summary = "$label: ${sp.value}"
                    sp.setOnPreferenceChangeListener { pref, newValue ->
                        (pref as SeekBarPreference).summary = "$label: $newValue"
                        true
                    }
                }
            }
        }
    }
}
