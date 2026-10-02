package com.studio.audio.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class AudioRecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Flush active buffers, release wake locks, and stop foreground task
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
