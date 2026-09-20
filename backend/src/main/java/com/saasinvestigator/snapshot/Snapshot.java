package com.saasinvestigator.snapshot;

import com.saasinvestigator.product.SourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What one crawled source looked like at one moment, stored in the {@code snapshots} collection.
 *
 * <p>This is the application's memory, and the reason it can say what <em>changed</em> rather than only what is
 * currently true. Each run writes one snapshot per crawled source; the next run reads the previous one back and
 * hands both to the LLM. Nothing in the backend compares them - see {@code docs/ARCHITECTURE.md} on why the
 * comparison is the model's job and not a hand-written differ's.
 *
 * <p>Only crawled sources produce snapshots. MCP sources are reached by the model itself, so the backend never
 * holds their content and there is nothing to store - which is exactly the gap {@code mcpHistoryLimited} on a
 * change report exists to declare rather than paper over.
 *
 * <p>The source is referenced by {@code sourceName}, not by index into the product's source array, so reordering
 * or deleting a source cannot retroactively reassign old snapshots to a different one. {@code sourceType} is
 * copied in for the same reason a report copies it: so a snapshot still explains itself after the source it came
 * from has been edited or removed.
 */
@Document(collection = "snapshots")
public class Snapshot {

    @Id
    private String id;

    /** Which product this belongs to. Indexed with {@code sourceName} and {@code fetchedAt}. */
    private String saasProductId;

    /** The source's label within its product. */
    private String sourceName;

    /** Copied from the source at fetch time so this document stays interpretable on its own. */
    private SourceType sourceType;

    /**
     * The extracted visible text of everything crawled for this source, one block per page, each headed by its
     * URL, and capped before storage.
     *
     * <p>Text rather than HTML: markup is most of the bytes and none of the meaning, and a diff of two HTML
     * documents is dominated by changes nobody asked about. The cap is enforced at fetch time rather than here -
     * see the crawler - because storing more than will ever be sent to a model is storage spent on nothing.
     */
    private String rawContent;

    /**
     * Every URL actually fetched for this snapshot.
     *
     * <p>Recorded because "why did this report miss the thing on the pricing page" is otherwise unanswerable. With
     * this list the answer is visible: the crawl hit its {@code maxPages} limit before reaching it, or
     * {@code robots.txt} disallowed it, or it is not reachable same-origin from the starting URL.
     */
    private List<String> pageUrls = new ArrayList<>();

    private Instant fetchedAt;

    /** Required by Spring Data's mapping layer. */
    public Snapshot() {}

    /**
     * Creates a snapshot with {@code fetchedAt} stamped.
     *
     * @param saasProductId the owning product's id
     * @param sourceName the source's label within that product
     * @param sourceType the source's type at fetch time
     * @param rawContent the extracted, page-delimited, already-capped text
     * @param pageUrls the URLs actually fetched; copied defensively, {@code null} treated as empty
     */
    public Snapshot(String saasProductId, String sourceName, SourceType sourceType, String rawContent,
                    List<String> pageUrls) {
        this.saasProductId = saasProductId;
        this.sourceName = sourceName;
        this.sourceType = sourceType;
        this.rawContent = rawContent;
        this.pageUrls = pageUrls == null ? new ArrayList<>() : new ArrayList<>(pageUrls);
        this.fetchedAt = Instant.now();
    }

    /** @return the Mongo document id */
    public String getId() {
        return id;
    }

    /** @param id the Mongo document id */
    public void setId(String id) {
        this.id = id;
    }

    /** @return the owning product's id */
    public String getSaasProductId() {
        return saasProductId;
    }

    /** @param saasProductId the owning product's id */
    public void setSaasProductId(String saasProductId) {
        this.saasProductId = saasProductId;
    }

    /** @return the source's label within its product */
    public String getSourceName() {
        return sourceName;
    }

    /** @param sourceName the source's label within its product */
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    /** @return the source's type as it was at fetch time */
    public SourceType getSourceType() {
        return sourceType;
    }

    /** @param sourceType the source's type as it was at fetch time */
    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    /** @return the extracted, page-delimited text */
    public String getRawContent() {
        return rawContent;
    }

    /** @param rawContent the extracted, page-delimited text */
    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    /** @return the URLs actually fetched. Never {@code null}. */
    public List<String> getPageUrls() {
        return pageUrls;
    }

    /** @param pageUrls the URLs actually fetched; {@code null} is treated as empty */
    public void setPageUrls(List<String> pageUrls) {
        this.pageUrls = pageUrls == null ? new ArrayList<>() : pageUrls;
    }

    /** @return when this source was fetched */
    public Instant getFetchedAt() {
        return fetchedAt;
    }

    /** @param fetchedAt when this source was fetched */
    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
