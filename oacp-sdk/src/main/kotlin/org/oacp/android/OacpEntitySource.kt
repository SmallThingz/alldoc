package org.oacp.android

import android.content.Context

/**
 * Callback interface for serving dynamic OACP entities.
 *
 * Implement this interface and register it with [OacpProvider.setEntitySource]
 * to serve entity data at `content://${applicationId}.oacp/entities/<entityType>`.
 *
 * Example:
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         OacpProvider.setEntitySource(object : OacpEntitySource {
 *             override fun queryEntities(
 *                 context: Context,
 *                 entityType: String,
 *                 query: String?
 *             ): List<OacpEntity> {
 *                 if (entityType != "alarm") return emptyList()
 *                 return alarmRepository.getAll()
 *                     .filter { query == null || it.label.startsWith(query, ignoreCase = true) }
 *                     .map { OacpEntity(id = it.id.toString(), displayName = it.label) }
 *             }
 *         })
 *     }
 * }
 * ```
 */
public interface OacpEntitySource {

    /**
     * Returns entities of the given type, optionally filtered by a prefix query.
     *
     * @param context     Android context.
     * @param entityType  The entity type ID (e.g. "alarm", "timer", "reading_list").
     * @param query       Optional prefix filter from `?q=` query parameter, or null.
     * @return List of matching entities. Return empty list if the type is unknown.
     */
    public fun queryEntities(
        context: Context,
        entityType: String,
        query: String?
    ): List<OacpEntity>
}

/**
 * An OACP entity item returned by [OacpEntitySource].
 *
 * @property id           Stable identifier for this entity.
 * @property displayName  Human-readable name the assistant may speak or display.
 * @property aliases      Optional alternate names users might say.
 * @property description  Optional description for disambiguation.
 * @property keywords     Optional ranking keywords.
 * @property metadata     Optional structured data (serialized as JSON).
 */
public data class OacpEntity(
    public val id: String,
    public val displayName: String,
    public val aliases: List<String> = emptyList(),
    public val description: String? = null,
    public val keywords: List<String> = emptyList(),
    public val metadata: Map<String, Any> = emptyMap()
)
