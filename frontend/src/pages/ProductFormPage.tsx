import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useAdminSettings } from '../api/admin';
import { useCreateProduct, useProduct, useUpdateProduct } from '../api/products';
import type { SaasProduct, SourceConfigRequest } from '../api/types';
import { useToast } from '../components/toast/ToastProvider';
import { ErrorState } from '../components/states/ErrorState';
import { SkeletonText } from '../components/states/Skeleton';
import { usePageTitle } from '../layout/usePageTitle';
import { SourceFields } from './product/SourceFields';
import {
  emptySourceDraft,
  FALLBACK_CRAWL_LIMITS,
  validateProduct,
  validateSources,
  type SourceDraft,
} from './product/sourceValidation';

/**
 * The New / Edit SaaS Product form.
 *
 * One component for both, because they differ only in where the initial state comes from and which
 * mutation the submit calls. Splitting them would duplicate the source list editor, which is the bulk
 * of the screen and the part with the rules in it.
 *
 * The form is uncontrolled by the server while it is open: the product query seeds the state once and
 * is not allowed to overwrite it afterwards. A background refetch replacing half-typed input would be
 * a genuinely maddening bug, and TanStack Query will refetch this key after any mutation elsewhere.
 *
 * @returns the form
 */
export function ProductFormPage() {
  const { productId } = useParams<{ productId: string }>();
  const editing = Boolean(productId);
  const navigate = useNavigate();
  const { showToast, showError } = useToast();

  const productQuery = useProduct(productId);
  // Only for the crawl-limit hints and ceilings. This form is ADMIN-only, so the caller can read it -
  // but a failure here must not block the form, hence the fallback rather than a required load.
  const settingsQuery = useAdminSettings();
  const limits = settingsQuery.data ?? FALLBACK_CRAWL_LIMITS;
  const defaults = settingsQuery.data
    ? { maxDepth: settingsQuery.data.defaultMaxDepth, maxPages: settingsQuery.data.defaultMaxPages }
    : undefined;

  const create = useCreateProduct();
  const update = useUpdateProduct(productId ?? '');
  const saving = create.isPending || update.isPending;

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [sources, setSources] = useState<SourceDraft[]>([]);
  const [seeded, setSeeded] = useState(false);
  // Populated on submit rather than on every keystroke: validating a field the user has not finished
  // typing yet means the form shouts at them for the first character of a URL.
  const [submitted, setSubmitted] = useState(false);

  usePageTitle(editing ? (productQuery.data?.name ?? 'Edit product') : 'New SaaS Product');

  /** Seeds the form from the loaded product, exactly once. */
  useEffect(() => {
    if (!editing || seeded || !productQuery.data) return;
    const product = productQuery.data;
    setName(product.name);
    setDescription(product.description ?? '');
    setSources(product.sources.map(toDraft));
    setSeeded(true);
  }, [editing, seeded, productQuery.data]);

  const errors = useMemo(
    () => ({
      product: validateProduct(name, description),
      sources: validateSources(sources, limits),
    }),
    [name, description, sources, limits],
  );
  const valid =
    Object.keys(errors.product).length === 0 && Object.keys(errors.sources).length === 0;

  /**
   * Applies a change to one source by its client-side key.
   *
   * @param key the draft's key
   * @param change the fields to merge in
   */
  const changeSource = (key: string, change: Partial<SourceDraft>) =>
    setSources((current) =>
      current.map((draft) => (draft.key === key ? { ...draft, ...change } : draft)),
    );

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (!valid) return;

    const body = {
      name: name.trim(),
      // Empty description is sent as null rather than "", so the API's "no description" and "a
      // description that happens to be blank" are not two ways of storing the same thing.
      description: description.trim() || null,
      sources: sources.map(toRequest),
    };

    try {
      if (editing && productId) {
        await update.mutateAsync(body);
        showToast({ tone: 'success', message: `Saved ${body.name}.` });
        navigate(`/products/${productId}`);
      } else {
        const created = await create.mutateAsync(body);
        showToast({ tone: 'success', message: `Created ${created.name}.` });
        navigate(`/products/${created.id}`);
      }
    } catch (cause) {
      // Inline would be wrong here: the failure is not attributable to a field the user can see - a
      // duplicate product name, a URL the backend could not parse - so it goes to the toast layer with
      // the backend's own message.
      showError(cause, editing ? 'Could not save' : 'Could not create');
    }
  };

  if (editing && productQuery.isPending) {
    return (
      <div className="stack">
        <h1>Edit SaaS Product</h1>
        <SkeletonText lines={6} label="Loading product" />
      </div>
    );
  }

  if (editing && productQuery.isError) {
    return (
      <div className="stack">
        <h1>Edit SaaS Product</h1>
        <ErrorState error={productQuery.error} onRetry={() => productQuery.refetch()}>
          <Link to="/" className="btn btn-secondary btn-sm">
            Back to SaaS Products
          </Link>
        </ErrorState>
      </div>
    );
  }

  return (
    <form className="stack" onSubmit={onSubmit} noValidate>
      <h1>{editing ? 'Edit SaaS Product' : 'New SaaS Product'}</h1>

      <div className="card stack">
        <div className="field">
          <label className="label" htmlFor="product-name">
            Name
          </label>
          <input
            id="product-name"
            className="input"
            value={name}
            onChange={(event) => setName(event.target.value)}
            aria-invalid={submitted && errors.product.name ? true : undefined}
            aria-describedby={submitted && errors.product.name ? 'product-name-error' : undefined}
            maxLength={200}
            autoFocus={!editing}
          />
          {submitted && errors.product.name ? (
            <span id="product-name-error" className="field-error">
              {errors.product.name}
            </span>
          ) : null}
        </div>

        <div className="field" style={{ marginBottom: 0 }}>
          <label className="label" htmlFor="product-description">
            Description <span className="muted">(optional)</span>
          </label>
          <textarea
            id="product-description"
            className="textarea"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={2000}
            aria-describedby="product-description-hint"
          />
          {submitted && errors.product.description ? (
            <span className="field-error">{errors.product.description}</span>
          ) : (
            <span id="product-description-hint" className="hint">
              Passed to the model as context, so it is worth saying what the product is and what kind
              of change matters to you.
            </span>
          )}
        </div>
      </div>

      <section className="stack">
        <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap' }}>
          <h2>Sources</h2>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => setSources((current) => [...current, emptySourceDraft()])}
          >
            Add source
          </button>
        </div>

        {sources.length === 0 ? (
          <p className="muted">
            No sources yet. A product can be saved without any - it just has nothing to compare until
            one is added. Add as many of each type as you need; there is no limit.
          </p>
        ) : (
          sources.map((draft) => (
            <SourceFields
              key={draft.key}
              draft={draft}
              errors={submitted ? errors.sources[draft.key] : undefined}
              limits={limits}
              defaults={defaults}
              onChange={(change) => changeSource(draft.key, change)}
              onRemove={() =>
                setSources((current) => current.filter((other) => other.key !== draft.key))
              }
            />
          ))
        )}
      </section>

      <div className="row" style={{ flexWrap: 'wrap' }}>
        <button type="submit" className="btn btn-primary" disabled={saving}>
          {saving ? 'Saving…' : editing ? 'Save changes' : 'Create SaaS Product'}
        </button>
        <Link
          to={editing && productId ? `/products/${productId}` : '/'}
          className="btn btn-secondary"
        >
          Cancel
        </Link>
      </div>

      {submitted && !valid ? (
        // A single pointer rather than a repeated list of every message: the messages are already next
        // to their fields, and a summary that restates them all is the thing people stop reading.
        <p className="field-error" role="alert">
          Some fields need attention before this can be saved.
        </p>
      ) : null}
    </form>
  );
}

/**
 * Converts a stored source into an editable draft.
 *
 * @param source the source as the API returned it
 * @returns the draft
 */
function toDraft(source: SaasProduct['sources'][number]): SourceDraft {
  return {
    key: `stored-${source.name}`,
    type: source.type,
    name: source.name,
    endpointUrl: source.endpointUrl,
    authToken: '',
    authTokenConfigured: source.authTokenConfigured,
    authTokenLast4: source.authTokenLast4,
    authTokenCleared: false,
    // `maxDepth` is what the user set; `effectiveMaxDepth` is that or the system default. The form
    // edits the override, so the null-means-default distinction has to survive a round trip - showing
    // the effective value here would silently freeze a default into an explicit override on next save.
    maxDepth: source.maxDepth === null ? '' : String(source.maxDepth),
    maxPages: source.maxPages === null ? '' : String(source.maxPages),
  };
}

/**
 * Converts a draft into the request shape.
 *
 * @param draft the draft to submit
 * @returns the source as the API expects it
 */
function toRequest(draft: SourceDraft): SourceConfigRequest {
  const request: SourceConfigRequest = {
    type: draft.type,
    name: draft.name.trim(),
    endpointUrl: draft.endpointUrl.trim(),
  };

  // The three-state contract from `SourceConfigRequest`'s own docs: a typed value replaces, an
  // explicit clear sends "", and anything else omits the field entirely to mean "leave it alone".
  if (draft.authToken) request.authToken = draft.authToken;
  else if (draft.authTokenCleared) request.authToken = '';

  if (draft.maxDepth.trim()) request.maxDepth = Number(draft.maxDepth);
  if (draft.maxPages.trim()) request.maxPages = Number(draft.maxPages);

  return request;
}

export default ProductFormPage;
