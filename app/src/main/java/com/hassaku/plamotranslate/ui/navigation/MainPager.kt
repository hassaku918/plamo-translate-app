package com.hassaku.plamotranslate.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.hassaku.plamotranslate.data.ocr.TextRecognitionHelper
import com.hassaku.plamotranslate.ui.screens.CameraScreen
import com.hassaku.plamotranslate.ui.screens.TranslateScreen
import com.hassaku.plamotranslate.ui.screens.TranslateViewModel
import kotlinx.coroutines.launch

enum class AppPage(val title: String) {
    TRANSLATE("翻訳"),
    CAMERA("カメラ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPager(
    textRecognitionHelper: TextRecognitionHelper
) {
    val pagerState = rememberPagerState(pageCount = { AppPage.entries.size })
    val scope = rememberCoroutineScope()
    val translateViewModel: TranslateViewModel = hiltViewModel()

    var pendingTranslateText by remember { mutableStateOf<String?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppPage.entries.forEachIndexed { index, page ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        icon = {
                            Icon(
                                imageVector = when (page) {
                                    AppPage.TRANSLATE -> Icons.Default.Translate
                                    AppPage.CAMERA -> Icons.Default.CameraAlt
                                },
                                contentDescription = page.title
                            )
                        },
                        label = { Text(page.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // Keep off-screen pages out of composition so CameraX fully releases
            beyondViewportPageCount = 0
        ) { page ->
            when (AppPage.entries[page]) {
                AppPage.TRANSLATE -> {
                    LaunchedEffect(pendingTranslateText) {
                        pendingTranslateText?.let { text ->
                            translateViewModel.setInputText(text)
                            pendingTranslateText = null
                        }
                    }
                    TranslateScreen(viewModel = translateViewModel)
                }
                AppPage.CAMERA -> {
                    CameraScreen(
                        textRecognitionHelper = textRecognitionHelper,
                        onTextSelected = { text ->
                            pendingTranslateText = text
                            scope.launch {
                                pagerState.animateScrollToPage(AppPage.TRANSLATE.ordinal)
                            }
                        }
                    )
                }
            }
        }
    }
}
