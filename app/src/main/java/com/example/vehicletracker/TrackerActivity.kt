package com.example.vehicletracker

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.vehicletracker.databinding.ActivityTrackerBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackerActivity : AppCompatActivity() {

    private lateinit var b: ActivityTrackerBinding
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // পারমিশন চাওয়ার উত্তর এখানে আসে
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startTracking()
        else toast("লোকেশন অনুমতি ছাড়া ট্র্যাকিং চালু হবে না")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTrackerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.pairButton.setOnClickListener { pairWithCode() }

        b.startButton.setOnClickListener {
            // অনুমতি চাওয়ার আগে কেন লাগবে তা বুঝিয়ে বলা (Play Store ও ব্যবহারকারীর জন্য জরুরি)
            AlertDialog.Builder(this)
                .setTitle("লোকেশন অনুমতি")
                .setMessage("এই অ্যাপ গাড়ির লোকেশন পাঠাতে GPS ব্যবহার করবে। ট্র্যাকিং চললে একটি নোটিফিকেশন দেখাবে। যেকোনো সময় বন্ধ করতে পারবেন।")
                .setPositiveButton("ঠিক আছে") { _, _ -> permissionLauncher.launch(permissionsNeeded()) }
                .setNegativeButton("বাতিল", null)
                .show()
        }

        b.stopButton.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            toast("ট্র্যাকিং বন্ধ হয়েছে")
        }

        // স্ক্রিন খোলা থাকলে প্রতি ৫ সেকেন্ডে স্ট্যাটাস আপডেট হয়
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    render()
                    delay(5_000)
                }
            }
        }
    }

    private fun render() {
        val paired = prefs.getString(KEY_VEHICLE_ID, null) != null
        b.pairGroup.visibility = if (paired) View.GONE else View.VISIBLE
        b.trackGroup.visibility = if (paired) View.VISIBLE else View.GONE

        val lastFix = prefs.getLong("last_fix_ms", 0L)
        val lastSent = prefs.getLong("last_sent_ms", 0L)
        b.statusText.text = "সর্বশেষ GPS লোকেশন: ${timeText(lastFix)}\n" +
                "সর্বশেষ সার্ভারে পাঠানো: ${timeText(lastSent)}"
    }

    private fun timeText(ms: Long): String =
        if (ms == 0L) "এখনো নেই"
        else SimpleDateFormat("dd MMM HH:mm:ss", Locale.ENGLISH).format(Date(ms))

    // মালিকের দেওয়া কোড দিয়ে এই ফোনকে গাড়ির সাথে জোড়া লাগানো
    private fun pairWithCode() {
        val code = b.codeInput.text.toString().trim().uppercase()
        if (code.length != 8) {
            toast("৮ অক্ষরের কোড দিন")
            return
        }

        lifecycleScope.launch {
            try {
                b.pairButton.isEnabled = false

                // প্রতিটি ফোনের নিজস্ব ID দরকার; Anonymous login এ তা তৈরি হয়
                if (auth.currentUser == null) auth.signInAnonymously().await()
                val uid = auth.currentUser!!.uid

                val pairRef = db.collection(COL_PAIRING).document(code)
                val pairSnap = pairRef.get().await()
                val vehicleId = pairSnap.getString("vehicleId")
                    ?: throw IllegalStateException("কোডটি পাওয়া যায়নি")

                val expires = pairSnap.getTimestamp("expiresAt")
                if (expires == null || expires.toDate().before(Date())) {
                    throw IllegalStateException("কোডের মেয়াদ শেষ")
                }

                val vehicleRef = db.collection(COL_VEHICLES).document(vehicleId)

                // Transaction: দুটি লেখা একসাথে হবে, নাহলে কিছুই হবে না
                db.runTransaction { tx ->
                    val pair = tx.get(pairRef)
                    if (pair.getBoolean("used") != false) {
                        throw IllegalStateException("কোডটি আগেই ব্যবহার হয়েছে")
                    }
                    // গাড়িতে ট্র্যাকার যুক্ত করা + কোডটি "ব্যবহৃত" চিহ্নিত করা
                    tx.update(vehicleRef, mapOf("trackerUid" to uid, "claimCode" to code))
                    tx.update(pairRef, "used", true)
                }.await()

                prefs.edit().putString(KEY_VEHICLE_ID, vehicleId).apply()
                toast("পেয়ারিং সফল হয়েছে")
            } catch (e: Exception) {
                toast("ব্যর্থ: ${e.message}")
            } finally {
                b.pairButton.isEnabled = true
            }
        }
    }

    private fun permissionsNeeded(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun startTracking() {
        // Foreground service চালু: এটাই ব্যাকগ্রাউন্ডে GPS চালিয়ে রাখে
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
        toast("ট্র্যাকিং চালু হয়েছে")
    }
}
