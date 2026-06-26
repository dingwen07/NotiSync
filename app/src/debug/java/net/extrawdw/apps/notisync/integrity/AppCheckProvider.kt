package net.extrawdw.apps.notisync.integrity

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds attest via the App Check debug provider: register the debug token it logs in the Firebase
 * console (App Check → Manage debug tokens) and emulators/CI/dev devices attest without passing Play
 * Integrity. Kept out of release by living in src/debug + the debugImplementation dependency.
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
