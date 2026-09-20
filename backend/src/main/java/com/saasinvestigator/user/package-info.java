/**
 * Users, roles, and the default-admin seed.
 *
 * <p>Passwords are stored only as BCrypt hashes. Because BCrypt is one-way, an admin can
 * <em>reset</em> another user's password but can never view it - that is the point of hashing, and
 * storing something reversible instead would be a real liability rather than a convenience. The
 * {@code /api/users} responses never include {@code passwordHash} at all.
 */
package com.saasinvestigator.user;
