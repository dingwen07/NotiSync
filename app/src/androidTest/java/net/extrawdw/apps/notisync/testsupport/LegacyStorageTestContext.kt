package net.extrawdw.apps.notisync.testsupport

import android.content.Context
import android.content.ContextWrapper

/** Keeps pre-cutover store contract tests isolated on their original per-feature database files. */
internal class LegacyStorageTestContext(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
}
