import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useDeleteProduct, useProduct } from '../api/products';
import { useAuth } from '../auth/AuthContext';
import { LastRunPill } from '../components/Badges';
import { Modal } from '../components/Modal';
import { EmptyState } from '../components/states/EmptyState';
import { ErrorState } from '../components/states/ErrorState';
import { SkeletonText } from '../components/states/Skeleton';
import { useToast } from '../components/toast/ToastProvider';
import { usePageTitle } from '../layout/usePageTitle';
import { AskTab } from './product/AskTab';
import { CompareTab } from './product/CompareTab';
import { HistoryTab } from './product/HistoryTab';
import styles from './product/ProductDetail.module.css';
import { RunTab } from './product/RunTab';
import { SourcePanel } from './product/SourcePanel';

/** The detail page's tabs, in display order. */
const TABS = ['run', 'compare', 'history', 'ask'] as const;

type TabId = (typeof TABS)[number];

const TAB_LABELS: Record<TabId, string> = {
  run: 'Run',
  compare: 'Compare',
  history: 'History',
  ask: 'Ask',
};

/**
 * A SaaS Product's detail page: source configuration alongside the Run / Compare / History / Ask tabs.
 *
 * The tab state lives here rather than in the URL. That is a deliberate trade: a shareable
 * `?tab=history` would be nice, but a Run tab that can be navigated away from and back to by the
 * browser's own history would remount mid-run and silently drop the event stream. Keeping the state
 * local makes "the run keeps going while you read its progress" the only behaviour there is.
 *
 * Each tab's content is mounted only while it is selected. For Run and Ask that is load-bearing:
 * unmounting aborts the stream, which is exactly what should happen when someone leaves a run behind,
 * and is why the {@link RunTab} keeps its own state instead of hoisting it here.
 *
 * @returns the product detail page
 */
export function ProductDetailPage() {
  const { productId } = useParams<{ productId: string }>();
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const { showError, showSuccess } = useToast();
  const [tab, setTab] = useState<TabId>('run');
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const { data: product, isPending, isError, error, refetch } = useProduct(productId);
  const deleteProduct = useDeleteProduct();

  // Undefined while loading, so the top bar and the tab title fill in once the name arrives rather
  // than flashing a placeholder.
  usePageTitle(product?.name);

  const onDelete = async () => {
    if (!product) return;
    try {
      await deleteProduct.mutateAsync(product.id);
      showSuccess(`"${product.name}" and its history were deleted.`);
      // Replaces rather than pushes: Back from the dashboard should not return to a product that no
      // longer exists.
      navigate('/', { replace: true });
    } catch (cause) {
      showError(cause, 'Could not delete this product');
      setConfirmingDelete(false);
    }
  };

  if (isPending) {
    return (
      <div className="stack">
        <SkeletonText lines={2} label="Loading product" />
        <div className="card">
          <SkeletonText lines={4} label="Loading sources" />
        </div>
      </div>
    );
  }

  if (isError) {
    return (
      <ErrorState error={error} onRetry={() => refetch()}>
        <Link to="/" className="btn btn-secondary btn-sm">
          Back to Dashboard
        </Link>
      </ErrorState>
    );
  }

  if (!product) {
    // Reachable only if the route param is missing, which the router's own path makes impossible -
    // but `useProduct` is legitimately disabled in that case, so the state is handled rather than
    // asserted away.
    return (
      <EmptyState
        title="No product selected"
        description="This URL is missing a product id."
        action={
          <Link to="/" className="btn btn-primary">
            Back to Dashboard
          </Link>
        }
      />
    );
  }

  return (
    <div className="stack">
      <header className="stack" style={{ gap: 'var(--space-3)' }}>
        <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap' }}>
          <div>
            <h1>{product.name}</h1>
            {product.description ? <p className="muted">{product.description}</p> : null}
          </div>

          {/* Admin-only in the UI; the DELETE is `@PreAuthorize`d server-side, which is the control. */}
          {isAdmin ? (
            <div className="row" style={{ flexWrap: 'wrap' }}>
              <Link to={`/products/${product.id}/edit`} className="btn btn-secondary">
                Edit
              </Link>
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => setConfirmingDelete(true)}
              >
                Delete
              </button>
            </div>
          ) : null}
        </div>

        <div className="row" style={{ flexWrap: 'wrap' }}>
          <LastRunPill lastRun={product.lastRun} />
          <span className="muted" style={{ fontSize: 'var(--text-sm)' }}>
            {product.sources.length} source{product.sources.length === 1 ? '' : 's'}
          </span>
        </div>
      </header>

      <div className={styles.layout}>
        <SourcePanel product={product} canEdit={isAdmin} />

        <section>
          <div className={styles.tabs} role="tablist" aria-label="Product views">
            {TABS.map((id) => (
              <button
                key={id}
                type="button"
                role="tab"
                id={`tab-${id}`}
                aria-selected={tab === id}
                aria-controls={`panel-${id}`}
                // Only the selected tab is in the tab order; arrow keys are not implemented here
                // because the panels are mounted on selection, so moving focus between tabs would
                // tear down a running stream just by pressing an arrow key.
                tabIndex={tab === id ? 0 : -1}
                className={`${styles.tab} ${tab === id ? styles.tabActive : ''}`}
                onClick={() => setTab(id)}
              >
                {TAB_LABELS[id]}
              </button>
            ))}
          </div>

          <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
            {tab === 'run' ? <RunTab product={product} /> : null}
            {tab === 'compare' ? (
              <CompareTab product={product} onGoToRun={() => setTab('run')} />
            ) : null}
            {tab === 'history' ? <HistoryTab productId={product.id} /> : null}
            {tab === 'ask' ? <AskTab productId={product.id} productName={product.name} /> : null}
          </div>
        </section>
      </div>

      {confirmingDelete ? (
        <Modal
          title={`Delete "${product.name}"?`}
          onClose={() => setConfirmingDelete(false)}
          footer={
            <>
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => setConfirmingDelete(false)}
                disabled={deleteProduct.isPending}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={onDelete}
                disabled={deleteProduct.isPending}
              >
                {deleteProduct.isPending ? 'Deleting…' : 'Delete product'}
              </button>
            </>
          }
        >
          <p>
            This removes the product, its {product.sources.length} source
            {product.sources.length === 1 ? '' : 's'}, every stored snapshot, and every report in its
            history. It cannot be undone.
          </p>
        </Modal>
      ) : null}
    </div>
  );
}

export default ProductDetailPage;
