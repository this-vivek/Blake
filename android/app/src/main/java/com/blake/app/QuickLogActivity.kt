package com.blake.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blake.app.data.BlakeDatabase
import com.blake.app.data.TodayCacheEntity
import com.blake.app.ui.theme.BlakeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Log anything screen on its own, opened by the "Log anything" home-screen widget. It is a
 * transparent window holding only the sheet, so there is no landing page: you log, close the
 * sheet, and you are back on the home screen.
 */
class QuickLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlakeTheme {
                val vm: TodayViewModel = viewModel()
                LogSheet(vm = vm, onDismiss = { finish() })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Whatever was logged should show on the Today widget without opening the app.
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Prefs.apiFor(app).today().getOrNull()?.let { fresh ->
                    BlakeDatabase.get(app).todayDao().upsert(TodayCacheEntity(json = fresh, updatedAt = System.currentTimeMillis()))
                }
            } catch (e: Exception) {
                // offline: the widget keeps its last numbers
            }
            WidgetRefresh.update(app)
        }
    }
}
