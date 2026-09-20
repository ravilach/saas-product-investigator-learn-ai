package com.saasinvestigator.user;

/**
 * The two roles in the system.
 *
 * <p>Enforced with {@code @PreAuthorize} on controller methods; the frontend mirrors the same rules
 * as route guards, but the backend is the authority. See the
 * {@code manage-roles-and-permissions} skill - a role change has to be made in both places together
 * or the UI and the API disagree about what a user can do.
 */
public enum Role {

    /**
     * Full access: create/edit/delete SaaS Products and their sources, manage users (including
     * resetting any user's password), and the whole Admin Console.
     */
    ADMIN,

    /**
     * View SaaS Products and their sources, trigger runs and compares, view report history, and use
     * the ad-hoc Ask feature. Cannot change source configuration or manage users.
     */
    READ_ONLY;

    /**
     * Returns the Spring Security authority string for this role.
     *
     * <p>Spring's {@code hasRole('ADMIN')} expects the stored authority to be {@code ROLE_ADMIN};
     * building that prefix in one place avoids the classic mismatch where {@code @PreAuthorize}
     * silently never matches because the authority was stored without it.
     *
     * @return the authority name, e.g. {@code ROLE_ADMIN}
     */
    public String authority() {
        return "ROLE_" + name();
    }
}
