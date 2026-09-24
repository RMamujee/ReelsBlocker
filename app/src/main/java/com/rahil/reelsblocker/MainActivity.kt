package com.rahil.reelsblocker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var state: BlockerState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        state = BlockerState(this)

        val swMaster = findViewById<SwitchMaterial>(R.id.sw_master)
        val swReels = findViewById<SwitchMaterial>(R.id.sw_reels)
        val swExplore = findViewById<SwitchMaterial>(R.id.sw_explore)
        val swDebug = findViewById<SwitchMaterial>(R.id.sw_debug)

        swMaster.isChecked = state.masterEnabled
        swReels.isChecked = state.blockReels
        swExplore.isChecked = state.blockExplore
        swDebug.isChecked = state.debugMode

        swMaster.setOnCheckedChangeListener { _, checked -> state.masterEnabled = checked }
        swReels.setOnCheckedChangeListener { _, checked -> state.blockReels = checked }
        swExplore.setOnCheckedChangeListener { _, checked -> state.blockExplore = checked }
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

    private fun pauseFor(minutes: Int) {
        state.pauseUntil = System.currentTimeMillis() + minutes * 60_000L
        refresh()
    }

    private fun refresh() {
        val serviceOn = isServiceEnabled()
        findViewById<TextView>(R.id.tv_service_status).text =
            getString(if (serviceOn) R.string.status_service_on else R.string.status_service_off)
        findViewById<MaterialButton>(R.id.btn_accessibility).isEnabled = !serviceOn

        findViewById<TextView>(R.id.tv_stats).text = getString(R.string.stats_today, state.todayCount())

        findViewById<TextView>(R.id.tv_pause_status).text =
            if (state.isPaused())
                "Paused until " + DateFormat.getTimeFormat(this).format(Date(state.pauseUntil))
            else getString(R.string.not_paused)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<MaterialButton>(R.id.btn_battery).isEnabled = !exempt
        findViewById<TextView>(R.id.tv_battery_body).text =
            getString(if (exempt) R.string.battery_ok else R.string.battery_body)
    }

    private fun isServiceEnabled(): Boolean {
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
