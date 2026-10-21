package com.example.vehicletracker

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // দুটো বোতাম: কোন ফোনে এই অ্যাপ চলছে সেটা ঠিক করে দেয়
        findViewById<Button>(R.id.trackerModeButton).setOnClickListener {
            startActivity(Intent(this, TrackerActivity::class.java))
        }
        findViewById<Button>(R.id.ownerModeButton).setOnClickListener {
            startActivity(Intent(this, OwnerActivity::class.java))
        }
    }
}
