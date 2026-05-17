package ru.sipaha.spkremote.app

import android.app.Application
import ru.sipaha.spkremote.app.diagnostics.CrashLogger

/**
 * Custom [Application] subclass — registered as `android:name=".SpkApplication"`
 * in `AndroidManifest.xml`. Its only job today is to install the
 * uncaught-exception handler from [CrashLogger] before any other code
 * runs, so crashes during Activity creation or ViewModel init are
 * captured.
 *
 * Don't add Activity-level / ViewModel-level logic here — keep this
 * lean enough that adding it never introduces a new pre-onCreate crash
 * path the user can't diagnose.
 */
class SpkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
