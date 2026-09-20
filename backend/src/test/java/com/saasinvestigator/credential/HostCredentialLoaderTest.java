package com.saasinvestigator.credential;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the quick-boot host-credential file loader.
 *
 * <p>Most of these are about the ways the file can be <em>wrong</em>, because the loader's contract is that none
 * of them can stop the application from starting. A misconfigured path on a developer's laptop must produce a
 * warning and an empty {@code Optional}, never a failed context - the mount is a convenience, and the other two
 * credential channels still work without it.
 */
class HostCredentialLoaderTest {

    @TempDir
    Path dir;

    private HostCredentialLoader loader(Path path) {
        return new HostCredentialLoader(path.toString(), false);
    }

    @Test
    void readsAKeyFromTheMountedFile() throws IOException {
        Path file = Files.writeString(dir.resolve("anthropic-api-key"), "sk-ant-from-the-host");

        HostCredentialLoader loader = loader(file);

        assertThat(loader.isPresent()).isTrue();
        assertThat(loader.anthropicKey()).contains("sk-ant-from-the-host");
        assertThat(loader.path()).isEqualTo(file.toString());
    }

    @Test
    void stripsTheTrailingNewlineThatEchoLeavesBehind() throws IOException {
        // `echo $ANTHROPIC_API_KEY > ~/.anthropic-key` is how most people will create this file, and it
        // appends a newline. A newline inside an Authorization header is a 401 that looks like a wrong key.
        Path file = Files.writeString(dir.resolve("key"), "  sk-ant-with-whitespace\n");

        assertThat(loader(file).anthropicKey()).contains("sk-ant-with-whitespace");
    }

    @Test
    void reportsNoKeyWhenTheFileDoesNotExist() {
        HostCredentialLoader loader = loader(dir.resolve("absent"));

        assertThat(loader.isPresent()).isFalse();
        assertThat(loader.anthropicKey()).isEmpty();
    }

    @Test
    void reportsNoKeyWhenNoPathIsConfigured() {
        assertThat(new HostCredentialLoader("", false).anthropicKey()).isEmpty();
        assertThat(new HostCredentialLoader(null, false).anthropicKey()).isEmpty();
    }

    @Test
    void reportsNoKeyWhenTheFileIsEmptyOrWhitespace() throws IOException {
        assertThat(loader(Files.writeString(dir.resolve("empty"), "")).anthropicKey()).isEmpty();
        assertThat(loader(Files.writeString(dir.resolve("blank"), " \n\t ")).anthropicKey()).isEmpty();
    }

    @Test
    void refusesADirectoryWithoutFailing() {
        // Mounting a directory where a file was expected is an easy Docker mistake: `-v ~/.creds:/run/...`
        // when the intent was `-v ~/.creds/key:/run/...`.
        HostCredentialLoader loader = loader(dir);

        assertThat(loader.isPresent()).isFalse();
    }

    @Test
    void refusesAFileTooLargeToBeAKey() throws IOException {
        // A whole config file, a log, or a mounted keyring would all read "successfully" and then be sent as a
        // bearer token. The size ceiling is what stops a mount mistake becoming a very strange API error.
        String tooBig = "x".repeat((int) HostCredentialLoader.MAX_FILE_BYTES + 1);

        assertThat(loader(Files.writeString(dir.resolve("big"), tooBig)).anthropicKey()).isEmpty();
    }

    @Test
    void ignoresAPerfectlyGoodFileWhenIgnoreHostCredentialsIsSet() throws IOException {
        Path file = Files.writeString(dir.resolve("key"), "sk-ant-should-not-be-read");

        HostCredentialLoader loader = new HostCredentialLoader(file.toString(), true);

        // IGNORE_HOST_CREDENTIALS exists so a container can be run deliberately without the host's key, to
        // check that the app behaves correctly when no credential is configured at all.
        assertThat(loader.anthropicKey()).isEmpty();
        assertThat(loader.isPresent()).isFalse();
    }

    @Test
    void doesNotFailOnAPathThatCannotBeParsed() {
        // NUL is not a legal path character on any supported platform, so this exercises the
        // InvalidPathException branch without depending on OS-specific filesystem behaviour.
        HostCredentialLoader loader = new HostCredentialLoader("/run/host-credentials/\u0000key", false);

        assertThat(loader.anthropicKey()).isEmpty();
    }
}
