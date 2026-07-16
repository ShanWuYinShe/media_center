package com.example.mediacenter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.mediacenter.ui.AppNavigation
import com.example.mediacenter.ui.theme.MediaCenterTheme

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaCenterTheme {
                AppNavigation()
            }
        }
    }
}
