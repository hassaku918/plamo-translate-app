package com.hassaku.plamotranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hassaku.plamotranslate.data.ocr.TextRecognitionHelper
import com.hassaku.plamotranslate.ui.navigation.MainPager
import com.hassaku.plamotranslate.ui.screens.TranslateViewModel
import com.hassaku.plamotranslate.ui.theme.PLaMoTranslateTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var textRecognitionHelper: TextRecognitionHelper

    // Same activity-scoped VM as TranslateScreen (hiltViewModel) — used to free RAM on background.
    private val translateViewModel: TranslateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PLaMoTranslateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainPager(textRecognitionHelper = textRecognitionHelper)
                }
            }
        }
    }

    override fun onStop() {
        // Leaving foreground → drop native model weights so we do not hold ~400MB+ forever.
        translateViewModel.onAppBackgrounded()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            runCatching { textRecognitionHelper.close() }
            translateViewModel.unloadModel()
        }
        super.onDestroy()
    }
}
