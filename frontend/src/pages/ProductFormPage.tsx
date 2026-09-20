import { useParams } from 'react-router-dom';
import { usePageTitle } from '../layout/usePageTitle';
import { UnderConstruction } from './UnderConstruction';

/**
 * The New / Edit SaaS Product form. ADMIN only - the route is reachable by anyone signed in, and the
 * backend rejects the write, which is the enforcement that counts.
 *
 * @returns the product form page
 */
export function ProductFormPage() {
  const { productId } = useParams<{ productId?: string }>();
  const editing = Boolean(productId);
  usePageTitle(editing ? 'Edit SaaS Product' : 'New SaaS Product');

  return (
    <div className="stack">
      <h1>{editing ? 'Edit SaaS Product' : 'New SaaS Product'}</h1>
      <UnderConstruction
        page={editing ? 'Edit SaaS Product' : 'New SaaS Product'}
        description="Will hold the name/description fields and the add-source form with all five source types."
      />
    </div>
  );
}

export default ProductFormPage;
