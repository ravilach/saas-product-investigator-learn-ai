package com.saasinvestigator.audit;

import com.saasinvestigator.common.PageResponse;
import com.saasinvestigator.error.BadRequestException;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only access to the audit trail.
 *
 * <p>ADMIN-only, and only ever readable. There is deliberately no endpoint here to create, edit, or delete an
 * entry: a trail whose contents can be adjusted through the same API that writes it answers nothing. Entries are
 * written exclusively by {@link AuditService} from inside the action being recorded, and expiry (when there is any)
 * belongs to a retention policy on the collection, not to a DELETE verb.
 */
@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    /** Zero-based page number used when the caller does not ask for one. */
    static final int DEFAULT_PAGE = 0;

    /** Page size used when the caller does not ask for one - one screenful of trail, roughly. */
    static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Hard ceiling on page size. Without it, {@code ?size=1000000} is a request to load the entire collection into
     * memory and serialise it, which is a trivially available denial of service on the one collection guaranteed to
     * be the largest in the database.
     *
     * <p>Shared with every other paginated endpoint via {@link com.saasinvestigator.common.Paging}, so raising it
     * raises it once. Only the default page size above is specific to this endpoint.
     */
    static final int MAX_PAGE_SIZE = com.saasinvestigator.common.Paging.MAX_PAGE_SIZE;

    private final AuditService auditService;

    /**
     * @param auditService supplies the filtered query
     */
    public AuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Returns a page of audit entries, newest first.
     *
     * <p>Every filter is optional and they combine with AND. Sorting is not a parameter: the service forces
     * newest-first, because an audit trail in any other order is not what anyone reading one wants.
     *
     * @param actorUsername exact username to filter by, or {@code null}/blank for all actors. Exact rather than a
     *     substring match on purpose - a prefix search over a large collection cannot use the
     *     {@code (actorUsername, action, timestamp)} index, and "show me what this user did" is the actual question
     * @param action action to filter by, or {@code null} for all. An unrecognised value is a 400 listing the valid
     *     ones, handled centrally by the global exception handler
     * @param from inclusive lower bound on the entry timestamp (ISO-8601, e.g. {@code 2026-09-01T00:00:00Z})
     * @param to inclusive upper bound on the entry timestamp
     * @param page zero-based page number
     * @param size page size, capped at {@value #MAX_PAGE_SIZE}
     * @return the matching page of entries
     * @throws BadRequestException if the requested range is inverted, or paging arguments are nonsensical
     */
    @GetMapping
    public PageResponse<AuditLogResponse> search(
            @RequestParam(required = false) String actorUsername,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater.");
        }
        if (size < 1) {
            throw new BadRequestException("size must be at least 1.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            // Left alone this returns an empty page, which looks identical to "nothing happened then" -
            // the least helpful possible response to a swapped pair of dates.
            throw new BadRequestException("'from' must not be after 'to'.");
        }

        PageRequest pageable = PageRequest.of(
                page,
                Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "timestamp"));

        return PageResponse.from(
                auditService.search(actorUsername, action, from, to, pageable),
                AuditLogResponse::from);
    }
}
