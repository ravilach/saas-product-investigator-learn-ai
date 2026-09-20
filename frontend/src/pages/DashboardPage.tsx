import { useState } from 'react';
import { Link } from 'react-router-dom';
import { PRODUCTS_PAGE_SIZE, useProducts } from '../api/products';
import type { SaasProduct } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { LastRunPill } from '../components/Badges';
import { Pagination } from '../components/Pagination';
import { EmptyState } from '../components/states/EmptyState';
import { ErrorState } from '../components/states/ErrorState';
import { SkeletonCardGrid } from '../components/states/Skeleton';
import { usePageTitle } from '../layout/usePageTitle';
import { timeAgo } from '../utils/format';
import styles from './DashboardPage.module.css';

/**
 * The Dashboard: a card grid of every tracked SaaS Product.
 *
 * Each card shows what DESIGN & UX asks for - a last-run status pill and a time-since-last-run - and
 * the whole card is the link to the product, rather than a small "View" affordance in the corner: a
 * card with one destination should be one target, which also makes it comfortably bigger than the
 * 44px minimum on a phone.
 *
 * @returns the dashboard
 */
export function DashboardPage() {
  const { isAdmin } = useAuth();
  const [page, setPage] = useState(0);
  const { data, isPending, isError, error, refetch } = useProducts(page);

  // Not the product names: the top bar shows a page-level context line, and a list page's context is
  // the list. The product detail page is where a name belongs.
  usePageTitle('SaaS Products');

  return (
    <div className="stack">
      <div className={styles.header}>
        <h1>SaaS Products</h1>
        {/* Creating a product is ADMIN-only on the backend. Hiding the button for a READ_ONLY user is
            a courtesy so they are not offered a form that would 403 on submit - not the enforcement. */}
        {isAdmin ? (
          <Link to="/products/new" className="btn btn-primary">
            New SaaS Product
          </Link>
        ) : null}
      </div>

      {isPending ? <SkeletonCardGrid count={6} /> : null}

      {isError ? <ErrorState error={error} onRetry={() => refetch()} /> : null}

      {data && data.content.length === 0 ? (
        <EmptyState
          title="No SaaS Products yet"
          description={
            isAdmin
              ? 'Add a product, point it at a docs site, changelog, Jira instance, or any MCP server, and run it to see what changed.'
              : 'Nothing is being tracked yet. An admin needs to add the first product.'
          }
          action={
            isAdmin ? (
              <Link to="/products/new" className="btn btn-primary">
                New SaaS Product
              </Link>
            ) : null
          }
        />
      ) : null}

      {data && data.content.length > 0 ? (
        <>
          <div className={styles.grid}>
            {data.content.map((product) => (
              <ProductCard key={product.id} product={product} />
            ))}
          </div>
          <Pagination
            page={data}
            onChange={setPage}
            label="SaaS Products"
            pageSize={PRODUCTS_PAGE_SIZE}
          />
        </>
      ) : null}
    </div>
  );
}

/**
 * One product card.
 *
 * @param props.product the product to show
 * @returns the card, as a link to the product's detail page
 */
function ProductCard({ product }: { product: SaasProduct }) {
  return (
    <Link to={`/products/${product.id}`} className={`card ${styles.card}`}>
      <span className={styles.cardTitle}>{product.name}</span>

      {product.description ? (
        <p className={styles.cardDescription}>{product.description}</p>
      ) : (
        <p className={styles.cardDescription}>
          {product.sourceCount} source{product.sourceCount === 1 ? '' : 's'} configured
        </p>
      )}

      <div className={styles.cardFooter}>
        <LastRunPill lastRun={product.lastRun} />
        <span className={styles.cardMeta}>{timeAgo(product.lastRun?.runAt)}</span>
      </div>
    </Link>
  );
}

export default DashboardPage;
