package app.luoxianlv.wallpaper

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import app.luoxianlv.business.ui.ComposePage
import app.luoxianlv.settings.AppearanceStore
import app.luoxianlv.ui.theme.LuoXianLvTheme

/** 壁纸预览页面可替换；关闭后回到来源页面，或重新进入演练场。 */
class WallpaperPickerPage : ComposePage() {
    private var returnToPractice = false

    override fun prepare(state: Bundle) {
        returnToPractice =
            state.getBoolean(
                "returnToPractice",
                activity.intent.getBooleanExtra("returnToPractice", false),
            )
    }

    override fun savePageState() =
        Bundle().apply { putBoolean("returnToPractice", returnToPractice) }

    private fun enterPractice() {
        host.open(
            Intent()
                .setClassName(
                    pageContext.packageName,
                    "app.luoxianlv.ui.practice.PracticeActivity",
                ),
            true,
        )
    }

    private fun returnFromPicker() {
        if (returnToPractice) enterPractice() else host.closePage()
    }

    @Composable
    override fun Content() {
        val appearance by AppearanceStore.settings.collectAsState()
        LuoXianLvTheme(
            darkTheme = appearance.themeMode.isDark(isSystemInDarkTheme()),
            applySystemBars = isActive,
        ) {
            BackHandler(onBack = ::returnFromPicker)
            WallpaperPickerScreen(onBack = ::returnFromPicker, onEnterPractice = ::enterPractice)
        }
    }
}
