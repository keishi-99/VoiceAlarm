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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.personal.voicealarm.data.AlarmGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    groups: List<AlarmGroup>,
    onSave: (AlarmGroup) -> Unit,
    onDelete: (AlarmGroup) -> Unit,
    onBack: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("グループ管理") },
            navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("新しいグループ名（例: 仕事）") },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onSave(AlarmGroup(name = newName.trim())); newName = ""
                        }
                    }
                ) { Text("追加") }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.id }) { group ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(group.name)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("日本の祝日はオフ", Modifier.weight(1f))
                                Switch(
                                    checked = group.skipJapaneseHolidays,
                                    onCheckedChange = {
                                        onSave(group.copy(skipJapaneseHolidays = it))
                                    }
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("カスタム休日はオフ", Modifier.weight(1f))
                                Switch(
                                    checked = group.skipCustomHolidays,
                                    onCheckedChange = {
                                        onSave(group.copy(skipCustomHolidays = it))
                                    }
                                )
                            }
                            OutlinedButton(onClick = { onDelete(group) }) { Text("削除") }
                        }
                    }
                }
            }
        }
    }
}
