package com.saasinvestigator.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The body of {@code POST /api/saas-products} and {@code PUT /api/saas-products/{id}}.
 *
 * <p>One record for both verbs, because a product is small enough that an update is a whole new statement of what it
 * should be rather than a patch. That choice is what makes {@link SourceConfigMapper}'s "absent {@code authToken}
 * means unchanged" rule necessary, and the two decisions have to be read together: the client resubmits every
 * source, including tokens it was never shown.
 *
 * <p>{@code sources} is {@link jakarta.validation.Valid @Valid} so each entry's own constraints run. Without it, Bean
 * Validation stops at the list and a source missing its {@code name} would reach {@code SourceConfigMapper} to be
 * rejected there - with a message about one source rather than a field-by-field list of everything wrong.
 *
 * @param name the display name, unique across products
 * @param description free-text context about the product; optional, and passed to the model as part of the prompt
 * @param sources the sources to track; may be empty on create, since configuring them is the next screen
 */
public record SaasProductRequest(

        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be 200 characters or fewer")
        String name,

        @Size(max = 2000, message = "description must be 2000 characters or fewer")
        String description,

        @Valid
        List<SourceConfigRequest> sources) {
}
