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
                        text = state.engineName,
                        style = MaterialTheme.typography.labelSmall,
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
                isReady = state.isModelReady,
                isLoadingModel = state.isLoadingModel,
                onDownload = { viewModel.downloadModel() },
                onLoad = { viewModel.loadModel() }
            )

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
                placeholder = { Text("翻訳したいテキストを入力...") },
                maxLines = 8,
                supportingText = { Text("${state.input.length} / 4000") }
            )

            Button(
                onClick = { viewModel.translate() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isTranslating && state.input.isNotBlank() && !state.isLoadingModel
            ) {
                if (state.isTranslating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("翻訳中...")
                } else {
                    Text(if (state.isModelReady) "本推論で翻訳" else "翻訳 (モック)")
                }
            }

            if (state.output.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
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
    isReady: Boolean,
    isLoadingModel: Boolean,
    onDownload: () -> Unit,
    onLoad: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("モデル状態", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            when {
                isLoadingModel || status is ModelStatus.Loading -> {
                    Text("モデルをメモリに読み込み中…（数十秒かかることがあります）")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                status is ModelStatus.NotDownloaded -> {
                    Text("PLaMo 3 0.8B 未ダウンロード")
                    Text("Wi‑Fi推奨・空き容量800MB以上")
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("モデルをダウンロード (~0.5–0.6GB)")
                    }
                }
                status is ModelStatus.Downloading -> {
                    Text("ダウンロード中... ${(progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                status is ModelStatus.Ready -> {
                    Text("ファイル準備完了")
                    if (!isReady) {
                        Button(onClick = onLoad, modifier = Modifier.fillMaxWidth()) {
                            Text("本推論エンジンをロード")
                        }
                    } else {
                        Text("✓ 本推論準備OK", color = MaterialTheme.colorScheme.primary)
                    }
                }
                status is ModelStatus.Corrupted -> {
                    Text("モデルファイルが破損しています。再ダウンロードしてください。")
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
