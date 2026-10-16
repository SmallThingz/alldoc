package org.oacp.android

/**
 * OACP protocol constants.
 *
 * Standard intent actions, extra keys, and status values defined by the
 * Open App Capability Protocol.
 */
public object Oacp {

    /** Protocol version this SDK implements. */
    public const val VERSION: String = "0.3"

    // --- Intent Actions ---

    /** Broadcast action for async results sent back to the assistant. */
    public const val ACTION_RESULT: String = "org.oacp.ACTION_RESULT"

    // --- Standard Intent Extra Keys ---

    /** UUID string for correlating requests with results. */
    public const val EXTRA_REQUEST_ID: String = "org.oacp.extra.REQUEST_ID"

    /** Result lifecycle status (see STATUS_* constants). */
    public const val EXTRA_STATUS: String = "org.oacp.extra.STATUS"

    /** The capability ID that was invoked. */
    public const val EXTRA_CAPABILITY_ID: String = "org.oacp.extra.CAPABILITY_ID"

    /** The source app's package name. */
    public const val EXTRA_SOURCE_PACKAGE: String = "org.oacp.extra.SOURCE_PACKAGE"

    /** Human-readable message the assistant may speak aloud. */
    public const val EXTRA_MESSAGE: String = "org.oacp.extra.MESSAGE"

    /** JSON string with structured result payload. */
    public const val EXTRA_RESULT: String = "org.oacp.extra.RESULT"

    /** Machine-readable error code. */
    public const val EXTRA_ERROR_CODE: String = "org.oacp.extra.ERROR_CODE"

    /** Human-readable error description. */
    public const val EXTRA_ERROR_MESSAGE: String = "org.oacp.extra.ERROR_MESSAGE"

    /** "true" or "false" — whether the error is transient and retryable. */
    public const val EXTRA_ERROR_RETRYABLE: String = "org.oacp.extra.ERROR_RETRYABLE"

    // --- Result Status Values ---

    /** The app accepted the request and will process it. */
    public const val STATUS_ACCEPTED: String = "accepted"

    /** The app started processing. */
    public const val STATUS_STARTED: String = "started"

    /** The action completed successfully. */
    public const val STATUS_COMPLETED: String = "completed"

    /** The action failed. */
    public const val STATUS_FAILED: String = "failed"

    /** The action was cancelled. */
    public const val STATUS_CANCELLED: String = "cancelled"

    // --- Common Error Codes ---

    public const val ERROR_INVALID_PARAMETERS: String = "invalid_parameters"
    public const val ERROR_MISSING_PARAMETERS: String = "missing_parameters"
    public const val ERROR_NOT_FOUND: String = "not_found"
    public const val ERROR_NOT_AUTHENTICATED: String = "not_authenticated"
    public const val ERROR_NOT_AUTHORIZED: String = "not_authorized"
    public const val ERROR_REQUIRES_FOREGROUND: String = "requires_foreground"
    public const val ERROR_REQUIRES_UNLOCK: String = "requires_unlock"
    public const val ERROR_UNSUPPORTED_STATE: String = "unsupported_state"
    public const val ERROR_NETWORK_UNAVAILABLE: String = "network_unavailable"
    public const val ERROR_CANCELLED: String = "cancelled"
    public const val ERROR_INTERNAL: String = "internal_error"

    // --- ContentProvider ---

    /** Suffix appended to applicationId to form the provider authority. */
    public const val PROVIDER_AUTHORITY_SUFFIX: String = ".oacp"

    /** ContentProvider path for the capability manifest. */
    public const val PROVIDER_PATH_MANIFEST: String = "manifest"

    /** ContentProvider path for the semantic context document. */
    public const val PROVIDER_PATH_CONTEXT: String = "context"

    /** ContentProvider path prefix for entity queries. */
    public const val PROVIDER_PATH_ENTITIES: String = "entities"

    /** Placeholder in oacp.json replaced with actual package name at runtime. */
    public const val APPLICATION_ID_PLACEHOLDER: String = "__APPLICATION_ID__"
}
