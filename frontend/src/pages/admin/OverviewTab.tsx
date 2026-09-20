import { useAdminHealth, useAdminStats } from '../../api/admin';
import type { AdminHealth, AdminStats, ComponentHealth, ProviderHealth } from '../../api/types';
import { ErrorState } from '../../components/states/ErrorState';
import { SkeletonText } from '../../components/states/Skeleton';
import { formatDateTime, formatDuration, humaniseEnum, providerLabel, timeAgo } from '../../utils/format';
import styles from './Admin.module.css';

/**
 * The Admin Console's Overview tab: the aggregate counters, then live health.
 *
 * The two halves are separate queries rendered independently rather than gated on both resolving.
 * That is the point of the split: health is the thing an admin opens this page for when something is
 * broken, and a failing `/api/admin/stats` should not be able to hide it.
 *
 * @returns the overview tab
 */
export function OverviewTab() {
  const stats = useAdminStats();
  const health = useAdminHealth();

  return (
    <div className="stack">
      <section className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>At a glance</h2>

        {stats.isPending ? (
          <SkeletonText lines={4} label="Loading statistics" />
        ) : stats.isError ? (
          <ErrorState
            error={stats.error}
            title="Could not load statistics"
            onRetry={() => stats.refetch()}
          />
        ) : stats.data ? (
          <StatGrid stats={stats.data} />
        ) : null}
      </section>

      {stats.data && stats.data.recentRuns.length > 0 ? (
        <section className="card">
          <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>Recent runs</h2>
          <div className="table-scroll">
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">Product</th>
                  <th scope="col">Type</th>
                  <th scope="col">Status</th>
                  <th scope="col">When</th>
                </tr>
              </thead>
              <tbody>
                {stats.data.recentRuns.map((run, index) => (
                  // Index-keyed: runs have no id in this projection, and the list is replaced wholesale
                  // on every refetch rather than being reordered in place.
                  <tr key={index}>
                    <td>{run.productName}</td>
                    <td>{humaniseEnum(run.runType)}</td>
                    <td>
                      <span className={`pill ${runStatusClass(run.status)}`}>
                        {humaniseEnum(run.status)}
                      </span>
                    </td>
                    <td>
                      {formatDateTime(run.runAt)}
                      <div className="muted" style={{ fontSize: 'var(--text-xs)' }}>
                        {timeAgo(run.runAt)}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ) : null}

      <section className="card">
        <div
          className="row"
          style={{ justifyContent: 'space-between', flexWrap: 'wrap', marginBottom: 'var(--space-4)' }}
        >
          <h2 style={{ fontSize: 'var(--text-lg)' }}>Health</h2>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={() => health.refetch()}
            disabled={health.isFetching}
          >
            {health.isFetching ? 'Checking…' : 'Refresh'}
          </button>
        </div>

        {health.isPending ? (
          <SkeletonText lines={4} label="Checking health" />
        ) : health.isError ? (
          <ErrorState
            error={health.error}
            title="Could not check health"
            onRetry={() => health.refetch()}
          />
        ) : health.data ? (
          <HealthPanel health={health.data} />
        ) : null}
      </section>
    </div>
  );
}

/**
 * The six counters.
 *
 * @param props.stats the stats payload
 * @returns the stat grid
 */
function StatGrid({ stats }: { stats: AdminStats }) {
  return (
    <div className={styles.statGrid}>
      <Stat label="Users" value={stats.totalUsers} />
      <Stat label="Products" value={stats.totalProducts} />
      <Stat label="Sources configured" value={stats.totalSourcesConfigured} />
      <Stat label="Runs, last 24h" value={stats.runsLast24h} />
      <Stat label="Runs, last 7d" value={stats.runsLast7d} />
      <Stat
        label="Success rate, 7d"
        // A null rate means nothing ran, which is a different fact from "0% of runs succeeded" - so it
        // is rendered as a dash with the reason underneath rather than as 0%.
        value={
          stats.runSuccessRate7d === null ? '—' : `${Math.round(stats.runSuccessRate7d * 100)}%`
        }
        hint={
          stats.runSuccessRate7d === null
            ? 'No runs in the last 7 days'
            : `Average run ${formatDuration(stats.avgRunDurationSeconds7d)}`
        }
      />
    </div>
  );
}

/**
 * One counter card.
 *
 * @param props.label what is being counted
 * @param props.value the number, pre-formatted if it is not a plain count
 * @param props.hint an optional line of context beneath it
 * @returns the card
 */
function Stat({
  label,
  value,
  hint,
}: {
  label: string;
  value: number | string;
  hint?: string;
}) {
  return (
    <div className={styles.stat}>
      <span className={styles.statLabel}>{label}</span>
      <span className={styles.statValue}>{value}</span>
      {hint ? <span className={styles.statHint}>{hint}</span> : null}
    </div>
  );
}

/**
 * Component health, provider health, the last successful run, and the operational links.
 *
 * @param props.health the health payload
 * @returns the panel
 */
function HealthPanel({ health }: { health: AdminHealth }) {
  return (
    <div className="stack">
      <div className="row" style={{ flexWrap: 'wrap' }}>
        <span className={`pill ${statusClass(health.status)}`}>{health.status}</span>
        <span className="muted" style={{ fontSize: 'var(--text-sm)' }}>
          overall
        </span>
      </div>

      <div>
        <h3 style={{ fontSize: 'var(--text-sm)', marginBottom: 'var(--space-2)' }}>Components</h3>
        <ul className={styles.healthList}>
          {health.components.map((component) => (
            <ComponentRow key={component.name} component={component} />
          ))}
        </ul>
      </div>

      <div>
        <h3 style={{ fontSize: 'var(--text-sm)', marginBottom: 'var(--space-2)' }}>AI providers</h3>
        <ul className={styles.healthList}>
          {health.providers.map((provider) => (
            <ProviderRow key={provider.provider} provider={provider} />
          ))}
        </ul>
      </div>

      <div>
        <h3 style={{ fontSize: 'var(--text-sm)', marginBottom: 'var(--space-2)' }}>
          Last successful run
        </h3>
        {health.lastSuccessfulRun ? (
          <p style={{ fontSize: 'var(--text-sm)', margin: 0 }}>
            <strong>{health.lastSuccessfulRun.productName}</strong> ·{' '}
            {formatDateTime(health.lastSuccessfulRun.runAt)} ({timeAgo(health.lastSuccessfulRun.runAt)})
            <span className="muted"> · {health.lastSuccessfulRun.outcome}</span>
          </p>
        ) : (
          <p className="muted" style={{ fontSize: 'var(--text-sm)', margin: 0 }}>
            Nothing has completed successfully yet.
          </p>
        )}
      </div>

      <div>
        <h3 style={{ fontSize: 'var(--text-sm)', marginBottom: 'var(--space-2)' }}>Operations</h3>
        <div className="row" style={{ flexWrap: 'wrap' }}>
          {/*
            Real links to the backend's own endpoints, opened in a new tab. They are absolute paths
            from the API rather than hardcoded here, because in the container build the API is same
            origin and in dev it is proxied - either way the server knows the right path and the
            frontend should not be guessing it.
          */}
          <a
            className="btn btn-secondary btn-sm"
            href={health.links.apiDocs}
            target="_blank"
            rel="noreferrer"
          >
            API docs
          </a>
          <a
            className="btn btn-secondary btn-sm"
            href={health.links.prometheus}
            target="_blank"
            rel="noreferrer"
          >
            Prometheus metrics
          </a>
          <a
            className="btn btn-secondary btn-sm"
            href={health.links.actuatorHealth}
            target="_blank"
            rel="noreferrer"
          >
            Actuator health
          </a>
        </div>
      </div>
    </div>
  );
}

/**
 * One infrastructure component's row.
 *
 * @param props.component the component health
 * @returns the row
 */
function ComponentRow({ component }: { component: ComponentHealth }) {
  return (
    <li className={styles.healthRow}>
      <span className={styles.healthName}>{component.name}</span>
      <span className="row" style={{ flexWrap: 'wrap', justifyContent: 'flex-end' }}>
        {component.details ? (
          <span className={styles.healthDetail}>{summariseDetails(component.details)}</span>
        ) : null}
        <span className={`pill ${statusClass(component.status)}`}>{component.status}</span>
      </span>
    </li>
  );
}

/**
 * One provider's row: whether a key resolves, and from where.
 *
 * @param props.provider the provider health
 * @returns the row
 */
function ProviderRow({ provider }: { provider: ProviderHealth }) {
  return (
    <li className={styles.healthRow}>
      <span className={styles.healthName}>{providerLabel(provider.provider)}</span>
      <span className="row" style={{ flexWrap: 'wrap', justifyContent: 'flex-end' }}>
        {/* The last 4 and the source - which is the most this endpoint will ever return about a key. */}
        {provider.configured ? (
          <span className={styles.healthDetail}>
            {provider.source ? humaniseEnum(provider.source) : 'unknown source'}
            {provider.last4 ? ` · ends ${provider.last4}` : ''}
          </span>
        ) : null}
        <span className={`pill ${provider.configured ? 'pill-success' : 'pill-warning'}`}>
          {provider.configured ? 'Configured' : 'Not configured'}
        </span>
      </span>
    </li>
  );
}

/**
 * Renders a component's detail map as one short line.
 *
 * Only scalars, and only a few of them: Mongo's indicator returns a nested `validationResult` that is
 * useful to nobody at a glance, and a whole JSON tree in a health row buries the status pill.
 *
 * @param details the detail map
 * @returns a `key=value` summary, or an empty string if nothing was worth showing
 */
function summariseDetails(details: Record<string, unknown>): string {
  return Object.entries(details)
    .filter(([, value]) => typeof value !== 'object' || value === null)
    .slice(0, 3)
    .map(([key, value]) => `${key}=${String(value)}`)
    .join(' · ');
}

/**
 * Picks a pill class for a Spring health status.
 *
 * @param status `UP`, `DOWN`, `OUT_OF_SERVICE`, or anything else an indicator returns
 * @returns the pill class
 */
function statusClass(status: string): string {
  if (status === 'UP') return 'pill-success';
  if (status === 'DOWN') return 'pill-error';
  // `UNKNOWN` and `OUT_OF_SERVICE` are neither healthy nor confirmed broken, which is what warning is.
  return 'pill-warning';
}

/**
 * Picks a pill class for a run status.
 *
 * @param status the run's status string
 * @returns the pill class
 */
function runStatusClass(status: string): string {
  if (status === 'COMPLETED') return 'pill-success';
  if (status === 'FAILED') return 'pill-error';
  if (status === 'RUNNING') return 'pill-running';
  return 'pill-neutral';
}

export default OverviewTab;
