import { useEffect, useRef, useState } from 'react';
import { useAdminSettings, useUpdateAdminSettings } from '../../api/admin';
import { ErrorState } from '../../components/states/ErrorState';
import { SkeletonText } from '../../components/states/Skeleton';
import { useToast } from '../../components/toast/ToastProvider';
import { validateBoundedInteger } from '../product/sourceValidation';
import styles from './Admin.module.css';

/**
 * The Admin Console's Settings tab: the crawl defaults every website source falls back to.
 *
 * These are defaults, not limits - a source can override either one, up to the ceilings shown beside
 * them. The ceilings themselves are not editable here because they are configuration, not data: they
 * come from `application.properties` and changing them is a deployment decision.
 *
 * @returns the settings tab
 */
export function SettingsTab() {
  const settings = useAdminSettings();
  const update = useUpdateAdminSettings();
  const { showError, showSuccess } = useToast();

  // Strings, not numbers: a number-typed state cannot represent the half-typed "1" on the way to "10",
  // and coercing on every keystroke makes clearing the field impossible.
  const [depth, setDepth] = useState('');
  const [pages, setPages] = useState('');
  const [submitted, setSubmitted] = useState(false);

  // Seeded exactly once, the first time the query resolves. A background refetch landing mid-edit and
  // replacing a half-typed number is the same maddening bug the product form guards against, and the
  // ref is what makes "once" mean once rather than "once per object identity".
  const seeded = useRef(false);
  useEffect(() => {
    if (seeded.current || !settings.data) return;
    seeded.current = true;
    setDepth(String(settings.data.defaultMaxDepth));
    setPages(String(settings.data.defaultMaxPages));
  }, [settings.data]);

  if (settings.isPending) {
    return (
      <div className="card">
        <SkeletonText lines={4} label="Loading settings" />
      </div>
    );
  }

  if (settings.isError || !settings.data) {
    return (
      <ErrorState
        error={settings.error}
        title="Could not load settings"
        onRetry={() => settings.refetch()}
      />
    );
  }

  const { maxAllowedDepth, maxAllowedPages } = settings.data;
  // `validateBoundedInteger` treats blank as "use the default", which is right on the source form and
  // wrong here - these *are* the defaults, so there is nothing behind them to fall back to. Hence the
  // required check in front of it rather than a second copy of the range logic.
  const depthError =
    depth.trim() === ''
      ? 'A default crawl depth is required.'
      : validateBoundedInteger(depth, 1, maxAllowedDepth, 'The default depth');
  const pagesError =
    pages.trim() === ''
      ? 'A default page limit is required.'
      : validateBoundedInteger(pages, 1, maxAllowedPages, 'The default page limit');
  const invalid = Boolean(depthError || pagesError);

  const unchanged =
    depth === String(settings.data.defaultMaxDepth) && pages === String(settings.data.defaultMaxPages);

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (invalid) return;
    try {
      await update.mutateAsync({
        defaultMaxDepth: Number(depth),
        defaultMaxPages: Number(pages),
      });
      setSubmitted(false);
      showSuccess('The crawl defaults were saved.');
    } catch (cause) {
      showError(cause, 'Could not save the settings');
    }
  };

  return (
    <div className="stack">
      <section className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-2)' }}>Crawl defaults</h2>
        <p className="muted" style={{ fontSize: 'var(--text-sm)' }}>
          Used by any website or SaaS URL source that does not set its own limits. Changing these
          changes what those sources do on their next run.
        </p>

        <form className="stack" onSubmit={onSubmit} style={{ marginTop: 'var(--space-4)' }}>
          <div className={styles.filters}>
            <div className={styles.filterField}>
              <label className="label" htmlFor="default-max-depth">
                Default crawl depth
              </label>
              <input
                id="default-max-depth"
                className="input"
                // `inputMode` rather than `type="number"`: a number input's spinner and its
                // locale-dependent parsing are more trouble than they solve for a small integer, and
                // the validation is shared with the source form either way.
                inputMode="numeric"
                value={depth}
                onChange={(event) => setDepth(event.target.value)}
                aria-invalid={submitted && Boolean(depthError)}
              />
              <p className="hint">1 to {maxAllowedDepth}. How many links deep a crawl follows.</p>
              {submitted && depthError ? <p className="field-error">{depthError}</p> : null}
            </div>

            <div className={styles.filterField}>
              <label className="label" htmlFor="default-max-pages">
                Default page limit
              </label>
              <input
                id="default-max-pages"
                className="input"
                inputMode="numeric"
                value={pages}
                onChange={(event) => setPages(event.target.value)}
                aria-invalid={submitted && Boolean(pagesError)}
              />
              <p className="hint">1 to {maxAllowedPages}. The most pages one source will fetch.</p>
              {submitted && pagesError ? <p className="field-error">{pagesError}</p> : null}
            </div>
          </div>

          <div className="row" style={{ flexWrap: 'wrap' }}>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={update.isPending || unchanged}
            >
              {update.isPending ? 'Saving…' : 'Save defaults'}
            </button>
            {unchanged ? (
              <span className="muted" style={{ fontSize: 'var(--text-xs)' }}>
                Nothing to save.
              </span>
            ) : null}
          </div>
        </form>
      </section>

      <section className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-2)' }}>Ceilings</h2>
        <p className="muted" style={{ fontSize: 'var(--text-sm)' }}>
          The hard limits no source may exceed, whatever it configures. These come from the server's
          configuration and are changed by a deployment, not from here.
        </p>
        <div className={styles.statGrid} style={{ marginTop: 'var(--space-4)' }}>
          <div className={styles.stat}>
            <span className={styles.statLabel}>Max allowed depth</span>
            <span className={styles.statValue}>{maxAllowedDepth}</span>
          </div>
          <div className={styles.stat}>
            <span className={styles.statLabel}>Max allowed pages</span>
            <span className={styles.statValue}>{maxAllowedPages}</span>
          </div>
        </div>
      </section>
    </div>
  );
}

export default SettingsTab;
