package com.k2fsa.sherpa.onnx.kws

import com.k2fsa.sherpa.onnx.R
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

private const val REQUEST_AUDIO_PERMISSION = 200
private const val ACTION_RESUME_FROM_TERMUX = "com.ancall.action.RESUME_WAKE_LISTENER"

class MainActivity : AppCompatActivity() {
    private lateinit var recordButton: Button
    private lateinit var statusText: TextView
    private var listenerActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recordButton = findViewById(R.id.record_button)
        statusText = findViewById(R.id.my_text)
        statusText.text = getString(R.string.hint)

        recordButton.setOnClickListener {
            if (listenerActive) stopListener()
            else if (hasRequiredPermissions()) startListener()
            else requestAudioPermissions()
        }

        if (intent?.action == ACTION_RESUME_FROM_TERMUX) {
            if (hasRequiredPermissions()) startListener() else requestAudioPermissions()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_RESUME_FROM_TERMUX) {
            if (hasRequiredPermissions()) startListener() else requestAudioPermissions()
        }
    }

    private fun requestAudioPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_AUDIO_PERMISSION)
    }

    private fun requiredPermissions(): Array<String> {
        val result = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) result.add("android.permission.POST_NOTIFICATIONS")
        return result.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (hasRequiredPermissions()) startListener()
            else statusText.text = "ANCALL Next needs microphone permission to listen for its wake phrase."
        }
    }

    private fun startListener() {
        val serviceIntent = Intent(this, AncallWakeWordService::class.java).apply {
            action = AncallWakeWordService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        listenerActive = true
        recordButton.setText(R.string.stop)
        statusText.text = "ANCALL Next is listening for “Hey ANCALL”. Use the notification to stop listening."
    }

    private fun stopListener() {
        val serviceIntent = Intent(this, AncallWakeWordService::class.java).apply {
            action = AncallWakeWordService.ACTION_STOP
        }
        startService(serviceIntent)
        listenerActive = false
        recordButton.setText(R.string.start)
        statusText.text = "ANCALL Next listener stopped. Tap Start when you want it to listen again."
    }
}
