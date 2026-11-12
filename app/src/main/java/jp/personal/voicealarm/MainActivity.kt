package jp.personal.voicealarm

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.personal.voicealarm.data.Alarm
import jp.personal.voicealarm.ui.AlarmEditScreen
import jp.personal.voicealarm.ui.AlarmListScreen
import jp.personal.voicealarm.ui.GroupScreen
import jp.personal.voicealarm.ui.HistoryScreen
import jp.personal.voicealarm.ui.HolidayScreen
import jp.personal.voicealarm.ui.SettingsScreen
import jp.personal.voicealarm.ui.theme.VoiceAlarmTheme
import jp.personal.voicealarm.viewmodel.AlarmViewModel

private sealed interface Screen {
    data object ListScreen : Screen
    data class Edit(val alarm: Alarm?) : Screen
    data object Groups : Screen
    data object Holidays : Screen
    data object History : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            VoiceAlarmTheme {
                Surface {
                    VoiceAlarmApp()
                }
            }
        }
    }
}

@Composable
fun VoiceAlarmApp(vm: AlarmViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.ListScreen) }

    // 端末の戻るボタン：トップ（一覧）以外の画面ではアプリを閉じずトップへ戻る。
    // 編集画面で編集中の内容は画面上の「戻る」と同じく破棄される。
    BackHandler(enabled = screen !is Screen.ListScreen) {
        screen = Screen.ListScreen
    }

    when (val s = screen) {
        is Screen.ListScreen -> AlarmListScreen(
            state = state,
            onAdd = { screen = Screen.Edit(null) },
            onEdit = { screen = Screen.Edit(it) },
            onToggleEnabled = { alarm, enabled -> vm.setEnabled(alarm, enabled) },
            onSkipNext = { vm.skipNextOccurrence(it) },
            onClearSkips = { vm.clearFutureSkips(it) },
            onOpenGroups = { screen = Screen.Groups },
            onOpenHolidays = { screen = Screen.Holidays },
            onOpenHistory = { screen = Screen.History },
            onOpenSettings = { screen = Screen.Settings }
        )
        is Screen.Edit -> {
            // 既存アラームは DB の最新状態を優先する（編集開始時点のスナップショットでも可）
            AlarmEditScreen(
                initial = s.alarm,
                groups = state.groups,
                onSave = { vm.saveAlarm(it); screen = Screen.ListScreen },
                onDelete = { vm.deleteAlarm(it); screen = Screen.ListScreen },
                onDuplicate = { vm.saveAlarm(it); screen = Screen.ListScreen },
                onBack = { screen = Screen.ListScreen }
            )
        }
        is Screen.Groups -> GroupScreen(
            groups = state.groups,
            onSave = { vm.saveGroup(it) },
            onDelete = { vm.deleteGroup(it) },
            onBack = { screen = Screen.ListScreen }
        )
        is Screen.Holidays -> HolidayScreen(
            customHolidays = state.holidays,
            onAdd = { date, memo -> vm.addCustomHoliday(date, memo) },
            onRemove = { vm.removeCustomHoliday(it) },
            onBack = { screen = Screen.ListScreen }
        )
        is Screen.History -> HistoryScreen(onBack = { screen = Screen.ListScreen })
        is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.ListScreen })
    }
}
