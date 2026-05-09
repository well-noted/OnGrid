package com.ongrid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ongrid.app.navigation.AppNavigation
import com.ongrid.app.ui.theme.OnGridTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OnGridTheme {
                AppNavigation()
            }
        }
    }
}
