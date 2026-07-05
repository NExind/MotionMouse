package com.motionmouse.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class.
 *
 * @HiltAndroidApp triggers Hilt's code generation and installs
 * the application-level component. Every @Singleton dependency
 * lives for the lifetime of this object.
 *
 * We also create the notification channel here — it must exist
 * before the foreground service tries to post a notification,
 * and onCreate() is the correct place to register it because:
 *   - It runs before any Activity or Service starts
 *   - Creating an existing channel is a no-op (safe to repeat)
 *   - Channels cannot be created inside a Service on Android 8+
 *     without a race condition on first launch
 */
@HiltAndroidApp
class MotionMouseApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "motion_mouse_service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // IMPORTANCE_LOW: shows in tray without sound or heads-up popup.
                // The user will see it when they pull down the shade but it
                // won't interrupt them while they're using Motion Mouse.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)  // No badge on app icon for a service notification
            }

            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
