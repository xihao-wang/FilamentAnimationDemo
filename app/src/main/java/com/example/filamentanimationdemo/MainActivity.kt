package com.example.filamentanimationdemo

import android.os.Bundle
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.filamentanimationdemo.render.FilamentRenderer
import com.example.filamentanimationdemo.ui.theme.FilamentAnimationDemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FilamentAnimationDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CharacterViewer(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun CharacterViewer(modifier: Modifier = Modifier) {
    var renderer by remember { mutableStateOf<FilamentRenderer?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, renderer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> renderer?.start()
                Lifecycle.Event.ON_PAUSE -> renderer?.stop()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            renderer?.start()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            renderer?.stop()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).also { surfaceView ->
                    renderer = FilamentRenderer(
                        context = context,
                        surfaceView = surfaceView,
                        onError = { errorMessage = it }
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = {
                renderer?.stop()
                renderer = null
            }
        )

        errorMessage?.let { message ->
            Text(text = message)
        }
    }
}
