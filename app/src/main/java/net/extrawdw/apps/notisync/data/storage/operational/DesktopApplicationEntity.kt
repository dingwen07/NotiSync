package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction

/** Only user entries live here. A row replaces the complete built-in entry with the same ID. */
@Entity(tableName = "desktop_applications")
internal data class DesktopApplicationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "traversal") val traversal: String,
    @ColumnInfo(name = "accepted_names_json") val acceptedNamesJson: String,
    @ColumnInfo(name = "accepted_paths_json") val acceptedPathsJson: String,
    @ColumnInfo(name = "icon_data") val iconData: ByteArray?,
)

@Dao
internal interface DesktopApplicationDao {
    @Query("SELECT * FROM desktop_applications ORDER BY id")
    suspend fun entries(): List<DesktopApplicationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DesktopApplicationEntity)

    @Query("DELETE FROM desktop_applications WHERE id = :id")
    suspend fun delete(id: String)

    @Transaction
    suspend fun save(entry: DesktopApplicationEntity, previousId: String?) {
        upsert(entry)
        if (previousId != null && previousId != entry.id) delete(previousId)
    }
}
