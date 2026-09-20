import { EmptyState } from '../components/states/EmptyState';

/**
 * A deliberately honest placeholder for a page whose real implementation lands in BUILD ORDER step 9.
 *
 * Step 8 builds the frontend's foundations - tokens, theme, query client, routing, auth, error
 * handling - and step 9 builds the screens on top of them. Rather than leaving the routes broken or
 * faking a screen, each not-yet-built page renders this and names the step it is waiting on. That
 * keeps the shell genuinely walkable now (login, navigation, theme, guards can all be exercised
 * end to end) without pretending there is data behind it.
 *
 * Every use of this component is expected to be gone by the end of step 9.
 *
 * @param props.page the page's name, as it appears in the build order
 * @param props.description what it will do once it exists
 * @returns the placeholder
 */
export function UnderConstruction({
  page,
  description,
}: {
  page: string;
  description: string;
}) {
  return (
    <EmptyState
      title={`${page} — not built yet`}
      description={`${description} This screen is BUILD ORDER step 9; step 8 built the shell you are looking at.`}
    />
  );
}
