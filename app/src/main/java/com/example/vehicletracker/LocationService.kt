package com.example.vehicletracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import android.annotation.SuppressLint

class LocationService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private var vehicleId: String? = null

    // GPS নতুন লোকেশন দিলে এখানে আসে
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val id = vehicleId ?: return
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            prefs.edit().putLong("last_fix_ms", System.currentTimeMillis()).apply()

            FirebaseFirestore.getInstance()
                .collection(COL_VEHICLES)
                .document(id)
                .update(
                    mapOf(
                        "lat" to loc.latitude,
                        "lng" to loc.longitude,
                        "accuracy" to loc.accuracy.toDouble(),
                        "speed" to (if (loc.hasSpeed()) loc.speed.toDouble() else null),
                        // সময় সার্ভার থেকে নেওয়া হয়: ফোনের ঘড়ি ভুল হলেও সমস্যা হবে না
                        "lastUpdate" to FieldValue.serverTimestamp()
                    )
                )
                .addOnSuccessListener {
                    // সার্ভার নিশ্চিত করলে তবেই "পাঠানো হয়েছে" ধরা হয়
                    prefs.edit().putLong("last_sent_ms", System.currentTimeMillis()).apply()
                }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)

        // Android 8+ এ নোটিফিকেশন চ্যানেল লাগে
        val channel = NotificationChannel(
            CHANNEL_ID, "গাড়ি ট্র্যাকিং", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // নিয়ম: startForegroundService() এর পর দ্রুত startForeground() ডাকতেই হবে
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        vehicleId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_VEHICLE_ID, null)
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // পেয়ারিং বা পারমিশন না থাকলে নিজেই বন্ধ হবে
        if (vehicleId == null || !hasPermission) {
            stopSelf()
            return START_NOT_STICKY
        }

        // প্রতি ৩০ সেকেন্ডে লোকেশন (কমপক্ষে ১৫ সেকেন্ড পর পর)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .build()

        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        return START_STICKY  // সিস্টেম বন্ধ করলে আবার চালু হবে
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("গাড়ি ট্র্যাকিং চালু আছে")
            .setContentText("লোকেশন পাঠানো হচ্ছে। বন্ধ করতে অ্যাপ খুলুন।")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "tracking"
        const val NOTIF_ID = 1
    }
}
