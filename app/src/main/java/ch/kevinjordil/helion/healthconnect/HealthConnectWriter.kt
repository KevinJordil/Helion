package ch.kevinjordil.helion.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import kotlin.reflect.KClass

/**
 * Indirection over the real Health Connect service, the same shape
 * [ch.kevinjordil.helion.source.CommandSender] and [ch.kevinjordil.helion.source.ExportSignal]
 * already give the Gadgetbridge side of this app: a test hands [HealthConnectExporter] a
 * fake implementation and never starts a real Health Connect client, which would need an
 * actual installed provider and cross-process binder calls Robolectric cannot fulfil.
 */
interface HealthConnectWriter {

    /**
     * True only when every permission in [HEALTH_CONNECT_PERMISSIONS] is currently granted.
     * Checked fresh on every export pass, never cached: Health Connect permissions can be
     * revoked at any time from outside this app (the system's own permission screen), and
     * [HealthConnectExporter] must notice on the very next pass, not keep writing on a
     * stale "it was granted last time".
     */
    suspend fun hasWritePermission(): Boolean

    /**
     * Inserts or updates [records] (mixed record types allowed in one call). Health Connect
     * itself is what makes this an update rather than a duplicate when a record's
     * `clientRecordId` already exists there with an equal or lower `clientRecordVersion` --
     * see [healthConnectMetadata]'s own kdoc. Returns the count actually accepted; throws on
     * a genuine failure (permission missing, malformed record, no connection to the
     * provider), which [HealthConnectExporter] turns into a reported error rather than a
     * crash.
     */
    suspend fun insertOrUpdate(records: List<Record>): Int

    /**
     * Removes every record of [recordType] whose `clientRecordId` is in [clientRecordIds] --
     * used only for a [ch.kevinjordil.helion.store.ActivityStatus.DISMISSED] activity that
     * had already been written (see [HealthConnectExporter]'s own kdoc on why this path is
     * currently unreachable through the app's own UI, and kept anyway).
     */
    suspend fun deleteByClientId(recordType: KClass<out Record>, clientRecordIds: List<String>)
}

/** The real implementation, backed by an actual [HealthConnectClient] bound to Health Connect. */
class RealHealthConnectWriter(private val client: HealthConnectClient) : HealthConnectWriter {

    override suspend fun hasWritePermission(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(HEALTH_CONNECT_PERMISSIONS)

    override suspend fun insertOrUpdate(records: List<Record>): Int {
        if (records.isEmpty()) return 0
        return client.insertRecords(records).recordIdsList.size
    }

    override suspend fun deleteByClientId(recordType: KClass<out Record>, clientRecordIds: List<String>) {
        if (clientRecordIds.isEmpty()) return
        client.deleteRecords(recordType, recordIdsList = emptyList(), clientRecordIdsList = clientRecordIds)
    }
}

/** Builds the real client, or null when Health Connect is not usable on this phone -- see [healthConnectAvailability]. */
fun realHealthConnectWriterOrNull(context: Context): HealthConnectWriter? =
    if (healthConnectAvailability(context) == HealthConnectAvailability.Available) {
        RealHealthConnectWriter(HealthConnectClient.getOrCreate(context))
    } else {
        null
    }
