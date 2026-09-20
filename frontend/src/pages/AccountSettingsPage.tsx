import { useState } from 'react';
import {
  useDeleteMyCredential,
  useMyCredentials,
  useSaveMyCredential,
  useSetPreferredProvider,
} from '../api/account';
import type { CredentialStatus, LlmProviderType } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ErrorState } from '../components/states/ErrorState';
import { SkeletonText } from '../components/states/Skeleton';
import { useToast } from '../components/toast/ToastProvider';
import { usePageTitle } from '../layout/usePageTitle';
import { formatDate, providerLabel } from '../utils/format';
import styles from './AccountSettings.module.css';

/** The providers a personal key can be stored for, in the order the page lists them. */
const PROVIDERS: LlmProviderType[] = ['ANTHROPIC', 'OPENAI'];

/**
 * Account Settings: the signed-in user's own details and their personal BYOK provider keys.
 *
 * The details block is read-only, and that is the API's shape rather than an omission here - there is
 * no self-update endpoint, only the admin-only `/api/users` ones, so a name change is something an
 * admin does. Showing editable inputs that had nowhere to submit to would be worse than showing the
 * values and saying who can change them.
 *
 * @returns the account settings page
 */
export function AccountSettingsPage() {
  const { user, applyUser } = useAuth();
  const { showError, showSuccess } = useToast();
  usePageTitle('Account Settings');

  const credentials = useMyCredentials();
  const setPreferred = useSetPreferredProvider();

  /**
   * Changes which provider this user's runs go to.
   *
   * @param provider the provider, or `null` to fall back to the system default
   */
  const onChoosePreferred = async (provider: LlmProviderType | null) => {
    try {
      const updated = await setPreferred.mutateAsync(provider);
      // Handed to the auth context rather than a query cache: the signed-in user lives there and in
      // the persisted session, so skipping this would show the new choice here and the old one in the
      // header until the next reload.
      applyUser(updated);
      showSuccess(
        provider
          ? `Runs will use your ${providerLabel(provider)} key.`
          : 'Runs will use whichever provider the system has configured.',
      );
    } catch (cause) {
      showError(cause, 'Could not change your preferred provider');
    }
  };

  if (!user) {
    // The route is behind `RequireAuth`, so this is unreachable in practice - but `user` is nullable
    // and an early return is cheaper than asserting it away.
    return <SkeletonText lines={3} label="Loading your account" />;
  }

  const statusFor = (provider: LlmProviderType): CredentialStatus | undefined =>
    credentials.data?.find((entry) => entry.provider === provider);

  const configuredProviders = PROVIDERS.filter((provider) => statusFor(provider)?.configured);

  return (
    <div className="stack">
      <div>
        <h1>Account Settings</h1>
        <p className="muted">Your details, and the AI provider keys your runs use.</p>
      </div>

      <section className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>Your details</h2>

        <div className={styles.details}>
          <div className={styles.field}>
            <span className="label">Name</span>
            <p className={styles.fieldValue}>
              {user.firstName} {user.lastName}
            </p>
          </div>
          <div className={styles.field}>
            <span className="label">Username</span>
            <p className={styles.fieldValue}>{user.username}</p>
          </div>
          <div className={styles.field}>
            <span className="label">Email</span>
            <p className={styles.fieldValue}>{user.email}</p>
          </div>
          <div className={styles.field}>
            <span className="label">Role</span>
            <p className={styles.fieldValue}>
              <span className={`pill ${user.role === 'ADMIN' ? 'pill-running' : 'pill-neutral'}`}>
                {user.role === 'ADMIN' ? 'Admin' : 'Read only'}
              </span>
            </p>
          </div>
          <div className={styles.field}>
            <span className="label">Member since</span>
            <p className={styles.fieldValue}>{formatDate(user.createdAt)}</p>
          </div>
        </div>

        <p className="hint" style={{ marginTop: 'var(--space-4)' }}>
          An administrator can change these, including your password, from the Admin Console.
        </p>
      </section>

      <section className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-2)' }}>
          AI provider keys
        </h2>
        <p className="muted" style={{ fontSize: 'var(--text-sm)' }}>
          A key you add here is yours - it is encrypted before it is stored, it is never shown back to
          you or to an administrator, and only the last four characters ever leave the server. Without
          one, your runs fall back to the system key if there is one.
        </p>

        {credentials.isPending ? (
          <SkeletonText lines={4} label="Loading your provider keys" />
        ) : credentials.isError ? (
          <ErrorState
            error={credentials.error}
            title="Could not load your provider keys"
            onRetry={() => credentials.refetch()}
          />
        ) : (
          <div className={styles.providerList} style={{ marginTop: 'var(--space-4)' }}>
            {PROVIDERS.map((provider) => (
              <ProviderRow key={provider} provider={provider} status={statusFor(provider)} />
            ))}
          </div>
        )}
      </section>

      <section className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-2)' }}>
          Preferred provider
        </h2>
        <p className="muted" style={{ fontSize: 'var(--text-sm)' }}>
          Which provider your runs and questions are sent to.
        </p>

        <div className={styles.preferred} style={{ marginTop: 'var(--space-4)' }}>
          <PreferredOption
            label="System default"
            hint="Uses whichever provider an administrator has configured."
            checked={user.preferredLlmProvider === null}
            disabled={setPreferred.isPending}
            onSelect={() => onChoosePreferred(null)}
          />
          {PROVIDERS.map((provider) => (
            <PreferredOption
              key={provider}
              label={providerLabel(provider)}
              hint={
                configuredProviders.includes(provider)
                  ? 'Uses your own key.'
                  : 'You have no key stored for this provider - the system key would be used, if one is configured.'
              }
              checked={user.preferredLlmProvider === provider}
              disabled={setPreferred.isPending}
              onSelect={() => onChoosePreferred(provider)}
            />
          ))}
        </div>
      </section>
    </div>
  );
}

/**
 * One provider's key row: its current state, and the form that changes it.
 *
 * @param props.provider which provider
 * @param props.status the stored status, or `undefined` if the API did not list this provider
 * @returns the row
 */
function ProviderRow({
  provider,
  status,
}: {
  provider: LlmProviderType;
  status: CredentialStatus | undefined;
}) {
  const [apiKey, setApiKey] = useState('');
  const { showError, showSuccess } = useToast();
  const save = useSaveMyCredential();
  const remove = useDeleteMyCredential();

  const configured = status?.configured ?? false;
  const label = providerLabel(provider);
  const inputId = `api-key-${provider}`;

  const onSave = async (event: React.FormEvent) => {
    event.preventDefault();
    const trimmed = apiKey.trim();
    if (!trimmed) return;
    try {
      await save.mutateAsync({ provider, apiKey: trimmed });
      // Cleared on success rather than in `finally`: a failed save should leave the key in the box so
      // it does not have to be pasted again.
      setApiKey('');
      showSuccess(`Your ${label} key was saved.`);
    } catch (cause) {
      showError(cause, `Could not save your ${label} key`);
    }
  };

  const onRemove = async () => {
    try {
      await remove.mutateAsync(provider);
      showSuccess(`Your ${label} key was removed.`);
    } catch (cause) {
      showError(cause, `Could not remove your ${label} key`);
    }
  };

  return (
    <div className={styles.provider}>
      <div className={styles.providerHeader}>
        <span className={styles.providerName}>
          {label}
          {configured ? (
            <span className="pill pill-success">Key stored</span>
          ) : (
            <span className="pill pill-neutral">No key</span>
          )}
        </span>

        {configured ? (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={onRemove}
            disabled={remove.isPending}
          >
            {remove.isPending ? 'Removing…' : 'Remove key'}
          </button>
        ) : null}
      </div>

      {configured ? (
        <p className="muted" style={{ fontSize: 'var(--text-sm)' }}>
          Stored, ending <span className={styles.masked}>{status?.last4 ?? '????'}</span>. Saving a new
          key replaces it.
        </p>
      ) : null}

      <form className={styles.keyForm} onSubmit={onSave} style={{ marginTop: 'var(--space-3)' }}>
        <div style={{ flex: '1 1 260px', minWidth: 0 }}>
          <label className="sr-only" htmlFor={inputId}>
            {label} API key
          </label>
          <input
            id={inputId}
            className={`input ${styles.keyInput}`}
            // A password field, not a text field: the value is a secret, and an autofilled or
            // shoulder-surfed API key is the same problem as a password.
            type="password"
            autoComplete="off"
            spellCheck={false}
            value={apiKey}
            onChange={(event) => setApiKey(event.target.value)}
            placeholder={configured ? 'Enter a new key to replace the stored one' : `Your ${label} API key`}
            disabled={save.isPending}
          />
        </div>
        <button
          type="submit"
          className="btn btn-secondary"
          disabled={save.isPending || !apiKey.trim()}
        >
          {save.isPending ? 'Saving…' : configured ? 'Replace key' : 'Save key'}
        </button>
      </form>
    </div>
  );
}

/**
 * One radio in the preferred-provider list.
 *
 * A real `<input type="radio">` inside a `<label>` rather than a styled `<div>`: this is exactly the
 * control radios are for, and it gets keyboard arrow navigation and the grouping announcement for
 * free.
 *
 * @param props.label the option's name
 * @param props.hint one line on what choosing it means
 * @param props.checked whether it is the current choice
 * @param props.disabled true while a change is in flight
 * @param props.onSelect called when chosen
 * @returns the option
 */
function PreferredOption({
  label,
  hint,
  checked,
  disabled,
  onSelect,
}: {
  label: string;
  hint: string;
  checked: boolean;
  disabled: boolean;
  onSelect: () => void;
}) {
  return (
    <label className={`${styles.preferredOption} ${checked ? styles.preferredActive : ''}`}>
      <input
        type="radio"
        name="preferred-provider"
        checked={checked}
        disabled={disabled}
        onChange={onSelect}
      />
      <span className={styles.preferredLabel}>
        {label}
        <span className={styles.preferredHint}>{hint}</span>
      </span>
    </label>
  );
}

export default AccountSettingsPage;
