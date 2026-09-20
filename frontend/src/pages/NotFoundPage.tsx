import { Link } from 'react-router-dom';
import { EmptyState } from '../components/states/EmptyState';
import { usePageTitle } from '../layout/usePageTitle';

/**
 * Rendered for any URL inside the shell that matches no route.
 *
 * Kept inside the layout rather than replacing it, so the sidebar and top bar are still there - a
 * mistyped URL should not look like the app fell over.
 *
 * @returns the not-found page
 */
export function NotFoundPage() {
  usePageTitle('Page not found');

  return (
    <EmptyState
      title="Page not found"
      description="That URL does not match anything in the app. It may have been a link to something that has since been deleted."
      action={
        <Link className="btn btn-primary" to="/">
          Back to SaaS Products
        </Link>
      }
    />
  );
}

export default NotFoundPage;
