package com.saasinvestigator.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saasinvestigator.systemconfig.SystemConfigDocument;
import com.saasinvestigator.systemconfig.SystemConfigService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the JWT signing-secret resolution order.
 *
 * <p>This order is security-relevant rather than cosmetic: if the {@code JWT_SECRET} env var could quietly
 * beat an admin override, an admin who rotated the secret to sign everyone out would believe they had done so
 * while every existing token stayed valid.
 */
@ExtendWith(MockitoExtension.class)
class JwtSecretResolverTest {

    @Mock
    private SystemConfigService systemConfig;

    @Test
    void adminOverrideWinsOverTheEnvVar() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.of("override-secret"));
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "env-var-secret");

        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.ADMIN_OVERRIDE);
        // The persisted auto-generated secret is never even read when an override is present.
        verify(systemConfig, never()).getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET);
    }

    @Test
    void envVarWinsWhenThereIsNoOverride() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty());
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "env-var-secret");

        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.ENV_VAR);
        verify(systemConfig, never()).putSecretIfAbsent(anyString(), anyString());
    }

    @Test
    void reusesThePersistedSecretRatherThanGeneratingAFreshOne() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty());
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET))
                .thenReturn(Optional.of("previously-generated-secret"));
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "");

        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.AUTO_GENERATED);
        // This is what stops a restart from logging everyone out.
        verify(systemConfig, never()).putSecretIfAbsent(anyString(), anyString());
    }

    @Test
    void generatesAndPersistsASecretOnFirstBoot() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty());
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET))
                .thenReturn(Optional.empty());
        when(systemConfig.putSecretIfAbsent(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "");

        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.AUTO_GENERATED);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(systemConfig).putSecretIfAbsent(
                org.mockito.ArgumentMatchers.eq(SystemConfigDocument.KEY_JWT_SIGNING_SECRET),
                value.capture());
        // 32 random bytes, base64-encoded: 44 characters including one '=' of padding.
        assertThat(value.getValue()).hasSize(44);
    }

    @Test
    void adoptsTheValueAnotherReplicaWonTheRaceWith() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty());
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET))
                .thenReturn(Optional.empty());
        when(systemConfig.putSecretIfAbsent(anyString(), anyString()))
                .thenReturn("the-other-replicas-secret");
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "");

        // Both replicas must end up on the same key, or each rejects the other's tokens.
        JwtSecretResolver sameSecretDifferentInstance = new JwtSecretResolver(systemConfig, "");
        assertThat(resolver.activeKey().getEncoded())
                .isEqualTo(sameSecretDifferentInstance.activeKey().getEncoded());
    }

    @Test
    void derivesAUsableHmacKeyFromAShortSecret() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty());
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "short");

        // HMAC-SHA256 needs 256 bits; a hand-set passphrase shorter than that must still work.
        assertThat(resolver.activeKey().getEncoded()).hasSize(32);
    }

    @Test
    void blankEnvVarIsTreatedAsUnset() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty());
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET))
                .thenReturn(Optional.of("persisted"));
        // A Kubernetes Secret or ECS task definition that maps an empty string is a very easy mistake to
        // make; treating it as "set" would sign every token with the empty secret.
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "   ");

        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.AUTO_GENERATED);
    }

    @Test
    void invalidateCacheForcesAReResolve() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("newly-set-override"));
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "env-var-secret");

        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.ENV_VAR);
        resolver.invalidateCache();
        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.ADMIN_OVERRIDE);
    }

    @Test
    void applyingAnOverrideTakesEffectImmediatelyRatherThanInFifteenSeconds() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("a-newly-applied-override-secret"));
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "env-var-secret");
        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.ENV_VAR);

        resolver.applyOverride("a-newly-applied-override-secret");

        // This is why writing and invalidating are one method. The failure mode of doing them separately is
        // invisible: the write succeeds, the cache is not dropped, and this instance keeps accepting tokens
        // it has just decided are invalid for up to the cache TTL.
        verify(systemConfig).putSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE,
                "a-newly-applied-override-secret");
        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.ADMIN_OVERRIDE);
    }

    @Test
    void applyingAnOverrideTrimsItSoAPastedNewlineDoesNotChangeTheKey() {
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "env-var-secret");

        resolver.applyOverride("  a-secret-pasted-with-trailing-whitespace\n");

        // Two admins pasting the same secret from the same password manager must end up with the same key,
        // whether or not their clipboard picked up the newline.
        verify(systemConfig).putSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE,
                "a-secret-pasted-with-trailing-whitespace");
    }

    @Test
    void refusesABlankOverride() {
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "env-var-secret");

        // Belt and braces behind the request-level @NotBlank: an empty override would sign every token with
        // the empty string while the Admin Console reported ADMIN_OVERRIDE, which is the worst combination of
        // "looks locked down" and "is not".
        assertThatThrownBy(() -> resolver.applyOverride("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.applyOverride(null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(systemConfig, never()).putSecret(anyString(), anyString());
    }

    @Test
    void clearingAnOverrideRevealsTheEnvVarAgainAndReportsThatSomethingChanged() {
        when(systemConfig.exists(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE)).thenReturn(true);
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.of("the-override-being-cleared"))
                .thenReturn(Optional.empty());
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "env-var-secret");
        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.ADMIN_OVERRIDE);

        assertThat(resolver.clearOverride()).isTrue();

        verify(systemConfig).delete(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE);
        assertThat(resolver.activeSource()).isEqualTo(JwtSecretSource.ENV_VAR);
    }

    @Test
    void clearingAnOverrideThatWasNotThereReportsNoChange() {
        when(systemConfig.exists(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE)).thenReturn(false);
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "env-var-secret");

        // The distinction is what keeps "every session was just invalidated" out of the audit log on a click
        // that invalidated nothing.
        assertThat(resolver.clearOverride()).isFalse();
        assertThat(resolver.hasOverride()).isFalse();
    }

    @Test
    void anOverrideProducesADifferentKeyFromTheEnvVarItReplaces() {
        when(systemConfig.getSecret(SystemConfigDocument.KEY_JWT_SIGNING_SECRET_OVERRIDE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("a-newly-applied-override-secret"));
        JwtSecretResolver resolver = new JwtSecretResolver(systemConfig, "env-var-secret");
        byte[] before = resolver.activeKey().getEncoded();

        resolver.applyOverride("a-newly-applied-override-secret");

        // Everything the feature promises rests on this one fact: the key that verifies signatures is not the
        // key that produced the signatures on every token already in a browser. Asserting the source changed
        // would not catch a resolver that reported a new source while still returning the old key.
        assertThat(resolver.activeKey().getEncoded()).isNotEqualTo(before);
    }
}
