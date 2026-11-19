package com.quizai.mobile

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast

/** Permission/foreground setup only. The held-camera preview lives in the service overlay. */
class CameraActivity : Activity() {
    private var requested = false
    private val ready = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getBooleanExtra("success", false) != true) {
                Toast.makeText(this@CameraActivity, "No se pudo activar Cam. Revisa el permiso.", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Activando modo Cam..."
            setPadding(32, 24, 32, 24)
        })
        val filter = IntentFilter(ACTION_MODE_READY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ready, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(ready, filter)
    }

    override fun onResume() {
        super.onResume()
        if (!requested) {
            requested = true
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 41)
            } else enableCam()
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 41) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) enableCam()
            else {
                Toast.makeText(this, "Cam necesita permiso de camara.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun enableCam() {
        startForegroundService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_ENABLE_CAMERA))
    }

    override fun onDestroy() {
        unregisterReceiver(ready)
        super.onDestroy()
    }

    companion object { const val ACTION_MODE_READY = "com.quizai.mobile.CAMERA_MODE_READY" }
}
