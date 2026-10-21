package com.example.vehicletracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.vehicletracker.databinding.ActivityOwnerBinding
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

class OwnerActivity : AppCompatActivity() {

    private lateinit var b: ActivityOwnerBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private var listener: ListenerRegistration? = null
    private var vehicle: Vehicle? = null
    private var vehicleLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOwnerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.loginButton.setOnClickListener { login(create = false) }
        b.registerButton.setOnClickListener { login(create = true) }
        b.signOutButton.setOnClickListener { auth.signOut(); showScreen() }
        b.createVehicleButton.setOnClickListener { createVehicle() }
        b.pairCodeButton.setOnClickListener { createPairingCode() }
        b.unpairButton.setOnClickListener { unpair() }
        b.mapButton.setOnClickListener { openMap() }

        // প্রতি ১৫ সেকেন্ডে "তাজা/পুরোনো" স্ট্যাটাস আপডেট (ডেটা না বদলালেও সময় বাড়ে)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    render()
                    delay(15_000)
                }
            }
        }
        showScreen()
    }

    override fun onDestroy() {
        listener?.remove()
        super.onDestroy()
    }

    private fun showScreen() {
        val loggedIn = auth.currentUser != null
        b.loginGroup.visibility = if (loggedIn) View.GONE else View.VISIBLE
        b.dashboardGroup.visibility = if (loggedIn) View.VISIBLE else View.GONE

        if (loggedIn && listener == null) {
            attachVehicleListener()
        } else if (!loggedIn) {
            listener?.remove()
            listener = null
            vehicle = null
            vehicleLoaded = false
        }
        render()
    }

    private fun login(create: Boolean) {
        val email = b.emailInput.text.toString().trim()
        val pass = b.passwordInput.text.toString()
        if (email.isEmpty() || pass.length < 6) {
            toast("ইমেইল এবং ৬+ অক্ষরের পাসওয়ার্ড দিন")
            return
        }
        lifecycleScope.launch {
            try {
                if (create) auth.createUserWithEmailAndPassword(email, pass).await()
                else auth.signInWithEmailAndPassword(email, pass).await()
                showScreen()
            } catch (e: Exception) {
                toast("ব্যর্থ: ${e.message}")
            }
        }
    }

    // শুধু নিজের গাড়ির ডেটা শোনা হয় (Firestore rules এ এটাই বাধ্যতামূলক)
    private fun attachVehicleListener() {
        val uid = auth.currentUser?.uid ?: return
        listener = db.collection(COL_VEHICLES)
            .whereEqualTo("ownerUid", uid)
            .limit(1)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    toast("ডেটা লোড হয়নি: ${err.message}")
                    return@addSnapshotListener
                }
                vehicleLoaded = true
                vehicle = snap?.documents?.firstOrNull()?.let { Vehicle.from(it) }
                render()
            }
    }

    private fun render() {
        val v = vehicle
        b.createVehicleButton.visibility =
            if (vehicleLoaded && v == null) View.VISIBLE else View.GONE
        b.vehicleGroup.visibility = if (v != null) View.VISIBLE else View.GONE
        if (v == null) return

        b.trackerStatus.text =
            if (v.hasTracker) "ট্র্যাকার: জোড়া লাগানো আছে" else "ট্র্যাকার: জোড়া লাগানো নেই"
        b.unpairButton.visibility = if (v.hasTracker) View.VISIBLE else View.GONE
        b.pairCodeButton.visibility = if (v.hasTracker) View.GONE else View.VISIBLE

        if (v.lat != null && v.lng != null) {
            b.locationText.text = "লোকেশন: %.6f, %.6f\nনির্ভুলতা: ±%.0f মিটার\nগতি: %.1f কিমি/ঘণ্টা\n%s"
                .format(v.lat, v.lng, v.accuracy ?: 0.0, (v.speedMs ?: 0.0) * 3.6, ageText(v.lastUpdate))
        } else {
            b.locationText.text = "এখনো কোনো লোকেশন আসেনি"
        }
        b.mapButton.isEnabled = v.lat != null
    }

    private fun createVehicle() {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                db.collection(COL_VEHICLES).add(
                    mapOf(
                        "ownerUid" to uid,
                        "trackerUid" to null,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                toast("গাড়ি যোগ হয়েছে")
            } catch (e: Exception) {
                toast("ব্যর্থ: ${e.message}")
            }
        }
    }

    // নতুন ট্র্যাকার ফোনের জন্য কোড: ১০ মিনিটের মধ্যে, একবারই ব্যবহারযোগ্য
    private fun createPairingCode() {
        val v = vehicle ?: return
        val uid = auth.currentUser?.uid ?: return
        val code = newPairingCode()
        val expires = Timestamp(Date(System.currentTimeMillis() + PAIRING_MINUTES * 60_000))

        lifecycleScope.launch {
            try {
                db.collection(COL_PAIRING).document(code).set(
                    mapOf(
                        "vehicleId" to v.id,
                        "ownerUid" to uid,
                        "used" to false,
                        "expiresAt" to expires
                    )
                ).await()
                b.pairCodeText.text =
                    "নতুন ট্র্যাকার কোড: $code\n${PAIRING_MINUTES} মিনিটের মধ্যে ট্র্যাকার ফোনে দিন"
            } catch (e: Exception) {
                toast("ব্যর্থ: ${e.message}")
            }
        }
    }

    // আনপেয়ার করলে পুরোনো ফোনের লেখার অনুমতি বাতিল হয়ে যায় (Firestore rules দিয়ে)
    private fun unpair() {
        val v = vehicle ?: return
        lifecycleScope.launch {
            try {
                db.collection(COL_VEHICLES).document(v.id)
                    .update(mapOf("trackerUid" to null, "claimCode" to null))
                    .await()
                b.pairCodeText.text = ""
                toast("ট্র্যাকার আনপেয়ার হয়েছে। পুরোনো ফোন আর লোকেশন পাঠাতে পারবে না।")
            } catch (e: Exception) {
                toast("ব্যর্থ: ${e.message}")
            }
        }
    }

    private fun openMap() {
        val v = vehicle ?: return
        val lat = v.lat ?: return
        val lng = v.lng ?: return
        // ম্যাপ অ্যাপ খুলবে (Google Maps SDK ছাড়াই, তাই API key লাগে না)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")))
    }
}
