package com.example.vehicletracker

import android.content.Context
import android.widget.Toast
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.security.SecureRandom

// Firestore এর collection নাম (ভুল হলে ডেটা খুঁজে পাওয়া যাবে না, তাই এক জায়গায় রাখা)
const val COL_VEHICLES = "vehicles"
const val COL_PAIRING = "pairingCodes"

// ট্র্যাকার ফোনে নিজের তথ্য মনে রাখার জায়গা
const val PREFS = "tracker_prefs"
const val KEY_VEHICLE_ID = "vehicle_id"

const val PAIRING_MINUTES = 10L                 // পেয়ারিং কোডের মেয়াদ
const val STALE_MS = 2 * 60 * 1000L             // ২ মিনিটের বেশি পুরোনো লোকেশন = "পুরোনো"

// গাড়ির তথ্য (Firestore থেকে আসা ডেটা এই আকারে রাখি)
data class Vehicle(
    val id: String,
    val trackerUid: String?,
    val lat: Double?,
    val lng: Double?,
    val accuracy: Double?,
    val speedMs: Double?,
    val lastUpdate: Timestamp?
) {
    val hasTracker get() = trackerUid != null

    companion object {
        fun from(doc: DocumentSnapshot) = Vehicle(
            id = doc.id,
            trackerUid = doc.getString("trackerUid"),
            lat = doc.getDouble("lat"),
            lng = doc.getDouble("lng"),
            accuracy = doc.getDouble("accuracy"),
            speedMs = doc.getDouble("speed"),
            lastUpdate = doc.getTimestamp("lastUpdate")
        )
    }
}

// নিরাপদ র‍্যান্ডম কোড (SecureRandom): সহজে অনুমান করা যায় না
private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  // 0,O,1,I বাদ: পড়তে সহজ
private val random = SecureRandom()

fun newPairingCode(): String =
    (1..8).map { CODE_CHARS[random.nextInt(CODE_CHARS.length)] }.joinToString("")

// লোকেশন কতটা তাজা তা দেখায়
fun ageText(ts: Timestamp?): String {
    if (ts == null) return "এখনো কোনো আপডেট আসেনি"
    val ageMs = System.currentTimeMillis() - ts.toDate().time
    val minutes = ageMs / 60_000
    val label = if (ageMs <= STALE_MS) "✅ তাজা" else "⚠️ পুরোনো (stale)"
    return "$label, $minutes মিনিট আগে"
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
