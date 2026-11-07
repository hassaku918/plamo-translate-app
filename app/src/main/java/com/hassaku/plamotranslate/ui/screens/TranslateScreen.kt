package com.hassaku.plamotranslate.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hassaku.plamotranslate.data.inference.Style
import com.hassaku.plamotranslate.data.model.ModelStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(
    viewModel: TranslateViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PLaMo Translate") },
                actions = {
                    Text(
                        text = if (state.isModelInMemory) "RAM使用中" else state.engineName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.isModelInMemory)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModelStatusCard(
                status = state.modelStatus,
                progress = state.downloadProgress,
                isInMemory = state.isModelInMemory,
                isLoadingModel = state.isLoadingModel,
                onDownload = { viewModel.downloadModel() },
                onLoad = { viewModel.loadModel() },
                onUnload = { viewModel.unloadModel() },
                onDelete = { viewModel.deleteModel() }
            )

            state.statusText?.let { msg ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(msg, style = MaterialTheme.typography.bodyMedium)
            }

            state.error?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(state.sourceLang, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { viewModel.swapLanguages() }) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Swap")
                }
                Text(state.targetLang, style = MaterialTheme.typography.titleMedium)
            }

            if (state.targetLang.equals("Japanese", ignoreCase = true)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.style == Style.PLAIN,
                        onClick = { viewModel.setStyle(Style.PLAIN) },
                        label = { Text("常体") }
                    )
                    FilterChip(
                        selected = state.style == Style.POLITE,
                        onClick = { viewModel.setStyle(Style.POLITE) },
                        label = { Text("敬体") }
                    )
                }
            }

            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                label = { Text("原文") },
                placeholder = { Text("短文の方が速いです") },
                maxLines = 8,
                supportingText = {
                    Text("${state.input.length} / ${com.hassaku.plamotranslate.data.inference.PlamoLlamaEngine.MAX_INPUT_CHARS}")
                }
            )

            Button(
                onClick = { viewModel.translate() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isTranslating && state.input.isNotBlank() && !state.isLoadingModel
            ) {
                when {
                    state.isLoadingModel -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("モデル読み込み中...")
                    }
                    state.isTranslating -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("推論中...")
                    }
                    else -> {
                        val label = when {
                            state.isModelInMemory -> "本推論で翻訳"
                            state.modelStatus is ModelStatus.Ready -> "ロードして翻訳"
                            else -> "翻訳 (モック)"
                        }
                        Text(label)
                    }
                }
            }

            if (state.output.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("訳文", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(state.output)
                        Spacer(Modifier.height(8.dp))
                        val clipboard = LocalClipboardManager.current
                        TextButton(onClick = { clipboard.setText(AnnotatedString(state.output)) }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("コピー")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    status: ModelStatus,
    progress: Float,
    isInMemory: Boolean,
    isLoadingModel: Boolean,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("モデル状態", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(
                "アプリ使用中はモデルを保持（2分無操作で解放）。バックグラウンドで即解放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when {
                isLoadingModel || status is ModelStatus.Loading -> {
                    Text("モデルをメモリに読み込み中…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                status is ModelStatus.NotDownloaded -> {
                    Text("PLaMo 3 0.8B 未ダウンロード")
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("モデルをダウンロード")
                    }
                }
                status is ModelStatus.Downloading -> {
                    Text("ダウンロード中... ${(progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                status is ModelStatus.Ready || isInMemory -> {
                    if (isInMemory) {
                        Text("RAMに載っています（2回目以降は速い）",
                            color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = onUnload, modifier = Modifier.fillMaxWidth()) {
                            Text("今すぐメモリから解放")
                        }
                    } else {
                        Text("ディスク上に保存済み（RAM未使用）")
                        OutlinedButton(onClick = onLoad, modifier = Modifier.fillMaxWidth()) {
                            Text("先にロードしておく")
                        }
                    }
                    TextButton(onClick = onDelete) {
                        Text("モデルを削除")
                    }
                }
                status is ModelStatus.Corrupted -> {
                    Text("モデルファイルが破損しています。")
                    Button(onClick = onDownload) { Text("再ダウンロード") }
                }
                status is ModelStatus.Error -> {
                    Text("エラー: ${status.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDownload) { Text("再試行") }
                }
            }
        }
    }
}
