package com.amurcanov.tgwsproxy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.fragment.app.Fragment
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.ui.FloatingToolbar
import com.amurcanov.tgwsproxy.ui.TgWsProxyTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Thin host for the original TG WS Proxy Compose screen.
 *
 * ProxyController, ProxyService, SettingsStore and the native library are used
 * directly; this fragment only gives LimeFlow a lifecycle-safe container.
 */
class EmbeddedProxyFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context.applicationContext) }
            val themeMode by settingsStore.themeMode
                .collectAsStateWithLifecycle(initialValue = "system")
            val dynamicColor by settingsStore.isDynamicColor
                .collectAsStateWithLifecycle(initialValue = true)
            val themePalette by settingsStore.themePalette
                .collectAsStateWithLifecycle(initialValue = "limeflow")
            val scope = rememberCoroutineScope()

            LaunchedEffect(settingsStore) {
                settingsStore.migrateLegacyDefaults()
            }

            TgWsProxyTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                themePalette = themePalette,
            ) {
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = LocalDensity.current.density,
                        fontScale = 1f,
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppBackdrop(modifier = Modifier.fillMaxSize())
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color.Transparent,
                        ) {
                            Box {
                                MainContent(settingsStore)
                                FloatingToolbar(
                                    currentTheme = themeMode,
                                    onThemeChange = { mode ->
                                        scope.launch {
                                            settingsStore.saveThemeMode(mode)
                                            delay(120)
                                            activity?.let(ActivityCompat::recreate)
                                        }
                                    },
                                    isDynamicColor = dynamicColor,
                                    onDynamicColorChange = { enabled ->
                                        scope.launch {
                                            settingsStore.saveDynamicColor(enabled)
                                            delay(120)
                                            activity?.let(ActivityCompat::recreate)
                                        }
                                    },
                                    currentPalette = themePalette,
                                    onPaletteChange = { palette ->
                                        scope.launch {
                                            settingsStore.saveThemePalette(palette)
                                            delay(120)
                                            activity?.let(ActivityCompat::recreate)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
