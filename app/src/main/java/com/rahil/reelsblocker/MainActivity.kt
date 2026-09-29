package com.rahil.reelsblocker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var state: BlockerState
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pauseTicker = Runnable { refresh() }

    private val importRules =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) importRulesFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        state = BlockerState(this)

        val swMaster = findViewById<SwitchMaterial>(R.id.sw_master)
        val swReels = findViewById<SwitchMaterial>(R.id.sw_reels)
        val swExplore = findViewById<SwitchMaterial>(R.id.sw_explore)
        val swHideIcon = findViewById<SwitchMaterial>(R.id.sw_hide_reels_icon)
        val swDmReels = findViewById<SwitchMaterial>(R.id.sw_dm_reels)
        val swDebug = findViewById<SwitchMaterial>(R.id.sw_debug)

        swMaster.isChecked = state.masterEnabled
        swReels.isChecked = state.blockReels
        swExplore.isChecked = state.blockExplore
        swHideIcon.isChecked = state.hideReelsIcon
        swDmReels.isChecked = state.allowDmReels
        swDebug.isChecked = state.debugMode

        swMaster.setOnCheckedChangeListener { _, checked -> state.masterEnabled = checked }
        swReels.setOnCheckedChangeListener { _, checked -> state.blockReels = checked }
        swExplore.setOnCheckedChangeListener { _, checked -> state.blockExplore = checked }
        swHideIcon.setOnCheckedChangeListener { _, checked -> state.hideReelsIcon = checked }
        swDmReels.setOnCheckedChangeListener { _, checked -> state.allowDmReels = checked }
        swDebug.setOnCheckedChangeListener { _, checked -> state.debugMode = checked }

        findViewById<MaterialButton>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btn_pause15).setOnClickListener { pauseFor(15) }
        findViewById<MaterialButton>(R.id.btn_pause60).setOnClickListener { pauseFor(60) }
        findViewById<MaterialButton>(R.id.btn_resume).setOnClickListener {
            state.pauseUntil = 0L
            refresh()
        }
        findViewById<MaterialButton>(R.id.btn_battery).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<MaterialButton>(R.id.btn_import_rules).setOnClickListener {
            importRules.launch("*/*")
        }
        findViewById<MaterialButton>(R.id.btn_reload_rules).setOnClickListener {
            val service = BlockerService.instance
            if (service != null) {
                service.reloadRules()
                Toast.makeText(this, "Detection rules reloaded", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Service not running", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(pauseTicker)
        super.onPause()
    }

    private fun pauseFor(minutes: Int) {
        state.pauseUntil = System.currentTimeMillis() + minutes * 60_000L
        refresh()
    }

    /**
     * Off the main thread (the picker may hand back a slow cloud file), reading at most
     * MAX_FILE_BYTES + 1 so a huge or endless stream can't exhaust memory. This process
     * also hosts the blocker service, so nothing here may crash it.
     */
    private fun importRulesFrom(uri: Uri) {
        // Application context only: a provider that hangs must not pin this Activity.
        val appContext = applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val message = try {
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray((DetectionRules.MAX_FILE_BYTES + 1).toInt())
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    buf.copyOf(n)
                }
                when {
                    bytes == null || bytes.isEmpty() -> "Could not read that file"
                    bytes.size > DetectionRules.MAX_FILE_BYTES -> "File too large (max 64KB)"
                    else -> {
                        val text = String(bytes, Charsets.UTF_8)
                        val problem = DetectionRules.checkImport(text)
                        if (problem != null) {
                            "Import rejected: $problem"
                        } else {
                            val tmp = File.createTempFile("rules", ".tmp", appContext.filesDir)
                            tmp.writeText(text)
                            if (!tmp.renameTo(File(appContext.filesDir, "detection_rules.json"))) {
                                tmp.delete()
                                throw IllegalStateException("rename failed")
                            }
                            null
                        }
                    }
                }
            } catch (t: Throwable) {
                "Import failed"
            }
            mainHandler.post {
                if (message == null) {
                    BlockerService.instance?.reloadRules()
                    Toast.makeText(appContext, "Rules imported ✓", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun refresh() {
        val enabledInSettings = isServiceEnabledInSettings()
        val actuallyRunning = BlockerService.instance != null
        findViewById<TextView>(R.id.tv_service_status).text = getString(
            when {
                enabledInSettings && actuallyRunning -> R.string.status_service_on
                enabledInSettings -> R.string.status_service_stuck
                else -> R.string.status_service_off
            }
        )
        findViewById<MaterialButton>(R.id.btn_accessibility).isEnabled = !(enabledInSettings && actuallyRunning)

        findViewById<TextView>(R.id.tv_stats).text = getString(R.string.stats_today, state.todayCount())

        val tvPause = findViewById<TextView>(R.id.tv_pause_status)
        if (state.isPaused()) {
            tvPause.text = getString(
                R.string.paused_until,
                DateFormat.getTimeFormat(this).format(Date(state.pauseUntil))
            )
            // refresh again the moment the pause expires so the label never goes stale
            uiHandler.removeCallbacks(pauseTicker)
            uiHandler.postDelayed(pauseTicker, state.pauseUntil - System.currentTimeMillis() + 250)
        } else {
            tvPause.text = getString(R.string.not_paused)
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<MaterialButton>(R.id.btn_battery).isEnabled = !exempt
        findViewById<TextView>(R.id.tv_battery_body).text =
            getString(if (exempt) R.string.battery_ok else R.string.battery_body)
    }

    private fun isServiceEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals("$packageName/.BlockerService", ignoreCase = true) ||
                it.equals("$packageName/$packageName.BlockerService", ignoreCase = true)
        }
    }
}
