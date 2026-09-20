package com.saasinvestigator.admin;

import com.saasinvestigator.error.BadRequestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.stereotype.Component;

/**
 * The Data Explorer's one guardrail: no secret leaves the server, and no secret can be written through here.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>A generic "browse and edit any document" tool is precisely the thing that would quietly undo every protection the
 * rest of this application builds up. Passwords are hashed so nobody can read them back; API keys and MCP tokens are
 * encrypted at rest and only ever surfaced as a {@code last4}; the JWT signing secret is never returned in any form.
 * A raw Mongo browser with none of that applied would make all three reachable in a table view, and an editable field
 * would let an admin replace a BCrypt hash with the literal text {@code hunter2} - locking that user out in a way
 * nothing in the application would explain.
 *
 * <h2>Masking is server-side, and that is the point</h2>
 *
 * <p>{@link #mask(String, Document)} replaces the value, so what travels to the browser is the string
 * {@value #MASK} and the real ciphertext never reaches it. Hiding the field in the UI instead would leave every
 * ciphertext in a network response, a browser cache, and whatever logs sit between the two - a different feature
 * wearing the same label.
 *
 * <h2>Rejecting, not ignoring</h2>
 *
 * <p>An update touching a masked field is a 400 naming where the change actually belongs. Silently dropping the field
 * would let an admin believe they had rotated a key; silently writing it would store {@value #MASK} as somebody's
 * password hash.
 */
@Component
public class SecretFieldMasker {

    /** What a secret value is replaced with. Chosen to read as a state, not as a value someone might try to copy. */
    public static final String MASK = "[encrypted]";

    /**
     * Secret field names per collection, with the message explaining where the change belongs instead.
     *
     * <p>Keyed by collection rather than being one global list of names, because a field name is only secret in
     * context: a {@code value} in {@code system_config} is public config, while {@code valueEncrypted} beside it is
     * the JWT signing secret. A global blocklist would either miss that or over-block.
     */
    private static final Map<String, Map<String, String>> SECRETS = secrets();

    /**
     * Fields that are structural rather than secret but still must not be edited here.
     *
     * <p>{@code _id} identifies the document being updated - editing it is a move, not an edit, and Mongo rejects it
     * outright with an error that explains nothing. {@code _class} is how Spring Data knows which Java type to read a
     * document back as; changing it turns a document into one that no longer deserialises, and the failure surfaces
     * far from here.
     */
    private static final List<String> STRUCTURAL = List.of("_id", "_class");

    private static Map<String, Map<String, String>> secrets() {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        map.put("users", Map.of("passwordHash",
                "passwordHash can't be edited directly - use Users > Reset password."));
        map.put("user_llm_credentials", Map.of("apiKeyEncrypted",
                "apiKeyEncrypted can't be edited directly - the owner sets their own key in Account Settings, "
                + "and an admin can't set it for them."));
        map.put("system_llm_credentials", Map.of("apiKeyEncrypted",
                "apiKeyEncrypted can't be edited directly - use Admin Console > Secrets."));
        map.put("system_config", Map.of("valueEncrypted",
                "valueEncrypted can't be edited directly - use Admin Console > Secrets for the JWT signing secret, "
                + "or Settings for non-secret configuration."));
        map.put("saas_products", Map.of("authTokenEncrypted",
                "authTokenEncrypted can't be edited directly - edit the product's MCP source and set its auth token "
                + "there."));
        return map;
    }

    /**
     * Replaces every secret value in a document, including inside nested documents and arrays.
     *
     * <p>Recursive because one of the secrets is not top-level: an MCP source's {@code authTokenEncrypted} lives inside
     * the {@code sources} array of a {@code saas_products} document. A top-level-only masker would show the product
     * table safely and leak the token the moment somebody clicked a row.
     *
     * @param collection which collection the document came from
     * @param document the raw document; not modified
     * @return a copy with secret values replaced by {@value #MASK}
     */
    public Document mask(String collection, Document document) {
        Map<String, String> secretFields = SECRETS.getOrDefault(collection, Map.of());
        return secretFields.isEmpty() ? document : (Document) maskValue(document, secretFields.keySet());
    }

    @SuppressWarnings("unchecked")
    private static Object maskValue(Object value, java.util.Set<String> secretFields) {
        if (value instanceof Document document) {
            Document copy = new Document();
            document.forEach((key, nested) -> copy.put(key,
                    // Masked whether or not the value is null: a null secret field is genuinely "not set", but
                    // distinguishing the two here would mean the UI has to, and it has nothing useful to do with the
                    // difference.
                    secretFields.contains(key) ? (nested == null ? null : MASK) : maskValue(nested, secretFields)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> maskValue(item, secretFields)).toList();
        }
        if (value instanceof Map<?, ?> map) {
            // Mongo hands back Document for sub-documents, but a plain Map can appear when a document was written by
            // something other than this application - which is exactly the case this tool exists to inspect.
            Document copy = new Document();
            ((Map<String, Object>) map).forEach((key, nested) -> copy.put(key,
                    secretFields.contains(key) ? (nested == null ? null : MASK) : maskValue(nested, secretFields)));
            return copy;
        }
        return value;
    }

    /**
     * Rejects an update that touches a field this tool must not write.
     *
     * <p>Walks the whole submitted document, not just its top level, and for the same reason {@link #mask} does: an MCP
     * source's token is nested inside an array. A top-level-only check would pass an update whose {@code sources} array
     * carries {@value #MASK} back in the token's place, overwriting real ciphertext with the mask - which is how a
     * masking feature turns into a data-destroying one.
     *
     * @param collection which collection is being updated
     * @param updates the submitted document
     * @throws BadRequestException naming the first offending field and where the change belongs instead
     */
    public void rejectProtectedFields(String collection, Document updates) {
        reject(updates, SECRETS.getOrDefault(collection, Map.of()));
    }

    private static void reject(Object value, Map<String, String> secretFields) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                String field = String.valueOf(key);
                String message = secretFields.get(field);
                if (message != null) {
                    throw new BadRequestException(message);
                }
                if (STRUCTURAL.contains(field)) {
                    throw new BadRequestException(field + " is managed by the database and can't be edited here.");
                }
                reject(nested, secretFields);
            });
        } else if (value instanceof List<?> list) {
            list.forEach(item -> reject(item, secretFields));
        }
    }

    /**
     * @param collection which collection
     * @return the names of that collection's secret fields, so the UI can render them as disabled chips rather than
     *     discovering they are read-only by being rejected
     */
    public List<String> secretFieldNames(String collection) {
        return List.copyOf(SECRETS.getOrDefault(collection, Map.of()).keySet());
    }
}
