package com.poslik.pos.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.os.Bundle

class CaisseActivity : ComponentActivity() {

    private lateinit var environment: CaisseEnvironment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        environment = CaisseEnvironment(applicationContext)
        setContent {
            CaisseScreen(environment)
        }
    }

    override fun onDestroy() {
        environment.close()
        super.onDestroy()
    }
}