import { usePageTitle } from '../layout/usePageTitle';
import { UnderConstruction } from './UnderConstruction';

/**
 * The Dashboard: a card grid of SaaS Products, each with a last-run status pill and
 * time-since-last-run.
 *
 * @returns the dashboard
 */
export function DashboardPage() {
  usePageTitle('SaaS Products');

  return (
    <div className="stack">
      <h1>SaaS Products</h1>
      <UnderConstruction
        page="Dashboard"
        description="Will show a card grid of every SaaS Product with its last-run status and age."
      />
    </div>
  );
}

export default DashboardPage;
