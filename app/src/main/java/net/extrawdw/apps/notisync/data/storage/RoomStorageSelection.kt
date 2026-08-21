package net.extrawdw.apps.notisync.data.storage

/** Marks an application context whose one-time cutover selected the two authoritative Room v1 databases. */
interface RoomStorageSelection {
    val usesRoomStorage: Boolean
}

internal val android.content.Context.usesRoomStorage: Boolean
    get() = (applicationContext as? RoomStorageSelection)?.usesRoomStorage == true
