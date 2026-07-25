package com.ricardo.txtreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ricardo.txtreader.navigation.TxtReaderApp
import com.ricardo.txtreader.ui.theme.TxtReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TxtReaderTheme {
                TxtReaderApp()
            }
        }
    }
}
