package com.v2ray.ang

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        remoteMessage.notification?.let {
            sendNotification(it.title, it.body)
        }
    }

    override fun onNewToken(token: String) {
        // توکن جدید FCM دریافت شد
        // می‌توانید این توکن را به سرور خود ارسال کنید
        android.util.Log.d(AppConfig.TAG, "New FCM Token: $token")
    }

    private fun sendNotification(title: String?, messageBody: String?) {
        val channelId = "v2rayNG_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // ایجاد Notification Channel برای اندروید 8.0 و بالاتر
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "v2rayNG Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        // ساخت نوتیفیکیشن
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification) // آیکون نوتیفیکیشن
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)

        // نمایش نوتیفیکیشن
        notificationManager.notify(0, notificationBuilder.build())
    }
}
