package com.saasinvestigator.product;

import com.saasinvestigator.crawl.CrawlSettings;
import com.saasinvestigator.crypto.CryptoService;
import com.saasinvestigator.error.BadRequestException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Converts sources between the three forms they exist in, and owns the encryption boundary for their tokens.
 *
 * <pre>
 *   SourceConfigRequest   ->   SourceConfig        ->   SourceConfigResponse
 *   (plaintext token)          (encrypted token)        (last4 only)
 *                        ^ this class ^
 * </pre>
 *
 * <p>Every path that saves a source goes through {@link #toStored}, and every path that returns one goes
 * through {@link #toResponse}. That is what makes "an MCP {@code authToken} is encrypted at rest and never
 * returned in full" a property of the application rather than a habit of whoever wrote the last controller.
 * {@link #decryptAuthToken} is the single exception - the one method that produces plaintext, for handing to
 * an MCP server declaration - and it exists here, next to the encryption, so the whole lifecycle of the token
 * is readable in one file.
 *
 * <p><b>Validation lives here too</b>, because these rules are about the source's shape rather than about the
 * product that holds it, and because the update path needs them before it can safely match new sources
 * against old ones by name. Two of them are worth their own note:
 *
 * <ul>
 *   <li><b>Names must be unique within a product.</b> A source's name is its identity in snapshots, in report
 *       {@code changes} entries, and in the {@code sourcesIncluded} metadata - none of which carry an index.
 *       Two sources called "Docs" would make a report ambiguous about which one changed, and would make this
 *       class's own token-preservation matching ambiguous as well.
 *   <li><b>Crawl limits are rejected when out of range, not clamped.</b> The opposite of how the same values
 *       are treated when read back out of an existing document. The asymmetry is deliberate and is the same
 *       one {@code CrawlSettings.updateDefaults} makes: a person typing 10,000 into a form should be told the
 *       ceiling, not silently given 200 while the form still shows what they typed. A value already stored -
 *       possibly written before the ceiling existed, possibly written straight into Mongo by the Data
 *       Explorer - is clamped instead, because there is nobody there to tell. See
 *       {@code /docs/decisions/0005-crawl-bounds.md}.
 * </ul>
 */
@Service
public class SourceConfigMapper {

    private static final Logger log = LoggerFactory.getLogger(SourceConfigMapper.class);

    private final CryptoService cryptoService;
    private final CrawlSettings crawlSettings;

    /**
     * @param cryptoService encrypts tokens on the way in, and produces {@code last4} on the way out
     * @param crawlSettings supplies the effective crawl limits and the ceilings to validate against
     */
    public SourceConfigMapper(CryptoService cryptoService, CrawlSettings crawlSettings) {
        this.cryptoService = cryptoService;
        this.crawlSettings = crawlSettings;
    }

    /**
     * Converts a submitted list of sources into storable ones, encrypting any new tokens and carrying
     * existing ones forward.
     *
     * <p>Existing sources are matched by name, case-insensitively. That is what lets an edit form resubmit
     * every source without the user having to re-enter tokens they cannot see - and renaming a source is
     * consequently treated as replacing it, which is correct: the name is the identity that its snapshots and
     * its report history are recorded against.
     *
     * @param requests the submitted sources; {@code null} is treated as an empty list, because a product with
     *     no sources is a legitimate intermediate state rather than an error
     * @param existing the product's currently stored sources, or {@code null} on create
     * @return storable sources in submission order, tokens encrypted
     * @throws BadRequestException if a name is duplicated, a URL is unusable, an MCP source carries crawl
     *     limits, or a crawl limit exceeds its ceiling
     */
    public List<SourceConfig> toStored(List<SourceConfigRequest> requests, List<SourceConfig> existing) {
        if (requests == null || requests.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, SourceConfig> existingByName = indexByName(existing);
        Set<String> seenNames = new HashSet<>();
        List<SourceConfig> stored = new ArrayList<>(requests.size());

        for (SourceConfigRequest request : requests) {
            String name = request.name() == null ? "" : request.name().trim();
            validate(request, name, seenNames);
            SourceConfig previous = existingByName.get(key(name));

            SourceConfig source = new SourceConfig();
            source.setType(request.type());
            source.setName(name);
            source.setEndpointUrl(request.endpointUrl().trim());
            source.setAuthTokenEncrypted(resolveToken(request, previous));
            // MCP sources are validated above to carry neither, so these are null for them by construction
            // rather than by being stripped - an MCP source holding maxDepth: 5 would imply a limit was
            // being enforced on something nothing crawls.
            source.setMaxDepth(request.maxDepth());
            source.setMaxPages(request.maxPages());
            stored.add(source);
        }
        return stored;
    }

    /**
     * Converts a stored source into its API representation, masking the token.
     *
     * @param source the stored source
     * @return the safe-to-return view, including the crawl limits that will actually be used
     */
    public SourceConfigResponse toResponse(SourceConfig source) {
        boolean hasToken = source.hasAuthToken();
        return new SourceConfigResponse(
                source.getType(),
                source.getName(),
                source.getEndpointUrl(),
                hasToken,
                hasToken ? last4OrMasked(source) : null,
                source.getMaxDepth(),
                source.getMaxPages(),
                source.isCrawled() ? crawlSettings.resolveMaxDepth(source.getMaxDepth()) : null,
                source.isCrawled() ? crawlSettings.resolveMaxPages(source.getMaxPages()) : null);
    }

    /**
     * Converts a whole list of stored sources.
     *
     * @param sources the stored sources, or {@code null}
     * @return the masked views, in order; empty if there are none
     */
    public List<SourceConfigResponse> toResponses(List<SourceConfig> sources) {
        return sources == null ? List.of() : sources.stream().map(this::toResponse).toList();
    }

    /**
     * Decrypts a source's auth token for an actual call to the source.
     *
     * <p>The only method in the application that produces an MCP token in plaintext. It is used when
     * declaring the MCP server to the LLM provider; the value must not be logged, echoed into a progress
     * event, or stored anywhere.
     *
     * <p>A token that cannot be decrypted resolves to empty rather than throwing, and the source is then
     * declared without credentials - which will most likely fail at the MCP server with a clear
     * authentication error. That is a better outcome than aborting the whole run, since a product's other
     * sources are unaffected and the ERROR line names the source to fix.
     *
     * @param source the stored source
     * @return the plaintext token, or empty if none is stored or it cannot be decrypted
     */
    public Optional<String> decryptAuthToken(SourceConfig source) {
        if (source == null || !source.hasAuthToken()) {
            return Optional.empty();
        }
        try {
            return Optional.of(cryptoService.decrypt(source.getAuthTokenEncrypted()));
        } catch (IllegalStateException e) {
            log.error("The auth token for source '{}' could not be decrypted and will not be sent. The most "
                    + "likely cause is that CREDENTIAL_ENCRYPTION_KEY changed; re-enter the token on the "
                    + "product's source configuration.", source.getName(), e);
            return Optional.empty();
        }
    }

    private String resolveToken(SourceConfigRequest request, SourceConfig previous) {
        String submitted = request.authToken();
        if (submitted == null) {
            // Absent means unchanged. On create there is no previous source, so this correctly yields null.
            return previous == null ? null : previous.getAuthTokenEncrypted();
        }
        if (submitted.isBlank()) {
            // Explicitly emptied: the user is telling us this source needs no credential any more.
            return null;
        }
        return cryptoService.encrypt(submitted.trim());
    }

    private void validate(SourceConfigRequest request, String name, Set<String> seenNames) {
        if (name.isEmpty()) {
            throw new BadRequestException("Every source needs a name.");
        }
        if (!seenNames.add(key(name))) {
            // Says "ignoring case" because otherwise "Docs" and "docs" produce a message that looks like it
            // is complaining about a name the user can see only once in the form.
            throw new BadRequestException("Two sources are both named '" + name
                    + "' (names are compared ignoring case). Source names identify them in reports and "
                    + "snapshots, so they must be unique within a product.");
        }
        validateUrl(request, name);
        if (request.type().isMcp()) {
            if (request.maxDepth() != null || request.maxPages() != null) {
                throw new BadRequestException("Source '" + name + "' is an MCP source, so maxDepth and "
                        + "maxPages do not apply - the LLM calls the server's tools directly and nothing "
                        + "crawls it. Remove them rather than leaving limits that are not enforced.");
            }
            return;
        }
        CrawlSettings.CrawlDefaults ceilings = crawlSettings.ceilings();
        if (request.maxDepth() != null
                && (request.maxDepth() < 0 || request.maxDepth() > ceilings.defaultMaxDepth())) {
            throw new BadRequestException("maxDepth for source '" + name + "' must be between 0 and "
                    + ceilings.defaultMaxDepth() + ". Crawl limits are a safety boundary, not a preference.");
        }
        if (request.maxPages() != null
                && (request.maxPages() < 1 || request.maxPages() > ceilings.defaultMaxPages())) {
            throw new BadRequestException("maxPages for source '" + name + "' must be between 1 and "
                    + ceilings.defaultMaxPages() + ". Crawl limits are a safety boundary, not a preference.");
        }
    }

    private void validateUrl(SourceConfigRequest request, String name) {
        String url = request.endpointUrl() == null ? "" : request.endpointUrl().trim();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new BadRequestException("endpointUrl for source '" + name + "' must be an http or "
                        + "https URL. Both MCP servers and crawled pages are reached over HTTP.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BadRequestException("endpointUrl for source '" + name
                        + "' has no host - it needs to look like https://example.com/docs.");
            }
        } catch (URISyntaxException e) {
            // The exception's own message points at a character offset, which is accurate and useless to
            // someone who mistyped a URL in a form field.
            throw new BadRequestException("endpointUrl for source '" + name + "' is not a valid URL.");
        }
    }

    private String last4OrMasked(SourceConfig source) {
        return decryptAuthToken(source).map(cryptoService::last4).orElseGet(() -> cryptoService.last4(null));
    }

    private static Map<String, SourceConfig> indexByName(List<SourceConfig> existing) {
        Map<String, SourceConfig> index = new HashMap<>();
        if (existing != null) {
            for (SourceConfig source : existing) {
                if (source.getName() != null) {
                    index.put(key(source.getName()), source);
                }
            }
        }
        return index;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
