package com.saasinvestigator.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The single JSON envelope every paginated endpoint in this API returns.
 *
 * <p>Deliberately not Spring Data's {@code Page}. Serialising {@code PageImpl} directly produces a much larger
 * object full of Spring-internal structure ({@code pageable}, {@code sort.sorted}, {@code sort.unsorted}...) whose
 * shape has changed between Spring Data versions - Spring itself warns about relying on it. The frontend would end
 * up coupled to that shape, and a dependency upgrade could silently reshape a response. Seven explicit fields are
 * cheap and stable.
 *
 * @param <T> the response DTO type held in {@code content}
 * @param content this page's items
 * @param page zero-based page number
 * @param size requested page size
 * @param totalElements total matching items across all pages
 * @param totalPages total number of pages
 * @param first whether this is the first page
 * @param last whether this is the last page
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    /**
     * Converts a repository page of entities into a page of response DTOs.
     *
     * <p>Mapping happens here rather than in each controller so that no endpoint can accidentally serialise an
     * entity - and with it a field like {@code passwordHash} or {@code apiKeyEncrypted} - by returning the page it
     * got from the service.
     *
     * @param page the page of entities
     * @param mapper converts one entity to its response DTO
     * @param <E> the entity type
     * @param <T> the response DTO type
     * @return the equivalent page of DTOs
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
