import { useAuth } from '../auth/AuthContext';
import { usePageTitle } from '../layout/usePageTitle';
import { UnderConstruction } from './UnderConstruction';

/**
 * Account Settings: the signed-in user's own details and their personal BYOK provider keys.
 *
 * @returns the account settings page
 */
export function AccountSettingsPage() {
  const { user } = useAuth();
  usePageTitle('Account Settings');

  return (
    <div className="stack">
      <h1>Account Settings</h1>
      {user ? (
        <div className="card stack">
          <div>
            <span className="label">Name</span>
            <p>
              {user.firstName} {user.lastName}
            </p>
          </div>
          <div>
            <span className="label">Username</span>
            <p>{user.username}</p>
          </div>
          <div>
            <span className="label">Email</span>
            <p>{user.email}</p>
          </div>
          <div>
            <span className="label">Role</span>
            <p>
              <span className={`pill ${user.role === 'ADMIN' ? 'pill-running' : 'pill-neutral'}`}>
                {user.role === 'ADMIN' ? 'Admin' : 'Read only'}
              </span>
            </p>
          </div>
        </div>
      ) : null}
      <UnderConstruction
        page="AI Provider keys"
        description="Will list Anthropic and OpenAI with an add/remove key field each, masked to the last 4 characters, plus the active-provider choice."
      />
    </div>
  );
}

export default AccountSettingsPage;
