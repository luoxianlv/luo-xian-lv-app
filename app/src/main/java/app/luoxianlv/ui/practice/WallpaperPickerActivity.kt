package app.luoxianlv.ui.practice

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.luoxianlv.data.AppearanceStore
import app.luoxianlv.ui.theme.LuoXianLvTheme
import app.luoxianlv.ui.wallpaper.WallpaperPickerScreen

/**
 * Portrait library of original project previews; selection applies before the next stage starts.
 */
class WallpaperPickerActivity : AppCompatActivity() {
    private fun returnFromPicker() {
        if (intent.getBooleanExtra("returnToPractice", false))
            startActivity(Intent(this, PracticeActivity::class.java))
        finish()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    returnFromPicker()
                }
            },
        )
        setContent {
            val appearance by AppearanceStore.settings.collectAsState()
            LuoXianLvTheme(darkTheme = appearance.themeMode.isDark(isSystemInDarkTheme())) {
                WallpaperPickerScreen(
                    onBack = ::returnFromPicker,
                    onEnterPractice = {
                        startActivity(Intent(this, PracticeActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }
}
