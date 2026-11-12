package jp.personal.voicealarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.personal.voicealarm.data.CustomHoliday
import jp.personal.voicealarm.logic.HolidayCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HolidayScreen(
    customHolidays: List<CustomHoliday>,
    onAdd: (LocalDate, String) -> Unit,
    onRemove: (LocalDate) -> Unit,
    onBack: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var memo by remember { mutableStateOf("") }
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    val japaneseThisMonth = remember(currentMonth) {
        HolidayCalculator.holidaysOfMonth(currentMonth)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("休日設定") },
            navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = memo, onValueChange = { memo = it },
                label = { Text("メモ（例: 有給・会社休み）") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { showPicker = true }) { Text("カスタム休日を追加（日付を選ぶ）") }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) { Text("← 前月") }
                Text(
                    "${currentMonth.year}年${currentMonth.monthValue}月の日本の祝日",
                    style = MaterialTheme.typography.titleSmall
                )
                OutlinedButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) { Text("翌月 →") }
            }
            if (japaneseThisMonth.isEmpty()) {
                Text("この月に日本の祝日はありません。")
            } else {
                for ((date, name) in japaneseThisMonth.toSortedMap()) {
                    Text("${date.monthValue}月${date.dayOfMonth}日: $name")
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("登録済みカスタム休日", style = MaterialTheme.typography.titleSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(customHolidays, key = { it.epochDay }) { holiday ->
                    val date = LocalDate.ofEpochDay(holiday.epochDay)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${date.year}年${date.monthValue}月${date.dayOfMonth}日 ${holiday.memo}")
                        OutlinedButton(onClick = { onRemove(date) }) { Text("削除") }
                    }
                }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        val date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        onAdd(date, memo.trim())
                        memo = ""
                    }
                    showPicker = false
                }) { Text("追加") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("キャンセル") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
