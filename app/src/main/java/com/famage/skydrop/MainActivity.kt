package com.famage.skydrop

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.famage.skydrop.ui.SendScreen
import com.famage.skydrop.ui.SendViewModel
import com.famage.skydrop.ui.theme.SkyDropTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SendViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashScreenView ->
                splashScreenView.remove()
            }
        }

        viewModel.toggleReceiver(true)
        handleIntent(intent)

        setContent {
            SkyDropTheme {
                var showSplash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    showSplash = false
                }

                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                    SendScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSplash,
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(550, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                        ) + androidx.compose.animation.scaleOut(
                            targetScale = 1.05f,
                            animationSpec = androidx.compose.animation.core.tween(550, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                        )
                    ) {
                        com.famage.skydrop.ui.components.SplashScreenOverlay(
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    viewModel.setSharedText(text)
                }

                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }

                if (uri != null) {
                    viewModel.setSharedUris(listOf(uri))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }

                if (!uris.isNullOrEmpty()) {
                    viewModel.setSharedUris(uris)
                }
            }
        }
    }
}