package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Encrypted with the existing random SQLCipher database key, wrapped by Android Keystore. */
@Entity(tableName = "hotspot_credentials")
internal data class HotspotCredentialsEntity(
    @PrimaryKey @ColumnInfo(name = "hotspot_device_id") val hotspotDeviceId: String,
    @ColumnInfo(name = "ssid") val ssid: String,
    @ColumnInfo(name = "psk") val psk: String?,
    @ColumnInfo(name = "security_type") val securityType: Int,
    @ColumnInfo(name = "hidden_ssid") val hiddenSsid: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun toString(): String = "HotspotCredentialsEntity(credentials=redacted)"
}

@Dao
internal interface HotspotCredentialsDao {
    @Query("SELECT * FROM hotspot_credentials")
    fun observe(): Flow<List<HotspotCredentialsEntity>>

    @Query("""
        INSERT INTO hotspot_credentials(hotspot_device_id, ssid, psk, security_type, hidden_ssid, updated_at)
        VALUES (:deviceId, :ssid, :psk, :securityType, :hiddenSsid, :updatedAt)
        ON CONFLICT(hotspot_device_id) DO UPDATE SET
            ssid = excluded.ssid, psk = excluded.psk, security_type = excluded.security_type,
            hidden_ssid = excluded.hidden_ssid, updated_at = excluded.updated_at
        WHERE excluded.updated_at > hotspot_credentials.updated_at
    """)
    suspend fun save(deviceId: String, ssid: String, psk: String?, securityType: Int, hiddenSsid: Boolean, updatedAt: Long)

    @Query("DELETE FROM hotspot_credentials WHERE hotspot_device_id = :deviceId")
    suspend fun delete(deviceId: String)
}
