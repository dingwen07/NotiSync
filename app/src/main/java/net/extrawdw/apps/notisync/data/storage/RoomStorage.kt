package net.extrawdw.apps.notisync.data.storage

import android.content.Context
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase

/** Let Room create or migrate both databases before raw feature stores open them. */
internal suspend fun initializeRoomStorage(context: Context) {
    val core = CoreDatabase.create(context)
    try {
        core.metadata().schemaObjectCount()
    } finally {
        core.close()
    }
    val operational = OperationalDatabase.create(context)
    try {
        operational.metadata().schemaObjectCount()
    } finally {
        operational.close()
    }
}
