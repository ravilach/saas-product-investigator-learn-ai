import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SECRET_MASK, type CollectionSummary, type ExplorerDocument } from '../../api/types';
import { jsonResponse, makeUser, renderWithProviders } from '../../test/renderWithProviders';
import { DataExplorerTab, coerceEdits, collectColumns, isScalar } from './DataExplorerTab';

/** The collection the tests browse: one masked field, declared the way the backend declares it. */
const COLLECTIONS: CollectionSummary[] = [
  { name: 'user_credentials', documentCount: 2, secretFields: ['apiKeyEncrypted'] },
  { name: 'saas_products', documentCount: 1, secretFields: [] },
];

/**
 * Two documents as the backend really returns them: the secret value already replaced with the mask,
 * server-side, before the response was written.
 */
const DOCUMENTS: ExplorerDocument[] = [
  {
    _id: 'cred-1',
    _class: 'com.saasinvestigator.credential.UserCredentialDocument',
    userId: 'user-1',
    provider: 'ANTHROPIC',
    apiKeyEncrypted: SECRET_MASK,
    last4: 'cdef',
    active: true,
    version: 3,
  },
];

/**
 * Stubs the three requests this tab makes, in whatever order TanStack Query fires them.
 *
 * Routed on the URL rather than on call order, because the collection list and the document page are
 * two independent queries and asserting on which resolves first would make the test fragile for no
 * reason.
 *
 * @param documents the page of documents to return
 * @returns the fetch mock, for asserting on what was sent
 */
function stubApi(documents: ExplorerDocument[] = DOCUMENTS) {
  const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
    if (url === '/api/admin/data-explorer/collections') {
      return jsonResponse(COLLECTIONS);
    }
    if (url.includes('/documents?')) {
      return jsonResponse({
        content: documents,
        page: 0,
        size: 10,
        totalElements: documents.length,
        totalPages: 1,
        first: true,
        last: true,
      });
    }
    if (url.includes('/documents/')) {
      return jsonResponse(documents[0]);
    }
    // Loud rather than silent: an unstubbed call showing up as an empty render is a much worse test
    // failure to read than this.
    throw new Error(`Unexpected request: ${url}`);
  });

  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/**
 * The Data Explorer's masked-field handling.
 *
 * The masking itself is the backend's job and is tested there. What matters here is the consequence the
 * build prompt names: a masked field renders as a disabled `[encrypted]` chip and never as an editable
 * input, because submitting the mask back would overwrite a real encrypted secret with the literal
 * string `[encrypted]`.
 */
describe('DataExplorerTab', () => {
  it('renders a masked value in the table as a chip, not as its text', async () => {
    stubApi();
    renderWithProviders(<DataExplorerTab />, { signedInAs: makeUser() });

    await userEvent.click(await screen.findByRole('button', { name: /user_credentials/ }));

    const table = await screen.findByRole('table');
    // The chip, not the bare string: the visual signal is what tells an admin the field exists but is
    // unreadable, and plain text would look like the value really is "[encrypted]".
    expect(within(table).getByText('[encrypted]')).toHaveAttribute('aria-disabled', 'true');
  });

  it('gives a masked field a disabled chip in the detail panel, and no input', async () => {
    stubApi();
    renderWithProviders(<DataExplorerTab />, { signedInAs: makeUser() });

    await userEvent.click(await screen.findByRole('button', { name: /user_credentials/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Open' }));

    const panel = await screen.findByRole('form');

    // The editable field is offered as an input...
    expect(within(panel).getByLabelText('userId')).toHaveValue('user-1');

    // ...and the masked one is not offered at all. `queryByLabelText` rather than a truthiness check on
    // the chip: the assertion that matters is the *absence of an input*, because an input is what would
    // let someone submit the mask back over a real secret.
    expect(within(panel).queryByLabelText('apiKeyEncrypted')).not.toBeInTheDocument();
    expect(within(panel).getAllByText('[encrypted]')[0]).toHaveAttribute('aria-disabled', 'true');
    expect(
      within(panel).getByText(/Encrypted at rest. Change it where it is configured, not here./),
    ).toBeInTheDocument();
  });

  it('never sends a masked field back, even though the mask was on screen', async () => {
    const fetchMock = stubApi();
    renderWithProviders(<DataExplorerTab />, { signedInAs: makeUser() });

    await userEvent.click(await screen.findByRole('button', { name: /user_credentials/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Open' }));

    const userIdInput = await screen.findByLabelText('userId');
    await userEvent.clear(userIdInput);
    await userEvent.type(userIdInput, 'user-2');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => {
      expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true);
    });

    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT');
    const body = JSON.parse(put?.[1]?.body as string);

    // A patch of exactly what was edited. The backend would reject a masked field with a 400, but the
    // UI must not rely on that - the whole point of the chip is that the field never enters the form's
    // state in the first place.
    expect(body).toEqual({ userId: 'user-2' });
    expect(Object.keys(body)).not.toContain('apiKeyEncrypted');
    expect(Object.keys(body)).not.toContain('_id');
  });

  it("does not offer inputs for the database's own fields", async () => {
    stubApi();
    renderWithProviders(<DataExplorerTab />, { signedInAs: makeUser() });

    await userEvent.click(await screen.findByRole('button', { name: /user_credentials/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Open' }));

    const panel = await screen.findByRole('form');

    // `_id` and `_class` are rejected by the backend with a 400, so offering them and then failing
    // would be a worse experience than not offering them.
    expect(within(panel).queryByLabelText('_id')).not.toBeInTheDocument();
    expect(within(panel).queryByLabelText('_class')).not.toBeInTheDocument();
    expect(within(panel).getAllByText('Managed by the database.').length).toBe(2);
  });

  it('names the collection\'s masked fields up front', async () => {
    stubApi();
    renderWithProviders(<DataExplorerTab />, { signedInAs: makeUser() });

    await userEvent.click(await screen.findByRole('button', { name: /user_credentials/ }));

    expect(
      await screen.findByText(/Masked in this collection: apiKeyEncrypted/),
    ).toBeInTheDocument();
  });

  it('masks a field named in secretFields even if its value does not look masked', async () => {
    // The two signals are deliberately independent: `secretFields` is authoritative for which fields
    // are protected, so a value the masker did not rewrite - a null, say - must still not get an input.
    stubApi([{ _id: 'cred-2', apiKeyEncrypted: null, userId: 'user-9' }]);
    renderWithProviders(<DataExplorerTab />, { signedInAs: makeUser() });

    await userEvent.click(await screen.findByRole('button', { name: /user_credentials/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Open' }));

    const panel = await screen.findByRole('form');
    expect(within(panel).queryByLabelText('apiKeyEncrypted')).not.toBeInTheDocument();
  });
});

describe('collectColumns', () => {
  it('unions the keys across documents so a sparse field is not invisible', () => {
    const columns = collectColumns([
      { _id: '1', name: 'a' },
      { _id: '2', description: 'b' },
    ]);

    expect(columns).toEqual(['_id', 'name', 'description']);
  });

  it('drops _class, which is identical on every row', () => {
    expect(collectColumns([{ _id: '1', _class: 'com.example.Thing' }])).toEqual(['_id']);
  });
});

describe('isScalar', () => {
  it('treats null as editable and objects and arrays as not', () => {
    expect(isScalar(null)).toBe(true);
    expect(isScalar('text')).toBe(true);
    expect(isScalar(0)).toBe(true);
    expect(isScalar(false)).toBe(true);
    expect(isScalar({ nested: true })).toBe(false);
    expect(isScalar([1, 2])).toBe(false);
  });
});

describe('coerceEdits', () => {
  it('keeps a number field a number rather than turning it into a string', () => {
    // Sending "4" where the document had 3 would change the field's BSON type, and the application
    // would then fail to deserialise its own document.
    expect(coerceEdits({ version: 3 }, { version: '4' })).toEqual({ version: 4 });
  });

  it('keeps a boolean field a boolean for the two literals', () => {
    expect(coerceEdits({ active: true }, { active: 'false' })).toEqual({ active: false });
  });

  it('passes an unparsable value through as typed rather than guessing', () => {
    // `Boolean('no')` is `true` and `Number('')` is `0`; either would store something the admin did not
    // type. Sending the string lets the backend reject it with a message instead.
    expect(coerceEdits({ active: true }, { active: 'no' })).toEqual({ active: 'no' });
    expect(coerceEdits({ version: 3 }, { version: '' })).toEqual({ version: '' });
  });

  it('includes only the edited fields', () => {
    expect(coerceEdits({ a: '1', b: '2' }, { b: '3' })).toEqual({ b: '3' });
  });
});
