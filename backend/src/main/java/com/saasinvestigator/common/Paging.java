package com.saasinvestigator.common;

import com.saasinvestigator.error.BadRequestException;
import org.springframework.data.domain.PageRequest;

/**
 * Turns {@code ?page=&size=} into a validated {@link PageRequest}, identically for every paginated endpoint.
 *
 * <p>The ceiling is the reason this is shared rather than repeated. {@code ?size=1000000} is a one-line request to
 * load an entire collection into memory and serialise it, so the cap has to exist on every paginated endpoint, and a
 * cap that each controller applies for itself is a cap that the next controller forgets. Defaults are left to the
 * endpoint - an audit trail is read in bigger pages than a product list - but the ceiling and the "what counts as a
 * nonsensical page" rules are the same everywhere.
 *
 * <p>Out-of-range values are rejected rather than corrected. Silently serving page 0 for {@code ?page=-1} makes a
 * broken pager look like a working one that has run out of data.
 *
 * <p>No {@code sort} parameter, deliberately. Every list in this API has one correct order - newest first - which is
 * baked into the repository query and its index. A client-supplied sort field would be an unindexed collection scan
 * waiting to be requested, and there is no screen that wants one.
 */
public final class Paging {

    /** Ceiling on page size for every paginated endpoint. */
    public static final int MAX_PAGE_SIZE = 200;

    /** The page size used by endpoints that have no reason to ask for a different one. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private Paging() {
    }

    /**
     * Validates a page request and caps its size.
     *
     * @param page zero-based page number
     * @param size requested page size
     * @return the page request, with size capped at {@link #MAX_PAGE_SIZE}
     * @throws BadRequestException if {@code page} is negative or {@code size} is below 1
     */
    public static PageRequest of(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater.");
        }
        if (size < 1) {
            throw new BadRequestException("size must be at least 1.");
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
    }
}
