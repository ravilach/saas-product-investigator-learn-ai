package com.saasinvestigator.product;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository over the {@code saas_products} collection. */
public interface SaasProductRepository extends MongoRepository<SaasProduct, String> {

    /**
     * The dashboard's list, newest first.
     *
     * <p>Paginated rather than {@code findAll()}: the number of products is operator-controlled and small today,
     * but an endpoint that returns an unbounded collection is a decision that only looks fine until it doesn't,
     * and each product carries its full source list with it.
     *
     * @param pageable page, size, and sort
     * @return one page of products
     */
    Page<SaasProduct> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Looks a product up by display name, used to reject duplicates on create.
     *
     * <p>Not backed by a unique index: names are a convenience, and uniqueness is enforced in the service so the
     * rejection can be a 409 with a readable message rather than a driver exception. See
     * {@code MongoIndexInitializer} for which constraints are genuinely structural.
     *
     * @param name the display name
     * @return the product, or empty
     */
    Optional<SaasProduct> findByName(String name);

    /**
     * @param name the display name
     * @return {@code true} if a product already uses this name
     */
    boolean existsByName(String name);
}
