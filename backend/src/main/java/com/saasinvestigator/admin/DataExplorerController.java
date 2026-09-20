package com.saasinvestigator.admin;

import com.saasinvestigator.common.PageResponse;
import com.saasinvestigator.common.Paging;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browse and edit this application's Mongo documents.
 *
 * <p>The guardrails, the reasons for them, and what is deliberately absent are all documented on
 * {@link DataExplorerService} and {@link SecretFieldMasker}. This class is the HTTP surface over them: four endpoints,
 * ADMIN-only, three of which only read.
 */
@RestController
@RequestMapping("/api/admin/data-explorer")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Data Explorer", description = "Browse this application's collections; secret fields are masked")
public class DataExplorerController {

    private final DataExplorerService explorer;

    /**
     * @param explorer performs the lookups and applies the guardrail
     */
    public DataExplorerController(DataExplorerService explorer) {
        this.explorer = explorer;
    }

    /**
     * @return the browsable collections, each with its document count and the names of its masked fields
     */
    @GetMapping("/collections")
    @Operation(summary = "List the browsable collections")
    public List<DataExplorerService.CollectionSummary> collections() {
        return explorer.collections();
    }

    /**
     * Returns a page of documents, secret fields replaced server-side.
     *
     * <p>{@code pageSize} is the documented parameter name for this endpoint, and {@code size} is accepted as a synonym
     * so that the page-size parameter is spelled the same way here as on every other paginated endpoint. Accepting both
     * costs one line; making a client remember which of two spellings this one endpoint wants costs more than that
     * every time somebody uses it. If both arrive, {@code pageSize} wins, because it is the name in the contract.
     *
     * @param name the collection to browse
     * @param page zero-based page number
     * @param pageSize how many documents per page
     * @param size synonym for {@code pageSize}
     * @return the page, with every secret value replaced by {@value SecretFieldMasker#MASK}
     */
    @GetMapping("/collections/{name}/documents")
    @Operation(summary = "Page through a collection's documents with secret fields masked")
    public PageResponse<Map<String, Object>> documents(
            @PathVariable String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Integer size) {

        int requested = pageSize != null ? pageSize
                : size != null ? size
                : Paging.DEFAULT_PAGE_SIZE;

        // Routed through Paging so this endpoint shares the same ceiling and the same rejection of nonsense values as
        // every other paginated endpoint, even though it does its own skip/limit rather than using a Pageable.
        var pageable = Paging.of(page, requested);
        return explorer.documents(name, pageable.getPageNumber(), pageable.getPageSize());
    }

    /**
     * @param name the collection
     * @param id the document's id
     * @return the single document, secret fields masked
     */
    @GetMapping("/collections/{name}/documents/{id}")
    @Operation(summary = "Read one document with secret fields masked")
    public Map<String, Object> document(@PathVariable String name, @PathVariable String id) {
        return explorer.document(name, id);
    }

    /**
     * Updates the non-secret fields of one document.
     *
     * <p>The body is the set of fields to change, not the whole document - see
     * {@link DataExplorerService#update(String, String, Document)}. A body containing a masked field, {@code _id}, or
     * {@code _class} is rejected with a 400 naming where that change belongs instead, rather than being ignored.
     *
     * <p>Typed as a raw {@code Document} rather than a {@code Map} so it arrives as the same BSON type the service
     * inspects and writes, with no conversion in between that could change what a value is on the way through.
     *
     * @param name the collection
     * @param id the document's id
     * @param updates the fields to set
     * @return the updated document, masked
     */
    @PutMapping("/collections/{name}/documents/{id}")
    @Operation(summary = "Update a document's non-secret fields")
    public Map<String, Object> update(@PathVariable String name, @PathVariable String id,
                                      @RequestBody Document updates) {
        return explorer.update(name, id, updates);
    }
}
