import { Link } from 'react-router-dom';
import type { SaasProduct } from '../../api/types';
import { isMcpSource } from '../../api/types';
import { sourceTypeLabel } from '../../utils/format';
import styles from './ProductDetail.module.css';

/** Props for {@link SourcePanel}. */
export interface SourcePanelProps {
  /** The product whose sources are shown. */
  product: SaasProduct;
  /** Whether the viewer may edit. Read-only for READ_ONLY users, per DESIGN & UX. */
  canEdit: boolean;
}

/**
 * The source configuration panel on the product detail page.
 *
 * Read-only for everyone: editing happens on the Edit form, which this links to. That is a deliberate
 * split rather than inline editing, because a source list edit is a whole-list replacement on the
 * backend (`PUT` replaces `sources`), so an inline "edit one source" control would be pretending to an
 * atomicity the API does not offer.
 *
 * A READ_ONLY user sees the same panel without the link - the sources are not secret, only the ability
 * to change them is restricted, and the backend enforces that on the `PUT`.
 *
 * @param props see {@link SourcePanelProps}
 * @returns the panel
 */
export function SourcePanel({ product, canEdit }: SourcePanelProps) {
  return (
    <aside className="card">
      <div className={styles.panelHeading}>
        <h2 style={{ fontSize: 'var(--text-lg)' }}>Sources</h2>
        {canEdit ? (
          <Link to={`/products/${product.id}/edit`} className="btn btn-secondary btn-sm">
            Edit
          </Link>
        ) : null}
      </div>

      {product.sources.length === 0 ? (
        <p className="muted" style={{ fontSize: 'var(--text-sm)', marginBottom: 0 }}>
          {canEdit
            ? 'No sources configured. A run needs at least one to have anything to compare.'
            : 'No sources configured yet.'}
        </p>
      ) : (
        <ul className={styles.sourceList}>
          {product.sources.map((source) => (
            <li key={source.name} className={styles.source}>
              <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
                <span className="pill pill-neutral">{sourceTypeLabel(source.type)}</span>
                <strong style={{ fontSize: 'var(--text-sm)' }}>{source.name}</strong>
              </div>

              <span className={styles.sourceUrl}>{source.endpointUrl}</span>

              <div className={styles.sourceMeta}>
                {/* The token's existence and its last 4 - never anything more. The plaintext is
                    encrypted at rest and the API has no endpoint that returns it. */}
                {source.authTokenConfigured ? (
                  <span>Token ends {source.authTokenLast4 ?? '????'}</span>
                ) : null}

                {/* Effective values, not the overrides: this panel answers "what will a run actually
                    do", and the override-vs-default distinction belongs on the edit form. */}
                {!isMcpSource(source.type) ? (
                  <span>
                    depth {source.effectiveMaxDepth ?? '—'} · up to {source.effectiveMaxPages ?? '—'}{' '}
                    pages
                    {source.maxDepth === null && source.maxPages === null ? ' (defaults)' : ''}
                  </span>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}
