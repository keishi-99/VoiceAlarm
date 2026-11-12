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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import jp.personal.voicealarm.data.Alarm
import jp.personal.voicealarm.logic.NextAlarmCalculator
import jp.personal.voicealarm.util.PermissionChecks
import jp.personal.voicealarm.viewmodel.AlarmUiState
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    state: AlarmUiState,
    onAdd: () -> Unit,
    onEdit: (Alarm) -> Unit,
    onToggleEnabled: (Alarm, Boolean) -> Unit,
    onSkipNext: (Alarm) -> Unit,
    onClearSkips: (Alarm) -> Unit,
    onOpenGroups: () -> Unit,
    onOpenHolidays: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // 重要な許可が外れていたらバナーで知らせる。
    // 許可は端末側で変わるため、画面に戻ったとき（ON_RESUME）に再チェックする
    val context = LocalContext.current
    var permissionRefreshKey by remember { mutableStateOf(0) }
    DisposableEffect(context) {
        val owner = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<LifecycleOwner>().firstOrNull()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefreshKey++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val missingPermissions = remember(permissionRefreshKey, state) {
        PermissionChecks.missingCriticalPermissions(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ボイスアラーム") },
                actions = {
                    IconButton(onClick = onOpenGroups) {
                        Icon(Icons.Default.Groups, contentDescription = "グループ")
                    }
                    IconButton(onClick = onOpenHolidays) {
                        Icon(Icons.Default.DateRange, contentDescription = "休日")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "鳴動履歴")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "設定・許可")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("アラームを追加") }
            )
        }
    ) { padding ->
        if (state.alarms.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (missingPermissions.isNotEmpty()) {
                        PermissionWarningBanner(
                            missing = missingPermissions,
                            onClick = onOpenSettings
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                    Icon(
                        Icons.Default.AlarmOff,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "アラームがありません",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "右下の「アラームを追加」から作ってください。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        val groupsById = state.groups.associateBy { it.id }
        val grouped = state.alarms.groupBy { it.groupId }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (missingPermissions.isNotEmpty()) {
                item {
                    PermissionWarningBanner(
                        missing = missingPermissions,
                        onClick = onOpenSettings
                    )
                }
            }

            // 直近の次回アラームのサマリー（次回がない場合も領域を残し、レイアウトが動かないようにする）
            val nextEntry = state.nextTimes.minByOrNull { it.value }
            val nextAlarm = nextEntry?.let { e -> state.alarms.firstOrNull { it.id == e.key } }
            item {
                if (nextEntry != null && nextAlarm != null) {
                    NextAlarmSummaryCard(
                        alarm = nextAlarm,
                        nextDateTime = nextEntry.value
                    )
                } else {
                    NextAlarmSummaryEmptyCard()
                }
            }

            val orderedKeys = listOf<Long?>(null) + state.groups.map { it.id }
            for (key in orderedKeys) {
                val list = grouped[key].orEmpty()
                    .sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
                if (list.isEmpty()) continue
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = key?.let { groupsById[it]?.name } ?: "グループなし",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${list.size}件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        // このセクション内の全アラームをまとめてオン/オフする。
                        // 一部だけオンのときはスイッチはオフ表示で、押すと全てオンになる
                        val allOn = list.all { it.enabled }
                        Switch(
                            checked = allOn,
                            onCheckedChange = { target ->
                                list.forEach { a ->
                                    if (a.enabled != target) onToggleEnabled(a, target)
                                }
                            }
                        )
                    }
                }
                items(list, key = { it.id }) { alarm ->
                    AlarmRow(
                        alarm = alarm,
                        preview = state.previews[alarm.id] ?: "",
                        hasNext = alarm.id in state.nextTimes,
                        onClick = { onEdit(alarm) },
                        onToggleEnabled = { onToggleEnabled(alarm, it) },
                        onSkipNext = { onSkipNext(alarm) },
                        onClearSkips = { onClearSkips(alarm) }
                    )
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun NextAlarmSummaryCard(alarm: Alarm, nextDateTime: java.time.LocalDateTime) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("次のアラーム", style = MaterialTheme.typography.labelLarge)
                Text(
                    String.format(
                        "%d月%d日(%s) %02d:%02d",
                        nextDateTime.monthValue, nextDateTime.dayOfMonth,
                        NextAlarmCalculator.weekdayJa(nextDateTime.dayOfWeek.value),
                        nextDateTime.hour, nextDateTime.minute
                    ),
                    style = MaterialTheme.typography.titleLarge
                )
                if (alarm.label.isNotBlank()) {
                    Text(alarm.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** 次回のアラームがない場合のサマリー（カード自体は常に表示し、高さを揃える） */
@Composable
private fun NextAlarmSummaryEmptyCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AlarmOff, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("次のアラーム", style = MaterialTheme.typography.labelLarge)
                Text(
                    "次回はありません",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmRow(
    alarm: Alarm,
    preview: String,
    hasNext: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSkipNext: () -> Unit,
    onClearSkips: () -> Unit
) {
    val contentAlpha = if (alarm.enabled) 1f else 0.45f
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        // 一覧に多く表示できるよう、余白と行間は詰めたコンパクトな作りにする
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.alpha(contentAlpha)) {
                    Text(
                        String.format("%02d:%02d", alarm.hour, alarm.minute),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    val sub = listOfNotNull(
                        alarm.label.ifBlank { null },
                        repeatSummary(alarm)
                    ).joinToString(" ・ ")
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggleEnabled)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(contentAlpha)
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // スキップ状態の行は、予約の有無や次回の有無にかかわらず常に出して領域を固定する
            // （行が消えるとカードの高さが変わり、一覧のレイアウトががたつくため）
            val futureSkips = alarm.futureSkipDates()
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (futureSkips.isNotEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                if (futureSkips.isNotEmpty()) {
                    val days = futureSkips.joinToString("・") { "${it.monthValue}月${it.dayOfMonth}日" }
                    Text(
                        "${futureSkips.size}回分スキップ中（$days）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "スキップ予約なし",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 次回がない場合もボタン自体は残し、押せない状態にする（領域を残すため）
                FilledTonalButton(
                    onClick = onSkipNext,
                    enabled = alarm.enabled && hasNext,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("次回をスキップ")
                }
                TextButton(
                    onClick = onClearSkips,
                    enabled = alarm.enabled && futureSkips.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("スキップを全て取り消す")
                }
            }
        }
    }
}

/** 重要な許可が外れているときの警告バナー。タップで設定・許可画面へ。 */
@Composable
private fun PermissionWarningBanner(missing: List<String>, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "許可が外れています",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "${missing.joinToString("、")} の許可が必要です。タップして設定を確認してください。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/** 繰り返し設定の短い要約（例：平日／毎日／月・水・金／1回だけ） */
private fun repeatSummary(alarm: Alarm): String {
    val days = alarm.repeatDaySet()
    if (days.isEmpty()) {
        return alarm.specificDateEpochDay?.let {
            val d = LocalDate.ofEpochDay(it)
            "1回だけ（${d.monthValue}月${d.dayOfMonth}日）"
        } ?: "1回だけ"
    }
    return when {
        days == setOf(1, 2, 3, 4, 5) -> "平日"
        days == setOf(6, 7) -> "週末"
        days.size == 7 -> "毎日"
        else -> days.sorted().joinToString("・") { NextAlarmCalculator.weekdayJa(it) }
    }
}
