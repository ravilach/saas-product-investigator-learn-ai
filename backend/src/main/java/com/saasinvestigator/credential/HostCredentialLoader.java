package com.saasinvestigator.credential;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads a bind-mounted Anthropic API key from the host, once, at startup.
 *
 * <p>This is the quick-boot convenience path: put your key in a file, mount it, and the container has a
 * working system-wide Anthropic credential without an env var and without logging in to paste one.
 *
 * <pre>{@code
 * docker run -p 8080:8080 \
 *   -v ~/.anthropic/api-key.txt:/run/host-credentials/anthropic-api-key:ro \
 *   saas-investigator
 * }</pre>
 *
 * <p>It is the <em>lowest</em> priority system-wide source, below the {@code ANTHROPIC_API_KEY} env var and
 * the Admin Console override, because it is the least explicit of the three: a file left mounted from a
 * previous experiment should not quietly outrank a key someone deliberately configured.
 *
 * <p><b>Three deliberate choices worth knowing about:</b>
 *
 * <ul>
 *   <li><b>Nothing here can stop the app starting.</b> A missing file, an empty file, a directory where a
 *       file was expected, a permissions error - each logs at most a warning and resolves to empty. This is
 *       an optional convenience; failing a boot over it would turn a nice-to-have into an outage, and the
 *       build prompt calls this out explicitly.
 *   <li><b>It reads once, at construction.</b> Constructor rather than an {@code ApplicationRunner} so that
 *       the value is already available to anything that resolves a credential during startup, with no bean
 *       ordering to get wrong. The trade-off is that changing the mounted file needs a restart, which
 *       matches how bind-mounted config normally behaves and is what "at startup" in the build prompt says.
 *   <li><b>Anthropic only.</b> There is one fixed path and it is the Anthropic one. The spec defines this
 *       feature for the system-wide default provider, which is always Anthropic; inventing a parallel
 *       OpenAI path nobody asked for would add a second undocumented file to reason about.
 * </ul>
 *
 * <p>Set {@code IGNORE_HOST_CREDENTIALS=true} to skip the file even when it is mounted - the "I know it is
 * there and I do not want it" case, which otherwise has no answer short of unmounting.
 */
@Component
public class HostCredentialLoader {

    private static final Logger log = LoggerFactory.getLogger(HostCredentialLoader.class);

    /**
     * Sanity ceiling on the file's size. An API key is on the order of 100 bytes; anything approaching this
     * is a wrong file, most plausibly a whole credentials JSON or a log. Reading it would put its contents
     * where a key belongs, so the file is rejected instead - and 4 KiB is small enough that this cannot be
     * used to make the app read something large.
     */
    static final long MAX_FILE_BYTES = 4_096;

    private final Optional<String> anthropicKey;
    private final String configuredPath;
    private final boolean ignored;

    /**
     * @param configuredPath where to look, from {@code HOST_CREDENTIAL_ANTHROPIC_PATH}; defaults to
     *     {@code /run/host-credentials/anthropic-api-key}
     * @param ignored {@code true} to skip the file entirely, from {@code IGNORE_HOST_CREDENTIALS}
     */
    public HostCredentialLoader(
            @Value("${app.host-credentials.anthropic-key-path:}") String configuredPath,
            @Value("${app.host-credentials.ignore:false}") boolean ignored) {
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
        this.ignored = ignored;
        this.anthropicKey = load();
    }

    /**
     * The key read at startup.
     *
     * @return the trimmed file contents, or empty if the file was absent, ignored, unreadable, blank, or
     *     implausibly large
     */
    public Optional<String> anthropicKey() {
        return anthropicKey;
    }

    /**
     * @return {@code true} if a usable key was loaded from the host mount
     */
    public boolean isPresent() {
        return anthropicKey.isPresent();
    }

    /**
     * @return the path that was checked, for the Admin Console's Secrets tab to show where a
     *     {@code HOST_MOUNT} key came from - a path is not a secret, and "some file somewhere" is not a
     *     useful thing to tell an operator
     */
    public String path() {
        return configuredPath;
    }

    private Optional<String> load() {
        if (ignored) {
            log.info("IGNORE_HOST_CREDENTIALS is set, so the host-mounted Anthropic key file is not read.");
            return Optional.empty();
        }
        if (configuredPath.isEmpty()) {
            return Optional.empty();
        }
        try {
            Path file = Path.of(configuredPath);
            if (!Files.exists(file)) {
                // Debug, not warn: not mounting the file is the normal case for every deployment that
                // uses an env var or the Admin Console. A warning here would fire on every healthy boot.
                log.debug("No host-mounted Anthropic key at {}.", configuredPath);
                return Optional.empty();
            }
            if (!Files.isRegularFile(file)) {
                log.warn("Host credential path {} exists but is not a regular file; ignoring it.", configuredPath);
                return Optional.empty();
            }
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                log.warn("Host credential file {} is {} bytes, which is far larger than an API key. "
                        + "Ignoring it - this file should contain just the key and nothing else.",
                        configuredPath, size);
                return Optional.empty();
            }
            // Trimmed because the overwhelmingly common way to create this file is `echo $KEY > file`,
            // which appends a newline. A trailing newline in an Authorization header is a 401 that looks
            // like a wrong key, and debugging that costs more than this one call saves.
            String key = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (key.isEmpty()) {
                log.warn("Host credential file {} is empty; ignoring it.", configuredPath);
                return Optional.empty();
            }
            log.info("Loaded a system-wide Anthropic API key from the host-mounted file {}. It is the "
                    + "lowest-priority source: ANTHROPIC_API_KEY and an Admin Console override both "
                    + "take precedence.", configuredPath);
            return Optional.of(key);
        } catch (IOException | InvalidPathException | SecurityException e) {
            // Deliberately does not rethrow. See the class Javadoc: this path must never be able to
            // prevent a boot. The message names the file, because "check your mount" is the whole fix.
            log.warn("Could not read the host credential file {} ({}). Continuing without it.",
                    configuredPath, e.getMessage());
            return Optional.empty();
        }
    }
}
