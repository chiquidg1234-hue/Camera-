package com.grabafondo

import android.app.Application
import com.grabafondo.recording.RecordingNotifications

class GrabaFondoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordingNotifications.createChannels(this)
    }
}
