/**
 * Authentication and authorisation: JWT issuing/verification, the signing-secret resolution chain, and
 * the Spring Security filter chain.
 *
 * <p>Auth is stateless - there is no server-side session. A successful login returns a signed JWT whose
 * subject is the username and which carries the user's role as a claim; every subsequent request
 * presents it as {@code Authorization: Bearer <token>}. Nothing about a token is stored server-side,
 * which is why "log everyone out" is implemented by changing the signing key rather than by clearing a
 * session table.
 *
 * @see com.saasinvestigator.security.JwtSecretResolver for the override > env var > auto-generated
 *     resolution order
 */
package com.saasinvestigator.security;
