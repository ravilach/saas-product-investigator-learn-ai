import { useState } from 'react';
import {
  EXPLORER_PAGE_SIZE,
  useCollectionDocuments,
  useCollections,
  useUpdateDocument,
} from '../../api/admin';
import { SECRET_MASK, type CollectionSummary, type ExplorerDocument } from '../../api/types';
import { EncryptedChip } from '../../components/Badges';
import { Pagination } from '../../components/Pagination';
import { EmptyState } from '../../components/states/EmptyState';
import { ErrorState } from '../../components/states/ErrorState';
import { SkeletonTable, SkeletonText } from '../../components/states/Skeleton';
import { useToast } from '../../components/toast/ToastProvider';
import styles from './Admin.module.css';

/**
 * Fields Mongo and Spring Data own. The backend rejects a `PUT` touching either with a 400, so they
 * are rendered read-only here rather than offered and then refused.
 */
const STRUCTURAL_FIELDS = ['_id', '_class'];

/** How many characters of a value a table cell shows before truncating. */
const CELL_LIMIT = 60;

/**
 * The Admin Console's Data Explorer tab: browse this application's collections, and edit the fields
 * that are safe to edit.
 *
 * The masking is not implemented here - it happens server-side, before the response is written, so the
 * ciphertext of an API key or an auth token never reaches the browser at all. What this component does
 * is render the *consequence* of that: a field whose value is the mask, or whose name is in the
 * collection's `secretFields`, gets a disabled `[encrypted]` chip instead of an input. That is the
 * honest control, because there is nothing to put in an input and submitting the mask back would
 * overwrite a real secret with the literal string `[encrypted]`.
 *
 * @returns the data explorer tab
 */
export function DataExplorerTab() {
  const collections = useCollections();
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [openDocumentId, setOpenDocumentId] = useState<string | null>(null);

  const documents = useCollectionDocuments(selected, page);
  const summary = collections.data?.find((entry) => entry.name === selected);

  const onSelect = (name: string) => {
    setSelected(name);
    setPage(0);
    setOpenDocumentId(null);
  };

  return (
    <div className={styles.explorerLayout}>
      <aside className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-3)' }}>Collections</h2>

        {collections.isPending ? (
          <SkeletonText lines={5} label="Loading collections" />
        ) : collections.isError ? (
          <ErrorState
            error={collections.error}
            title="Could not load collections"
            onRetry={() => collections.refetch()}
          />
        ) : (
          <ul className={styles.collectionList}>
            {collections.data?.map((collection) => (
              <li key={collection.name}>
                <button
                  type="button"
                  className={`${styles.collectionButton} ${
                    selected === collection.name ? styles.collectionActive : ''
                  }`}
                  onClick={() => onSelect(collection.name)}
                  aria-current={selected === collection.name}
                >
                  <span>{collection.name}</span>
                  <span className="muted">{collection.documentCount}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>

      <section className="stack">
        {!selected ? (
          <EmptyState
            title="Pick a collection"
            description="This is a direct view of the application's own database. Secret fields are replaced with an [encrypted] marker before the data leaves the server, so they can be seen to exist but never read or edited here."
          />
        ) : (
          <>
            <div className="card">
              <div
                className="row"
                style={{ justifyContent: 'space-between', flexWrap: 'wrap', marginBottom: 'var(--space-3)' }}
              >
                <h2 className={styles.mono} style={{ fontSize: 'var(--text-lg)' }}>
                  {selected}
                </h2>
                <span className="muted" style={{ fontSize: 'var(--text-sm)' }}>
                  {summary?.documentCount ?? 0} documents
                </span>
              </div>

              {summary && summary.secretFields.length > 0 ? (
                <p className="hint" style={{ marginTop: 0 }}>
                  Masked in this collection: {summary.secretFields.join(', ')}. These are encrypted at
                  rest and are never sent to the browser.
                </p>
              ) : null}

              {documents.isPending ? (
                <SkeletonTable rows={5} columns={4} label={`Loading ${selected}`} />
              ) : documents.isError ? (
                <ErrorState
                  error={documents.error}
                  title="Could not load documents"
                  onRetry={() => documents.refetch()}
                />
              ) : !documents.data || documents.data.content.length === 0 ? (
                <EmptyState title="This collection is empty" />
              ) : (
                <div className="stack">
                  <DocumentTable
                    documents={documents.data.content}
                    openDocumentId={openDocumentId}
                    onOpen={setOpenDocumentId}
                  />
                  <Pagination
                    page={documents.data}
                    onChange={(next) => {
                      setPage(next);
                      // Closed on a page change: the open panel belongs to a document that is about to
                      // leave the table, and leaving it open makes it look like part of the new page.
                      setOpenDocumentId(null);
                    }}
                    label="documents"
                    pageSize={EXPLORER_PAGE_SIZE}
                  />
                </div>
              )}
            </div>

            {openDocumentId ? (
              <DocumentPanel
                collection={selected}
                summary={summary}
                document={documents.data?.content.find((entry) => documentId(entry) === openDocumentId)}
                onClose={() => setOpenDocumentId(null)}
              />
            ) : null}
          </>
        )}
      </section>
    </div>
  );
}

/**
 * The document table.
 *
 * Columns are derived from the union of the visible documents' keys rather than from the first one:
 * these collections are schemaless in principle, and a field that only some documents carry would
 * otherwise be invisible depending on which page you were on.
 *
 * @param props.documents the page of documents
 * @param props.openDocumentId which row's panel is open
 * @param props.onOpen opens a row's panel
 * @returns the table
 */
function DocumentTable({
  documents,
  openDocumentId,
  onOpen,
}: {
  documents: ExplorerDocument[];
  openDocumentId: string | null;
  onOpen: (id: string) => void;
}) {
  const columns = collectColumns(documents);

  return (
    <div className="table-scroll">
      <table className={styles.table}>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column} scope="col" className={styles.mono}>
                {column}
              </th>
            ))}
            <th scope="col">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {documents.map((entry) => {
            const id = documentId(entry);
            return (
              <tr key={id} className={openDocumentId === id ? styles.rowSelected : undefined}>
                {columns.map((column) => (
                  <td key={column} className={styles.mono}>
                    {entry[column] === SECRET_MASK ? <EncryptedChip /> : summarise(entry[column])}
                  </td>
                ))}
                <td className={styles.actionsCell}>
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={() => onOpen(id)}
                    aria-expanded={openDocumentId === id}
                  >
                    Open
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The detail and edit panel for one document.
 *
 * Only scalar, non-secret, non-structural fields get an input. Nested objects and arrays are shown as
 * read-only JSON: this is a field editor, not a JSON editor, and offering a textarea whose contents
 * have to parse as BSON to save would be a worse tool than not offering it.
 *
 * @param props.collection the collection's name
 * @param props.summary the collection's summary, which names its masked fields
 * @param props.document the document, or `undefined` if it has left the page
 * @param props.onClose closes the panel
 * @returns the panel
 */
function DocumentPanel({
  collection,
  summary,
  document,
  onClose,
}: {
  collection: string;
  summary: CollectionSummary | undefined;
  document: ExplorerDocument | undefined;
  onClose: () => void;
}) {
  // Keyed by field name; only fields the admin has actually touched end up here, which is what makes
  // the PUT a patch of real edits rather than a rewrite of everything that happened to be on screen.
  const [edits, setEdits] = useState<Record<string, string>>({});
  const { showError, showSuccess } = useToast();
  const update = useUpdateDocument();

  if (!document) {
    return (
      <div className="card">
        <EmptyState
          title="That document is no longer on this page"
          description="It may have been edited or deleted since the table was loaded."
          action={
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Close
            </button>
          }
        />
      </div>
    );
  }

  const id = documentId(document);
  const secretFields = summary?.secretFields ?? [];
  const changed = Object.keys(edits).length > 0;

  const onSave = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!changed) return;
    try {
      await update.mutateAsync({ collection, id, document: coerceEdits(document, edits) });
      setEdits({});
      showSuccess('The document was updated.');
    } catch (cause) {
      showError(cause, 'Could not save the document');
    }
  };

  return (
    // Named, because the page can hold both the table and this panel and a form with no accessible
    // name is announced as an unlabelled group - which one is being edited is the whole question here.
    <form className="card" onSubmit={onSave} aria-label={`Document ${id}`}>
      <div
        className="row"
        style={{ justifyContent: 'space-between', flexWrap: 'wrap', marginBottom: 'var(--space-4)' }}
      >
        <h2 style={{ fontSize: 'var(--text-lg)' }}>Document</h2>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>
          Close
        </button>
      </div>

      <div className={styles.fieldList}>
        {Object.keys(document).map((field) => {
          const value = document[field];
          const inputId = `field-${collection}-${field}`;
          // Either list is enough on its own: `secretFields` is authoritative for which fields are
          // protected, and the mask catches a masked value nested where there is no top-level name to
          // check against.
          const masked = secretFields.includes(field) || value === SECRET_MASK;
          const structural = STRUCTURAL_FIELDS.includes(field);
          const editable = !masked && !structural && isScalar(value);

          return (
            <div key={field} className={styles.fieldRow}>
              {editable ? (
                <label className={styles.fieldName} htmlFor={inputId}>
                  {field}
                </label>
              ) : (
                <span className={styles.fieldName}>{field}</span>
              )}

              {masked ? (
                // A chip, never an input. See the note on the component: there is nothing to put in an
                // input, and submitting the mask back would overwrite the real secret with it.
                <div style={{ paddingTop: 'var(--space-2)' }}>
                  <EncryptedChip />
                  <p className="hint" style={{ marginTop: 'var(--space-1)' }}>
                    Encrypted at rest. Change it where it is configured, not here.
                  </p>
                </div>
              ) : structural ? (
                <div>
                  <div className={styles.readOnlyValue}>{String(value)}</div>
                  <p className="hint" style={{ marginTop: 0 }}>
                    Managed by the database.
                  </p>
                </div>
              ) : editable ? (
                <input
                  id={inputId}
                  className="input"
                  value={edits[field] ?? String(value ?? '')}
                  onChange={(event) =>
                    setEdits((previous) => ({ ...previous, [field]: event.target.value }))
                  }
                />
              ) : (
                <div>
                  <div className={styles.readOnlyValue}>{JSON.stringify(value, null, 2)}</div>
                  <p className="hint" style={{ marginTop: 0 }}>
                    Nested values are read-only here.
                  </p>
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div className="row" style={{ marginTop: 'var(--space-5)', flexWrap: 'wrap' }}>
        <button type="submit" className="btn btn-primary" disabled={update.isPending || !changed}>
          {update.isPending ? 'Saving…' : 'Save changes'}
        </button>
        {changed ? (
          <button type="button" className="btn btn-ghost" onClick={() => setEdits({})}>
            Discard changes
          </button>
        ) : (
          <span className="muted" style={{ fontSize: 'var(--text-xs)' }}>
            Nothing changed.
          </span>
        )}
      </div>

      <p className="hint">
        This writes straight to the database, bypassing every validation the API applies. Every save is
        recorded in the audit log.
      </p>
    </form>
  );
}

/**
 * Reads a document's id.
 *
 * @param document the document
 * @returns the `_id` as a string, or an empty string if it has none
 */
function documentId(document: ExplorerDocument): string {
  const id = document['_id'];
  return id === undefined || id === null ? '' : String(id);
}

/**
 * Collects the union of every visible document's field names, `_id` first.
 *
 * @param documents the page of documents
 * @returns the column names in a stable order
 */
export function collectColumns(documents: ExplorerDocument[]): string[] {
  const columns = new Set<string>();
  for (const document of documents) {
    for (const key of Object.keys(document)) columns.add(key);
  }
  // `_class` is dropped from the table: it is the same value on every row of a collection and one of
  // the widest, so it costs a column and tells nobody anything. It is still shown in the panel.
  columns.delete('_class');

  const ordered = [...columns];
  return ordered.sort((left, right) => {
    if (left === '_id') return -1;
    if (right === '_id') return 1;
    return 0;
  });
}

/**
 * Whether a value is one this editor can offer a text input for.
 *
 * @param value the field's value
 * @returns true for strings, numbers, booleans, and null
 */
export function isScalar(value: unknown): boolean {
  return (
    value === null ||
    typeof value === 'string' ||
    typeof value === 'number' ||
    typeof value === 'boolean'
  );
}

/**
 * Turns the edited strings back into the types the fields started as.
 *
 * An input hands back a string for everything. Sending `"7"` where the document had `7` would change a
 * number field into a string field in Mongo - a silent schema change that the application would then
 * fail to deserialise. So each edit is coerced to the original value's type, and a value that no longer
 * parses as that type is sent as the string, where the backend's own handling decides.
 *
 * @param document the document as loaded
 * @param edits the raw edited strings, keyed by field
 * @returns the patch body
 */
export function coerceEdits(
  document: ExplorerDocument,
  edits: Record<string, string>,
): ExplorerDocument {
  const patch: ExplorerDocument = {};

  for (const [field, raw] of Object.entries(edits)) {
    const original = document[field];

    if (typeof original === 'number') {
      const parsed = Number(raw);
      patch[field] = raw.trim() !== '' && Number.isFinite(parsed) ? parsed : raw;
    } else if (typeof original === 'boolean') {
      // Only the two literals flip the boolean; anything else goes through as typed rather than
      // being silently read as `false`, which is what `Boolean(raw)` would do to "no".
      if (raw === 'true' || raw === 'false') patch[field] = raw === 'true';
      else patch[field] = raw;
    } else {
      patch[field] = raw;
    }
  }

  return patch;
}

/**
 * Renders a value short enough for a table cell.
 *
 * @param value the field's value
 * @returns a truncated one-line rendering
 */
function summarise(value: unknown): string {
  if (value === null || value === undefined) return '—';
  const rendered = typeof value === 'object' ? JSON.stringify(value) : String(value);
  return rendered.length > CELL_LIMIT ? `${rendered.slice(0, CELL_LIMIT)}…` : rendered;
}

export default DataExplorerTab;
