package com.saasinvestigator.user;

import com.saasinvestigator.config.MongoIndexInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Seeds a single default admin the first time the application starts against an empty {@code users}
 * collection.
 *
 * <p>This exists so a fresh container is usable immediately - {@code docker run -p 8080:8080 <image>} and
 * you can log in - rather than requiring a bootstrap step before anyone can reach the UI.
 *
 * <p>The password is the publicly documented value {@code admin}, which is why this class logs a
 * deliberately loud multi-line WARNING rather than a tidy one-liner: a warning that scrolls past unnoticed
 * would leave a known credential in place. It is also why the JWT signing secret is emphatically <em>not</em>
 * derived from this password - see {@code JwtSecretResolver}.
 */
@Component
@Order(AdminUserSeeder.ORDER)
public class AdminUserSeeder implements ApplicationRunner {

    /** Runs after {@link MongoIndexInitializer}, so the unique {@code username} index already exists. */
    public static final int ORDER = MongoIndexInitializer.ORDER + 10;

    /** The seeded account's login identifier. */
    public static final String DEFAULT_USERNAME = "admin";

    /** The seeded account's initial password. Known to anyone who has read this project's docs. */
    public static final String DEFAULT_PASSWORD = "admin";

    private static final String DEFAULT_EMAIL = "admin@localhost";

    private static final Logger log = LoggerFactory.getLogger(AdminUserSeeder.class);

    private final UserRepository userRepository;
    private final UserService userService;

    /**
     * @param userRepository used to test whether any user exists yet
     * @param userService performs the create, so hashing and validation follow the same path as any other
     *     user creation
     */
    public AdminUserSeeder(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        try {
            userService.create("Admin", "User", DEFAULT_USERNAME, DEFAULT_EMAIL, DEFAULT_PASSWORD,
                    Role.ADMIN);
        } catch (DuplicateKeyException e) {
            // Another replica seeded it between the count above and this insert. Nothing to do - the
            // unique index did its job and there is exactly one admin, which is the desired outcome.
            log.info("Default admin was seeded concurrently by another instance.");
            return;
        }
        warnAboutDefaultCredential();
    }

    private void warnAboutDefaultCredential() {
        log.warn("""

                ===============================================================================
                 DEFAULT ADMIN CREDENTIAL CREATED
                ===============================================================================
                 The users collection was empty, so a default administrator has been seeded:

                     username: {}
                     password: {}

                 This password is published in this project's documentation, so it is public.
                 Rotate it before this instance is reachable by anyone else:

                     log in, then Admin Console -> Users -> Reset password

                 See /docs/SETUP.md for the full rationale.
                ===============================================================================
                """, DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }
}
