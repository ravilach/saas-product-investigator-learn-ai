package com.saasinvestigator.product;

import com.saasinvestigator.common.PageResponse;
import com.saasinvestigator.common.Paging;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD for the tracked SaaS products.
 *
 * <h2>Who may do what, and why the split is here</h2>
 *
 * <p>Reading is open to both roles; every mutation is {@code ADMIN}. That is the exact line {@code READ_ONLY} is
 * meant to draw - a read-only user can run an analysis and read every report, but cannot change <em>what</em> is
 * being tracked or which URLs this server will fetch. Adding a source is closer to editing the deployment's
 * configuration than to using the product, which is why it sits with the admin actions rather than with Run.
 *
 * <p>The annotations are per-method rather than class-level on purpose: a class-level {@code hasRole('ADMIN')} with
 * three methods overriding it back down to {@code isAuthenticated()} reads as though the read endpoints were an
 * exception being carved out, when the opposite is true.
 */
@RestController
@RequestMapping("/api/saas-products")
@Tag(name = "SaaS Products", description = "The tracked products and their sources")
public class SaasProductController {

    private final SaasProductService service;

    /**
     * @param service holds the validation, cascade and audit behaviour
     */
    public SaasProductController(SaasProductService service) {
        this.service = service;
    }

    /**
     * Creates a product.
     *
     * @param request the product and its sources
     * @return the stored product, tokens masked
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public SaasProductResponse create(@Valid @RequestBody SaasProductRequest request) {
        return service.create(request);
    }

    /**
     * Lists products, newest first.
     *
     * @param page zero-based page number
     * @param size page size, capped by {@link Paging#MAX_PAGE_SIZE}
     * @return one page of products, each with its derived {@code lastRun}
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<SaasProductResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_PAGE_SIZE) int size) {
        return PageResponse.from(service.list(Paging.of(page, size)), response -> response);
    }

    /**
     * Returns one product in full, including its sources.
     *
     * <p>Readable by both roles, and safe to be: {@link SourceConfigMapper} replaces each MCP {@code authToken} with
     * its last four characters on the way out, so there is no version of this response that carries a credential.
     *
     * @param id the product id
     * @return the product
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public SaasProductResponse get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Replaces a product's name, description and sources.
     *
     * @param id the product id
     * @param request the new state of the product
     * @return the updated product
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SaasProductResponse update(@PathVariable String id, @Valid @RequestBody SaasProductRequest request) {
        return service.update(id, request);
    }

    /**
     * Deletes a product along with its snapshots, reports and run records.
     *
     * @param id the product id
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
