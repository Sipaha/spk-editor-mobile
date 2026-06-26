package ru.sipaha.sawe.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import ru.sipaha.sawe.app.ui.App
import ru.sipaha.sawe.app.ui.theme.SaweMobileTheme
import ru.sipaha.sawe.app.vm.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // No explicit enableEdgeToEdge(), but Android 15+ (targetSdk 35+)
        // FORCES edge-to-edge regardless — adjustResize alone no longer
        // lifts the activity window above the IME on those targets.
        // SessionDetailScreen's Scaffold zeroes contentWindowInsets and
        // its bottomBar applies WindowInsets.ime.union(navigationBars)
        // explicitly so the compose row stays above the keyboard AND
        // clears the system nav bar at rest.
        super.onCreate(savedInstanceState)
        // Cold-start auto-resume: the VM inspects [PairingRepository.loadAll]
        // and returns the appropriate landing destination — `pairing` when
        // nothing is paired, `solutions` for the single-server R-6b
        // auto-resume path, or `servers` when 2+ are paired. The VM also
        // kicks off [switchToServer] synchronously for the
        // most-recently-active server in the latter two cases, so by the
        // time the nav graph renders we're already in Connecting state.
        val initialRoute = if (savedInstanceState == null) {
            viewModel.coldStartLandingRoute()
        } else {
            // Recreated activity (e.g. orientation change). The VM survived
            // and is already in the right state — let the nav graph
            // restore its own back stack.
            null
        }
        setContent {
            SaweMobileTheme {
                App(vm = viewModel, initialRoute = initialRoute)
            }
        }
    }
}
