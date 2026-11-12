package jp.personal.voicealarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import jp.personal.voicealarm.data.AppDatabase
import jp.personal.voicealarm.data.RingHistory
import jp.personal.voicealarm.logic.NextAlarmCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 鳴動履歴画面。新しい順に最大200件。
 * 「いつ・どのアラームが鳴って、どう終わったか（停止/スヌーズ/自動停止）」を確認する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<RingHistory>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        entries = withContext(Dispatchers.IO) {
            AppDatabase.getInstance(context).ringHistoryDao().getRecent(200)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("鳴動履歴") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AppDatabase.getInstance(context).ringHistoryDao().deleteAll()
                                }
                                reloadKey++
                            }
                        },
                        enabled = entries.isNotEmpty()
                    ) { Text("全て消す") }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "まだ履歴がありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                formatHistoryTime(entry.eventTimeMillis),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val sub = listOfNotNull(
                                entry.alarmTime.takeIf { it.isNotBlank() },
                                entry.label.takeIf { it.isNotBlank() }
                            ).joinToString(" ")
                            if (sub.isNotBlank()) {
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            entry.event,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (entry.event.startsWith("鳴動") ||
                                entry.event.startsWith("スヌーズで")
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** 履歴の日時表示。例：11月12日(火) 7:00 */
private fun formatHistoryTime(millis: Long): String {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    return String.format(
        "%d月%d日(%s) %d:%02d",
        dt.monthValue, dt.dayOfMonth,
        NextAlarmCalculator.weekdayJa(dt.dayOfWeek.value),
        dt.hour, dt.minute
    )
}
