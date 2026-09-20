import { useParams } from 'react-router-dom';
import { usePageTitle } from '../layout/usePageTitle';
import { UnderConstruction } from './UnderConstruction';

/**
 * A SaaS Product's detail page: source configuration alongside the Run / Compare / History / Ask
 * tabs.
 *
 * @returns the product detail page
 */
export function ProductDetailPage() {
  const { productId } = useParams<{ productId: string }>();
  usePageTitle('Product');

  return (
    <div className="stack">
      <h1>Product</h1>
      <p className="muted">Product id: {productId}</p>
      <UnderConstruction
        page="Product detail"
        description="Will show source configuration plus the Run, Compare, History, and Ask tabs."
      />
    </div>
  );
}

export default ProductDetailPage;
