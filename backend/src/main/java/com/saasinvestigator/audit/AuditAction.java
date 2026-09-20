package com.saasinvestigator.audit;

/**
 * The closed set of auditable actions.
 *
 * <p>An enum rather than free-text strings so the Audit Log filter dropdown can be built from the values,
 * and so a typo in an action name is a compile error instead of an entry that no filter will ever match.
 */
public enum AuditAction {

    /** A login attempt succeeded. */
    AUTH_LOGIN_SUCCESS,

    /**
     * A login attempt failed. Recorded with the attempted username and nothing else - never the password
     * that was tried, which would turn the audit log into a plaintext password store.
     */
    AUTH_LOGIN_FAILURE,

    /** An admin created a user. */
    USER_CREATED,

    /** An admin deleted a user. */
    USER_DELETED,

    /** An admin reset a user's password. The new value is never recorded. */
    USER_PASSWORD_RESET,

    /** A SaaS Product was created. */
    PRODUCT_CREATED,

    /** A SaaS Product's details or sources were updated. */
    PRODUCT_UPDATED,

    /** A SaaS Product was deleted. */
    PRODUCT_DELETED,

    /** A standard run was triggered for a product. */
    PRODUCT_RUN_TRIGGERED,

    /** A custom date-range compare was triggered for a product. */
    PRODUCT_COMPARE_TRIGGERED,

    /** An ad-hoc question was asked about a product. */
    PRODUCT_ASK_SUBMITTED,

    /** A user stored or replaced their own LLM API key. Only the provider is recorded. */
    LLM_CREDENTIAL_ADDED,

    /** A user removed their own LLM API key. */
    LLM_CREDENTIAL_REMOVED,

    /** An admin set the system-wide credential override for a provider. */
    SYSTEM_CREDENTIAL_OVERRIDE_SET,

    /** An admin cleared the system-wide credential override for a provider. */
    SYSTEM_CREDENTIAL_OVERRIDE_CLEARED,

    /** An admin changed system settings, e.g. the crawl defaults. */
    SYSTEM_SETTINGS_UPDATED,

    /**
     * An admin set a JWT signing secret override, invalidating every issued token. Worth auditing
     * precisely because it signs out every session at once, including the actor's own.
     */
    JWT_SECRET_OVERRIDE_SET,

    /** An admin cleared the JWT signing secret override, which also invalidates every issued token. */
    JWT_SECRET_OVERRIDE_CLEARED,

    /** An admin edited a document through the Admin Console's Data Explorer. */
    DATA_EXPLORER_DOCUMENT_UPDATED
}
