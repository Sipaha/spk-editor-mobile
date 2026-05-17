package ru.sipaha.spkremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import ru.sipaha.spkremote.app.ui.App
import ru.sipaha.spkremote.app.ui.theme.SpkRemoteTheme
import ru.sipaha.spkremote.app.vm.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cold-start auto-resume: if EncryptedSharedPreferences has a
        // persisted pairing URL from a previous session, kick off the
        // connect immediately. The nav graph reacts to the resulting
        // [UiState.Connecting] / [UiState.Connected] transitions and pushes
        // the user past the QR screen without a flicker.
        //
        // Parse failure (corrupted prefs, schema change between versions)
        // falls back to the pairing screen with the inline error — same as
        // a hand-typed bad URL.
        if (savedInstanceState == null) {
            val persisted = viewModel.loadPersistedPairingUrl()
            if (!persisted.isNullOrBlank()) {
                viewModel.connect(persisted)
            }
        }
        setContent {
            SpkRemoteTheme {
                App(vm = viewModel)
            }
        }
    }
}
