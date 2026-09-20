package com.saasinvestigator.product;

/**
 * One configured data source, embedded in a {@link SaasProduct}'s {@code sources} array.
 *
 * <p>Embedded rather than a collection of its own: a source has no identity or lifecycle outside the product that
 * owns it, is never queried across products, and is always loaded with its product. A separate collection would
 * buy a join and nothing else.
 *
 * <p><b>{@code authToken} is stored encrypted</b> and is the one field here that needs care. What is held in this
 * object is always the AES-256-GCM ciphertext, never the plaintext a user typed - the service layer encrypts on
 * the way in and masks to {@code last4} on the way out. Nothing in this class decrypts, on purpose: a getter that
 * returns plaintext is a getter that eventually gets logged.
 *
 * <p>{@code maxDepth} and {@code maxPages} apply only to crawled types (see {@link SourceType#isCrawled()}) and
 * are validated away for MCP types rather than quietly ignored - an MCP source carrying {@code maxDepth: 5} would
 * imply a limit is being enforced somewhere, and none is.
 */
public class SourceConfig {

    private SourceType type;

    /** A human-chosen label, unique within its product. Identifies the source in reports and snapshots. */
    private String name;

    /** The MCP server URL, or the crawl's starting URL. */
    private String endpointUrl;

    /**
     * Credential for reaching this source, AES-256-GCM encrypted under {@code CREDENTIAL_ENCRYPTION_KEY}, or
     * {@code null} for a source that needs none.
     *
     * <p>Never returned by any endpoint in this form, and never logged. See {@code CryptoService}.
     */
    private String authTokenEncrypted;

    /**
     * How many links deep the crawl follows, or {@code null} to use the system default.
     *
     * <p>Null means "whatever the current default is" rather than a value copied at creation time, so raising the
     * default in the Admin Console takes effect for existing sources that never overrode it.
     */
    private Integer maxDepth;

    /** Maximum pages fetched per run, or {@code null} to use the system default. Same nullability reasoning. */
    private Integer maxPages;

    /** Required by Spring Data's mapping layer. */
    public SourceConfig() {}

    /**
     * Creates a source with no crawl overrides - correct as-is for MCP types, and for crawled types that should
     * follow the system defaults.
     *
     * @param type which kind of source this is
     * @param name the label, unique within the product
     * @param endpointUrl the MCP server URL or crawl starting URL
     * @param authTokenEncrypted already-encrypted credential, or {@code null}
     */
    public SourceConfig(SourceType type, String name, String endpointUrl, String authTokenEncrypted) {
        this.type = type;
        this.name = name;
        this.endpointUrl = endpointUrl;
        this.authTokenEncrypted = authTokenEncrypted;
    }

    /**
     * @return {@code true} if the backend fetches this source itself, delegating to {@link SourceType}
     */
    public boolean isCrawled() {
        return type != null && type.isCrawled();
    }

    /**
     * @return {@code true} if this source is declared to the LLM as a remote MCP tool
     */
    public boolean isMcp() {
        return type != null && type.isMcp();
    }

    /** @return which kind of source this is */
    public SourceType getType() {
        return type;
    }

    /** @param type which kind of source this is */
    public void setType(SourceType type) {
        this.type = type;
    }

    /** @return the label, unique within its product */
    public String getName() {
        return name;
    }

    /** @param name the label, unique within its product */
    public void setName(String name) {
        this.name = name;
    }

    /** @return the MCP server URL or crawl starting URL */
    public String getEndpointUrl() {
        return endpointUrl;
    }

    /** @param endpointUrl the MCP server URL or crawl starting URL */
    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    /** @return the encrypted credential, or {@code null}. Ciphertext - not safe to return to a client as-is. */
    public String getAuthTokenEncrypted() {
        return authTokenEncrypted;
    }

    /** @param authTokenEncrypted an already-encrypted credential, or {@code null}. Never a plaintext token. */
    public void setAuthTokenEncrypted(String authTokenEncrypted) {
        this.authTokenEncrypted = authTokenEncrypted;
    }

    /** @return {@code true} if a credential is stored for this source */
    public boolean hasAuthToken() {
        return authTokenEncrypted != null && !authTokenEncrypted.isBlank();
    }

    /** @return the crawl depth override, or {@code null} to use the system default */
    public Integer getMaxDepth() {
        return maxDepth;
    }

    /** @param maxDepth the crawl depth override, or {@code null} to use the system default */
    public void setMaxDepth(Integer maxDepth) {
        this.maxDepth = maxDepth;
    }

    /** @return the page-count override, or {@code null} to use the system default */
    public Integer getMaxPages() {
        return maxPages;
    }

    /** @param maxPages the page-count override, or {@code null} to use the system default */
    public void setMaxPages(Integer maxPages) {
        this.maxPages = maxPages;
    }
}
