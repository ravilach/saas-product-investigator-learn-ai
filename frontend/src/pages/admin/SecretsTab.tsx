import { useState } from 'react';
import {
  useClearJwtSecret,
  useClearSystemCredential,
  useJwtSecretStatus,
  useSetJwtSecret,
  useSetSystemCredential,
  useSystemCredentials,
} from '../../api/admin';
import type { JwtSecretSource, LlmProviderType, SystemCredentialStatus } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Modal } from '../../components/Modal';
import { ErrorState } from '../../components/states/ErrorState';
import { SkeletonText } from '../../components/states/Skeleton';
import { useToast } from '../../components/toast/ToastProvider';
import { providerLabel } from '../../utils/format';
import styles from './Admin.module.css';

/** The providers a system-wide override can be set for. */
const PROVIDERS: LlmProviderType[] = ['ANTHROPIC', 'OPENAI'];

/** The shortest API key the backend accepts, from `CredentialRequest`'s `@Size`. */
const MIN_API_KEY_LENGTH = 8;

/** The shortest JWT signing secret the backend accepts, from `JwtSecretRequest`'s `@Size`. */
const MIN_JWT_SECRET_LENGTH = 32;

/** How each credential source reads in the UI. */
const SOURCE_LABELS: Record<string, string> = {
  PERSONAL: "the user's own key",
  OVERRIDE: 'this admin override',
  ENV_VAR: 'an environment variable',
  HOST_MOUNT: 'a file mounted on the host',
};

/** How each JWT secret source reads in the UI. */
const JWT_SOURCE_LABELS: Record<JwtSecretSource, string> = {
  ADMIN_OVERRIDE: 'An override set here',
  ENV_VAR: 'The JWT_SECRET environment variable',
  AUTO_GENERATED: 'A secret this installation generated for itself',
};

/**
 * The Admin Console's Secrets tab: system-wide provider keys, and the JWT signing secret.
 *
 * Nothing on this page can display a secret, and that is not a UI choice - the API has no endpoint
 * that returns one. Provider keys come back as a `last4` and a source; the JWT secret comes back as a
 * source and nothing else, not even a masked tail, because unlike an API key there is no operational
 * question a partial signing secret would answer.
 *
 * @returns the secrets tab
 */
export function SecretsTab() {
  const credentials = useSystemCredentials();

  return (
    <div className="stack">
      <section className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-2)' }}>
          System AI provider keys
        </h2>
        <p className="muted" style={{ fontSize: 'var(--text-sm)' }}>
          The fallback used when a user has no personal key of their own. A key set here is encrypted
          before it is stored and is never returned - only its last four characters and where it came
          from.
        </p>

        {credentials.isPending ? (
          <SkeletonText lines={4} label="Loading system credentials" />
        ) : credentials.isError ? (
          <ErrorState
            error={credentials.error}
            title="Could not load system credentials"
            onRetry={() => credentials.refetch()}
          />
        ) : (
          <div style={{ marginTop: 'var(--space-4)' }}>
            {PROVIDERS.map((provider) => (
              <SystemCredentialRow
                key={provider}
                provider={provider}
                status={credentials.data?.find((entry) => entry.provider === provider)}
              />
            ))}
          </div>
        )}
      </section>

      <JwtSecretSection />
    </div>
  );
}

/**
 * One provider's system-wide override row.
 *
 * @param props.provider which provider
 * @param props.status its current status, or `undefined` if the API did not list it
 * @returns the row
 */
function SystemCredentialRow({
  provider,
  status,
}: {
  provider: LlmProviderType;
  status: SystemCredentialStatus | undefined;
}) {
  const [apiKey, setApiKey] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const { showError, showSuccess } = useToast();
  const save = useSetSystemCredential();
  const clear = useClearSystemCredential();

  const label = providerLabel(provider);
  const configured = status?.configured ?? false;
  // An override is what this row can clear. A key resolving from the environment or a host mount is
  // not something the API can remove, so the Clear button has to be gated on the source, not on
  // whether the provider is configured at all.
  const hasOverride = status?.source === 'OVERRIDE';
  const tooShort = apiKey.trim().length < MIN_API_KEY_LENGTH;

  const onSave = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (tooShort) return;
    try {
      await save.mutateAsync({ provider, apiKey: apiKey.trim() });
      setApiKey('');
      setSubmitted(false);
      showSuccess(`The system ${label} key was saved.`);
    } catch (cause) {
      showError(cause, `Could not save the system ${label} key`);
    }
  };

  const onClear = async () => {
    try {
      await clear.mutateAsync(provider);
      // Deliberately not "this provider is now unconfigured": clearing an override falls back to the
      // environment variable or a host mount, and the refetched source is what says which.
      showSuccess(`The ${label} override was cleared.`);
    } catch (cause) {
      showError(cause, `Could not clear the ${label} override`);
    }
  };

  return (
    <div className={styles.secretRow}>
      <div className={styles.secretHeader}>
        <span className={styles.secretName}>
          {label}
          {configured ? (
            <span className="pill pill-success">Configured</span>
          ) : (
            <span className="pill pill-warning">Not configured</span>
          )}
        </span>

        {hasOverride ? (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={onClear}
            disabled={clear.isPending}
          >
            {clear.isPending ? 'Clearing…' : 'Clear override'}
          </button>
        ) : null}
      </div>

      <p className="muted" style={{ fontSize: 'var(--text-sm)', margin: 0 }}>
        {configured ? (
          <>
            Resolving from {status?.source ? SOURCE_LABELS[status.source] ?? status.source : 'an unknown source'}
            {status?.last4 ? (
              <>
                , ending <span className={styles.mono}>{status.last4}</span>
              </>
            ) : null}
            .
          </>
        ) : (
          <>No key resolves for this provider. Runs that need it will fail with a clear message.</>
        )}
      </p>

      <form className={styles.secretForm} onSubmit={onSave}>
        <div style={{ flex: '1 1 260px', minWidth: 0 }}>
          <label className="sr-only" htmlFor={`system-key-${provider}`}>
            System {label} API key
          </label>
          <input
            id={`system-key-${provider}`}
            className={`input ${styles.secretInput}`}
            type="password"
            autoComplete="off"
            spellCheck={false}
            value={apiKey}
            onChange={(event) => setApiKey(event.target.value)}
            placeholder={hasOverride ? 'Enter a new key to replace the override' : `System ${label} API key`}
            aria-invalid={submitted && tooShort}
            disabled={save.isPending}
          />
          {submitted && tooShort ? (
            <p className="field-error">
              That is shorter than {MIN_API_KEY_LENGTH} characters, so it is not a real API key.
            </p>
          ) : null}
        </div>
        <button
          type="submit"
          className="btn btn-secondary"
          disabled={save.isPending || !apiKey.trim()}
        >
          {save.isPending ? 'Saving…' : hasOverride ? 'Replace override' : 'Set override'}
        </button>
      </form>
    </div>
  );
}

/**
 * The JWT signing secret section, and the confirmation in front of every change to it.
 *
 * @returns the section
 */
function JwtSecretSection() {
  const status = useJwtSecretStatus();
  const [pendingSecret, setPendingSecret] = useState<string | null>(null);
  const [confirmingClear, setConfirmingClear] = useState(false);

  return (
    <section className="card">
      <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-2)' }}>
        JWT signing secret
      </h2>
      <p className="muted" style={{ fontSize: 'var(--text-sm)' }}>
        The key that signs and verifies every session token. It is never returned in any form - not
        even a masked tail - so all this page can tell you is where it currently comes from.
      </p>

      {status.isPending ? (
        <SkeletonText lines={2} label="Loading the signing secret's status" />
      ) : status.isError ? (
        <ErrorState
          error={status.error}
          title="Could not read the signing secret's status"
          onRetry={() => status.refetch()}
        />
      ) : status.data ? (
        <div className="stack" style={{ marginTop: 'var(--space-4)' }}>
          <p style={{ fontSize: 'var(--text-sm)', margin: 0 }}>
            <strong>Source:</strong> {JWT_SOURCE_LABELS[status.data.source] ?? status.data.source}
          </p>

          <p className={styles.danger}>
            Changing this signs out <strong>every active session, including your own</strong>. Tokens
            are stateless and signed, so changing the key that verifies them is what invalidating them
            means - there is no way to exempt anyone, and everyone will have to sign in again.
          </p>

          <JwtSecretForm onRequestChange={setPendingSecret} />

          {status.data.source === 'ADMIN_OVERRIDE' ? (
            <div>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => setConfirmingClear(true)}
              >
                Clear the override
              </button>
              <p className="hint">
                Reverts to the JWT_SECRET environment variable, or to this installation's generated
                secret. This also signs everyone out.
              </p>
            </div>
          ) : null}
        </div>
      ) : null}

      {pendingSecret !== null ? (
        <JwtChangeDialog
          mode="set"
          secret={pendingSecret}
          onClose={() => setPendingSecret(null)}
        />
      ) : null}

      {confirmingClear ? (
        <JwtChangeDialog mode="clear" onClose={() => setConfirmingClear(false)} />
      ) : null}
    </section>
  );
}

/**
 * The form that collects a new signing secret.
 *
 * It does not submit anything. It validates, then hands the value to the confirmation dialog, which is
 * the only thing that calls the API - so there is no path to changing the secret that skips the
 * confirmation.
 *
 * @param props.onRequestChange called with the validated secret when the admin asks to change it
 * @returns the form
 */
function JwtSecretForm({ onRequestChange }: { onRequestChange: (secret: string) => void }) {
  const [secret, setSecret] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const tooShort = secret.length < MIN_JWT_SECRET_LENGTH;

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (tooShort) return;
    onRequestChange(secret);
    // Cleared here rather than after the change succeeds: the change signs this session out, so there
    // is no "after" in which to clear it, and leaving a signing secret sitting in a DOM input while
    // the app redirects to the login page is exactly the wrong thing to do.
    setSecret('');
    setSubmitted(false);
  };

  return (
    <form className={styles.secretForm} onSubmit={onSubmit}>
      <div style={{ flex: '1 1 260px', minWidth: 0 }}>
        <label className="label" htmlFor="jwt-secret">
          New signing secret
        </label>
        <input
          id="jwt-secret"
          className={`input ${styles.secretInput}`}
          type="password"
          autoComplete="off"
          spellCheck={false}
          value={secret}
          onChange={(event) => setSecret(event.target.value)}
          aria-invalid={submitted && tooShort}
        />
        <p className="hint">
          At least {MIN_JWT_SECRET_LENGTH} characters. Use a long random string, not a passphrase.
        </p>
        {submitted && tooShort ? (
          <p className="field-error">
            The secret must be at least {MIN_JWT_SECRET_LENGTH} characters - it is what stops anyone
            from minting their own admin token.
          </p>
        ) : null}
      </div>
      <button type="submit" className="btn btn-danger" disabled={!secret}>
        Change secret…
      </button>
    </form>
  );
}

/**
 * The confirmation in front of both JWT secret changes.
 *
 * Its job is to state the consequence in the words the build prompt asks for - every active session
 * including the admin's own - and then to carry it out. It signs this session out itself on success
 * rather than waiting for the next request's 401, because the token is already dead and a deliberate
 * sign-out with an explanation beats an expiry message the user did not ask for.
 *
 * @param props.mode whether a new secret is being set, or the override cleared
 * @param props.secret the new secret, for `set`
 * @param props.onClose dismisses the dialog
 * @returns the dialog
 */
function JwtChangeDialog({
  mode,
  secret,
  onClose,
}: {
  mode: 'set' | 'clear';
  secret?: string;
  onClose: () => void;
}) {
  const { logout } = useAuth();
  const { showError } = useToast();
  const setSecret = useSetJwtSecret();
  const clearSecret = useClearJwtSecret();

  const busy = setSecret.isPending || clearSecret.isPending;

  const onConfirm = async () => {
    try {
      if (mode === 'set') await setSecret.mutateAsync(secret ?? '');
      else await clearSecret.mutateAsync();
      // No success toast: `logout` clears the query cache and navigates to the login page, and a toast
      // congratulating someone on an action whose whole effect is to sign them out reads as noise.
      logout();
    } catch (cause) {
      showError(cause, 'Could not change the signing secret');
      onClose();
    }
  };

  return (
    <Modal
      title={mode === 'set' ? 'Change the JWT signing secret?' : 'Clear the JWT secret override?'}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="btn btn-danger" onClick={onConfirm} disabled={busy}>
            {busy ? 'Applying…' : mode === 'set' ? 'Change it and sign everyone out' : 'Clear it and sign everyone out'}
          </button>
        </>
      }
    >
      <p>
        This signs out <strong>every active session, including your own</strong>. You will be returned
        to the sign-in page immediately and will need to sign in again. Anyone else using the
        application will be signed out on their next action, mid-task.
      </p>
      <p style={{ marginBottom: 0 }}>
        {mode === 'set'
          ? 'Any run in progress keeps going server-side, but its live view will stop updating.'
          : 'The secret reverts to the JWT_SECRET environment variable, or to the one this installation generated for itself.'}
      </p>
    </Modal>
  );
}

export default SecretsTab;
