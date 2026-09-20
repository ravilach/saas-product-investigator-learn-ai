import { useState } from 'react';
import { useCreateUser, useDeleteUser, useResetPassword, useUsers } from '../../api/admin';
import type { CreateUserRequest, Role, User } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Modal } from '../../components/Modal';
import { ErrorState } from '../../components/states/ErrorState';
import { SkeletonTable } from '../../components/states/Skeleton';
import { useToast } from '../../components/toast/ToastProvider';
import { formatDate, providerLabel } from '../../utils/format';
import styles from './Admin.module.css';

/**
 * The shortest password the backend will accept, from `UserService.MIN_PASSWORD_LENGTH`.
 *
 * Mirrored here so the form can say so before submitting rather than after. The server's `@Size` is
 * the rule; this is only the earlier, kinder statement of it.
 */
const MIN_PASSWORD_LENGTH = 4;

/** The username rule from `CreateUserRequest`'s `@Pattern`, mirrored for the same reason. */
const USERNAME_PATTERN = /^[A-Za-z0-9._-]{2,64}$/;

/**
 * The Admin Console's Users tab: the user list, with create, delete, and password reset.
 *
 * The one thing here that is a security decision rather than a UI one: the reset dialog sets a new
 * password and cannot show the current one. Not "does not" - cannot. The stored value is a one-way
 * BCrypt hash and there is no endpoint that returns it, so there is nothing for this dialog to
 * display even if it wanted to.
 *
 * @returns the users tab
 */
export function UsersTab() {
  const { user: currentUser } = useAuth();
  const { data, isPending, isError, error, refetch } = useUsers();
  const [creating, setCreating] = useState(false);
  const [resetting, setResetting] = useState<User | null>(null);
  const [deleting, setDeleting] = useState<User | null>(null);

  return (
    <div className="stack">
      <section className="card">
        <div
          className="row"
          style={{ justifyContent: 'space-between', flexWrap: 'wrap', marginBottom: 'var(--space-4)' }}
        >
          <h2 style={{ fontSize: 'var(--text-lg)' }}>Users</h2>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setCreating(true)}>
            New user
          </button>
        </div>

        {isPending ? (
          <SkeletonTable rows={4} columns={5} label="Loading users" />
        ) : isError ? (
          <ErrorState error={error} title="Could not load users" onRetry={() => refetch()} />
        ) : (
          <div className="table-scroll">
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Username</th>
                  <th scope="col">Email</th>
                  <th scope="col">Role</th>
                  <th scope="col">Created</th>
                  <th scope="col">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {data?.map((user) => (
                  <tr key={user.id}>
                    <td>
                      {user.firstName} {user.lastName}
                      {user.id === currentUser?.id ? (
                        <span className="muted" style={{ fontSize: 'var(--text-xs)' }}>
                          {' '}
                          (you)
                        </span>
                      ) : null}
                    </td>
                    <td className={styles.mono}>{user.username}</td>
                    <td>{user.email}</td>
                    <td>
                      <span
                        className={`pill ${user.role === 'ADMIN' ? 'pill-running' : 'pill-neutral'}`}
                      >
                        {user.role === 'ADMIN' ? 'Admin' : 'Read only'}
                      </span>
                      {user.preferredLlmProvider ? (
                        <div className="muted" style={{ fontSize: 'var(--text-xs)' }}>
                          prefers {providerLabel(user.preferredLlmProvider)}
                        </div>
                      ) : null}
                    </td>
                    <td>{formatDate(user.createdAt)}</td>
                    <td className={styles.actionsCell}>
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        onClick={() => setResetting(user)}
                      >
                        Reset password
                      </button>
                      {/*
                        The backend refuses a self-delete with a 400, so the button is hidden rather
                        than left to produce an error that says what the UI already knew.
                      */}
                      {user.id === currentUser?.id ? null : (
                        <button
                          type="button"
                          className="btn btn-ghost btn-sm"
                          onClick={() => setDeleting(user)}
                        >
                          Delete
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {creating ? <CreateUserDialog onClose={() => setCreating(false)} /> : null}
      {resetting ? (
        <ResetPasswordDialog user={resetting} onClose={() => setResetting(null)} />
      ) : null}
      {deleting ? <DeleteUserDialog user={deleting} onClose={() => setDeleting(null)} /> : null}
    </div>
  );
}

/**
 * The create-user dialog.
 *
 * @param props.onClose dismisses the dialog
 * @returns the dialog
 */
function CreateUserDialog({ onClose }: { onClose: () => void }) {
  const [form, setForm] = useState<CreateUserRequest>({
    firstName: '',
    lastName: '',
    username: '',
    email: '',
    password: '',
    role: 'READ_ONLY',
  });
  const [submitted, setSubmitted] = useState(false);
  const { showError, showSuccess } = useToast();
  const create = useCreateUser();

  const errors = validateNewUser(form);
  const hasErrors = Object.keys(errors).length > 0;

  const set = <K extends keyof CreateUserRequest>(key: K, value: CreateUserRequest[K]) =>
    setForm((previous) => ({ ...previous, [key]: value }));

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (hasErrors) return;
    try {
      await create.mutateAsync(form);
      showSuccess(`${form.username} was created.`);
      onClose();
    } catch (cause) {
      showError(cause, 'Could not create the user');
    }
  };

  /** Shows a field's error only once a submit has been attempted. */
  const errorFor = (key: keyof CreateUserRequest) => (submitted ? errors[key] : undefined);

  return (
    <Modal
      title="New user"
      onClose={onClose}
      footer={
        <>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={onClose}
            disabled={create.isPending}
          >
            Cancel
          </button>
          <button
            type="submit"
            form="create-user-form"
            className="btn btn-primary"
            disabled={create.isPending}
          >
            {create.isPending ? 'Creating…' : 'Create user'}
          </button>
        </>
      }
    >
      {/*
        The form is in the body and its submit button is in the footer, joined by `form=`. That keeps
        Enter-to-submit working while the buttons stay where every other dialog puts them.
      */}
      <form id="create-user-form" className="stack" onSubmit={onSubmit}>
        <div className="field">
          <label className="label" htmlFor="new-first-name">
            First name
          </label>
          <input
            id="new-first-name"
            className="input"
            value={form.firstName}
            onChange={(event) => set('firstName', event.target.value)}
            aria-invalid={Boolean(errorFor('firstName'))}
          />
          {errorFor('firstName') ? <p className="field-error">{errorFor('firstName')}</p> : null}
        </div>

        <div className="field">
          <label className="label" htmlFor="new-last-name">
            Last name
          </label>
          <input
            id="new-last-name"
            className="input"
            value={form.lastName}
            onChange={(event) => set('lastName', event.target.value)}
            aria-invalid={Boolean(errorFor('lastName'))}
          />
          {errorFor('lastName') ? <p className="field-error">{errorFor('lastName')}</p> : null}
        </div>

        <div className="field">
          <label className="label" htmlFor="new-username">
            Username
          </label>
          <input
            id="new-username"
            className="input"
            value={form.username}
            autoComplete="off"
            onChange={(event) => set('username', event.target.value)}
            aria-invalid={Boolean(errorFor('username'))}
          />
          <p className="hint">Letters, digits, dot, dash and underscore. This is what they sign in with.</p>
          {errorFor('username') ? <p className="field-error">{errorFor('username')}</p> : null}
        </div>

        <div className="field">
          <label className="label" htmlFor="new-email">
            Email
          </label>
          <input
            id="new-email"
            className="input"
            type="email"
            value={form.email}
            onChange={(event) => set('email', event.target.value)}
            aria-invalid={Boolean(errorFor('email'))}
          />
          {errorFor('email') ? <p className="field-error">{errorFor('email')}</p> : null}
        </div>

        <div className="field">
          <label className="label" htmlFor="new-password">
            Initial password
          </label>
          <input
            id="new-password"
            className="input"
            // `new-password` rather than `off`: this tells a password manager to offer to generate one,
            // which is the behaviour worth having here.
            type="password"
            autoComplete="new-password"
            value={form.password}
            onChange={(event) => set('password', event.target.value)}
            aria-invalid={Boolean(errorFor('password'))}
          />
          <p className="hint">
            At least {MIN_PASSWORD_LENGTH} characters. Tell them out of band - it is hashed on arrival
            and cannot be read back.
          </p>
          {errorFor('password') ? <p className="field-error">{errorFor('password')}</p> : null}
        </div>

        <div className="field">
          <label className="label" htmlFor="new-role">
            Role
          </label>
          <select
            id="new-role"
            className="select"
            value={form.role}
            onChange={(event) => set('role', event.target.value as Role)}
          >
            <option value="READ_ONLY">Read only - can view, run, and ask</option>
            <option value="ADMIN">Admin - can also add, edit, and delete</option>
          </select>
        </div>
      </form>
    </Modal>
  );
}

/**
 * The password-reset dialog.
 *
 * Deliberately has no "current password" field, and shows nothing about the existing one. See
 * {@link UsersTab}'s note: there is nothing to show.
 *
 * @param props.user whose password is being reset
 * @param props.onClose dismisses the dialog
 * @returns the dialog
 */
function ResetPasswordDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const { showError, showSuccess } = useToast();
  const { user: currentUser } = useAuth();
  const reset = useResetPassword();

  const tooShort = password.length < MIN_PASSWORD_LENGTH;
  const mismatched = confirmation !== password;
  const invalid = tooShort || mismatched;

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (invalid) return;
    try {
      await reset.mutateAsync({ id: user.id, newPassword: password });
      showSuccess(`${user.username}'s password was reset.`);
      onClose();
    } catch (cause) {
      showError(cause, 'Could not reset the password');
    }
  };

  return (
    <Modal
      title={`Reset password for ${user.username}`}
      onClose={onClose}
      footer={
        <>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={onClose}
            disabled={reset.isPending}
          >
            Cancel
          </button>
          <button
            type="submit"
            form="reset-password-form"
            className="btn btn-primary"
            disabled={reset.isPending}
          >
            {reset.isPending ? 'Resetting…' : 'Set new password'}
          </button>
        </>
      }
    >
      <form id="reset-password-form" className="stack" onSubmit={onSubmit}>
        <p style={{ fontSize: 'var(--text-sm)', margin: 0 }}>
          This sets a new password for <strong>{user.firstName} {user.lastName}</strong>. Their current
          password is stored as a one-way hash, so it cannot be shown here or anywhere else - it can
          only be replaced.
        </p>

        {user.id === currentUser?.id ? (
          <p className={styles.danger}>
            This is your own account. Your current session keeps working, but you will need the new
            password the next time you sign in.
          </p>
        ) : null}

        <div className="field">
          <label className="label" htmlFor="reset-password">
            New password
          </label>
          <input
            id="reset-password"
            className="input"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            aria-invalid={submitted && tooShort}
          />
          {submitted && tooShort ? (
            <p className="field-error">
              The password must be at least {MIN_PASSWORD_LENGTH} characters.
            </p>
          ) : null}
        </div>

        <div className="field">
          <label className="label" htmlFor="reset-password-confirm">
            Confirm new password
          </label>
          <input
            id="reset-password-confirm"
            className="input"
            type="password"
            autoComplete="new-password"
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
            aria-invalid={submitted && mismatched}
          />
          {/* Confirmed because nothing can verify a typo afterwards: the value is hashed on arrival,
              so a mistyped reset locks the person out with no way to discover what was set. */}
          {submitted && mismatched ? (
            <p className="field-error">The two passwords do not match.</p>
          ) : null}
        </div>
      </form>
    </Modal>
  );
}

/**
 * The delete-user confirmation.
 *
 * @param props.user the user to delete
 * @param props.onClose dismisses the dialog
 * @returns the dialog
 */
function DeleteUserDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const { showError, showSuccess } = useToast();
  const remove = useDeleteUser();

  const onConfirm = async () => {
    try {
      await remove.mutateAsync(user.id);
      showSuccess(`${user.username} was deleted.`);
      onClose();
    } catch (cause) {
      showError(cause, 'Could not delete the user');
    }
  };

  return (
    <Modal
      title={`Delete ${user.username}?`}
      onClose={onClose}
      footer={
        <>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={onClose}
            disabled={remove.isPending}
          >
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-danger"
            onClick={onConfirm}
            disabled={remove.isPending}
          >
            {remove.isPending ? 'Deleting…' : 'Delete user'}
          </button>
        </>
      }
    >
      <p>
        This deletes {user.firstName} {user.lastName}'s account and discards any AI provider keys they
        had stored. Products and reports they created are not affected. It cannot be undone.
      </p>
    </Modal>
  );
}

/**
 * Validates the create-user form against the backend's own constraints.
 *
 * Exported for the same reason the source form's rules are: these are pure and worth testing without
 * mounting a dialog.
 *
 * @param form the draft user
 * @returns a message per invalid field; empty when the form is submittable
 */
export function validateNewUser(form: CreateUserRequest): Partial<Record<keyof CreateUserRequest, string>> {
  const errors: Partial<Record<keyof CreateUserRequest, string>> = {};

  if (!form.firstName.trim()) errors.firstName = 'A first name is required.';
  if (!form.lastName.trim()) errors.lastName = 'A last name is required.';

  if (!form.username.trim()) {
    errors.username = 'A username is required.';
  } else if (!USERNAME_PATTERN.test(form.username)) {
    errors.username =
      "A username must be 2-64 characters of letters, digits, '.', '_' or '-' - no spaces.";
  }

  if (!form.email.trim()) {
    errors.email = 'An email address is required.';
  } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) {
    // Deliberately loose. The server's `@Email` is the rule; this only catches the obvious cases,
    // because a stricter client-side regex would reject valid addresses the server accepts.
    errors.email = 'That does not look like an email address.';
  }

  if (form.password.length < MIN_PASSWORD_LENGTH) {
    errors.password = `The password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
  }

  return errors;
}

export default UsersTab;
