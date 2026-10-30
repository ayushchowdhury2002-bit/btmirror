package com.example.btmirror

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_PERMS = 1
    private val REQ_PROJECTION = 2

    private lateinit var audio: AudioManager
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var toggle: Button

    private val selected = LinkedHashSet<Int>()      // AudioDeviceInfo ids
    private val delays = HashMap<Int, Int>()         // id -> ms

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AudioManager::class.java)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "BT Mirror"
            textSize = 28f
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0, pad / 3, 0, pad / 2) }
        root.addView(status)

        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle)

        root.addView(TextView(this).apply {
            text = "Bluetooth outputs (tick the ones to mirror to)"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 4)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply { addView(root) })

        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun isBluetooth(d: AudioDeviceInfo): Boolean {
        if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return true
        if (Build.VERSION.SDK_INT >= 31 &&
            (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        ) return true
        return false
    }

    private fun refresh() {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { isBluetooth(it) }
        selected.retainAll(devices.map { it.id }.toSet())

        list.removeAllViews()
        if (devices.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No Bluetooth audio devices connected. Connect headphones/speakers in system Bluetooth settings."
            })
        }
        devices.forEach { d ->
            val box = CheckBox(this).apply {
                text = d.productName.toString()
                textSize = 18f
                isChecked = d.id in selected
                isEnabled = !MirrorService.running
                setOnCheckedChangeListener { _, on -> if (on) selected.add(d.id) else selected.remove(d.id) }
            }
            val value = TextView(this).apply { text = "${delays[d.id] ?: 0} ms"; minWidth = 150 }
            val seek = SeekBar(this).apply {
                max = 500
                progress = delays[d.id] ?: 0
                isEnabled = !MirrorService.running
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = p / 10 * 10          // 10 ms steps = one audio chunk
                        delays[d.id] = v
                        value.text = "$v ms"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = "Extra delay"; minWidth = 220 })
                addView(seek)
                addView(value)
            }
            list.addView(box)
            list.addView(row)
            list.addView(View(this).apply { minimumHeight = 24 })
        }

        status.text = if (MirrorService.running)
            "Mirroring to ${selected.size} device(s)."
        else
            "Stopped. Pick devices, then press Start."
        toggle.text = if (MirrorService.running) "Stop mirroring" else "Start mirroring"
    }

    private fun onToggle() {
        if (MirrorService.running) {
            stopService(Intent(this, MirrorService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 400)
            return
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least one Bluetooth device", Toast.LENGTH_SHORT).show()
            return
        }
        val missing = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        } else {
            requestProjection()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMS) return
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) requestProjection()
        else Toast.makeText(this, "Audio capture permission is required", Toast.LENGTH_LONG).show()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Capture permission denied", Toast.LENGTH_SHORT).show()
            return
        }
        val ids = selected.toIntArray()
        val svc = Intent(this, MirrorService::class.java)
            .putExtra("resultCode", resultCode)
            .putExtra("data", data)
            .putExtra("ids", ids)
            .putExtra("delays", IntArray(ids.size) { delays[ids[it]] ?: 0 })
        startForegroundService(svc)
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 600)
    }
}
