package com.ljyh.mei.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ljyh.mei.playback.AaBrowseDiagnostics
import com.ljyh.mei.ui.theme.MusicTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug-only: view persisted Android Auto browse/connect diagnostics without adb. */
class AaBrowseDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicTheme(seedColor = Color(0xFF0088FF), isDark = true) {
                var entries by remember {
                    mutableStateOf(AaBrowseDiagnostics.recorder.snapshot())
                }
                val fmt = remember {
                    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF111111))
                        .statusBarsPadding()
                        .padding(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "AA 诊断",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                        )
                        Button(
                            onClick = {
                                AaBrowseDiagnostics.recorder.clear()
                                entries = emptyList()
                            },
                        ) {
                            Text("清空")
                        }
                    }
                    Text(
                        "最近 ${AaBrowseDiagnostics.CAPACITY} 条 browse/connect（进程死后仍保留）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Button(
                        onClick = { entries = AaBrowseDiagnostics.recorder.snapshot() },
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Text("刷新")
                    }
                    if (entries.isEmpty()) {
                        Text("暂无记录。用车机打开 MeiloX 后再刷新。", color = Color(0xFF888888))
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(entries, key = { "${it.timestampMs}-${it.method}-${it.parentId}" }) { e ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1C1C1E))
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        "${fmt.format(Date(e.timestampMs))}  ${e.method}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        "pkg=${e.packageName} uid=${e.uid}\n" +
                                            "trusted=${e.isTrusted} ver=${e.controllerVersion}" +
                                            " legacy=${e.isLegacy}\n" +
                                            "parentId=${e.parentId ?: "-"}\n" +
                                            "result=${e.result}",
                                        color = Color(0xFFCCCCCC),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
