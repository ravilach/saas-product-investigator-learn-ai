import { describe, expect, it } from 'vitest';
import {
  emptySourceDraft,
  validateBoundedInteger,
  validateEndpointUrl,
  validateProduct,
  validateSource,
  validateSources,
  type CrawlLimits,
  type SourceDraft,
} from './sourceValidation';

/** The limits under test, chosen to be distinguishable from the fallbacks. */
const LIMITS: CrawlLimits = { maxAllowedDepth: 5, maxAllowedPages: 200 };

/**
 * Builds a valid draft, so each test can make exactly one thing wrong.
 *
 * @param overrides the fields this test cares about
 * @returns a draft
 */
function draft(overrides: Partial<SourceDraft> = {}): SourceDraft {
  return {
    ...emptySourceDraft(),
    name: 'Docs',
    endpointUrl: 'https://example.com/docs',
    ...overrides,
  };
}

describe('validateSource', () => {
  it('accepts a complete website source', () => {
    expect(validateSource(draft(), LIMITS)).toEqual({});
  });

  it('requires a name and a URL', () => {
    const errors = validateSource(draft({ name: '   ', endpointUrl: '' }), LIMITS);

    expect(errors.name).toBe('Every source needs a name.');
    expect(errors.endpointUrl).toBe('Every source needs a URL.');
  });

  it('rejects a name longer than the backend allows', () => {
    const errors = validateSource(draft({ name: 'x'.repeat(121) }), LIMITS);

    expect(errors.name).toBe('A source name must be 120 characters or fewer.');
    // The boundary itself is valid, which is the half of an inclusive limit that gets broken.
    expect(validateSource(draft({ name: 'x'.repeat(120) }), LIMITS).name).toBeUndefined();
  });

  it('treats blank crawl limits as "use the default" rather than as missing input', () => {
    const errors = validateSource(draft({ maxDepth: '', maxPages: '' }), LIMITS);

    // This is the whole null-means-default contract: a blank field must not be an error, because
    // blank is how the form says "inherit the admin setting".
    expect(errors).toEqual({});
  });

  it('enforces the crawl ceilings on a website source', () => {
    const errors = validateSource(draft({ maxDepth: '6', maxPages: '201' }), LIMITS);

    expect(errors.maxDepth).toBe('Max depth must be between 0 and 5.');
    expect(errors.maxPages).toBe('Max pages must be between 1 and 200.');
  });

  it('allows depth 0 but not pages 0', () => {
    // Depth 0 means "just this page", which is a real choice. Zero pages means fetch nothing, which
    // is not - it is a source that cannot ever produce a snapshot.
    expect(validateSource(draft({ maxDepth: '0' }), LIMITS).maxDepth).toBeUndefined();
    expect(validateSource(draft({ maxPages: '0' }), LIMITS).maxPages).toBe(
      'Max pages must be between 1 and 200.',
    );
  });

  it('skips the crawl limits for MCP sources, whose crawl fields do not exist', () => {
    // The values are deliberately out of range: an MCP draft carrying leftovers from before a type
    // switch must not be blocked by fields the form no longer shows.
    const errors = validateSource(
      draft({ type: 'DOCS_MCP', maxDepth: '99', maxPages: '9999' }),
      LIMITS,
    );

    expect(errors).toEqual({});
  });
});

describe('validateEndpointUrl', () => {
  it('accepts http and https', () => {
    expect(validateEndpointUrl('http://example.com')).toBeNull();
    expect(validateEndpointUrl('https://example.com/docs?page=2')).toBeNull();
  });

  it('rejects a bare hostname, which is the most common mistake', () => {
    // "example.com" is not a URL: `new URL` throws on it, and a user typing it has no idea why the
    // save failed unless the message says what shape is wanted.
    expect(validateEndpointUrl('example.com')).toBe(
      'That is not a valid URL. It needs to look like https://example.com/docs.',
    );
  });

  it('rejects a non-HTTP scheme', () => {
    expect(validateEndpointUrl('ftp://example.com')).toBe(
      'The URL must be http or https. Both MCP servers and crawled pages are reached over HTTP.',
    );
    // `file:` parses fine and has no host, which is exactly the case a naive "does it parse" check
    // would let through to a backend that then tries to fetch a local path.
    expect(validateEndpointUrl('file:///etc/passwd')).not.toBeNull();
  });

  it('trims surrounding whitespace rather than failing on a pasted URL', () => {
    expect(validateEndpointUrl('  https://example.com  ')).toBeNull();
  });
});

describe('validateBoundedInteger', () => {
  it('rejects the values Number() would silently accept', () => {
    // Each of these becomes a valid number via `Number()`, and each would reach the backend as
    // something other than what was typed.
    expect(validateBoundedInteger('2.5', 0, 10, 'Max depth')).toBe('Max depth must be a whole number.');
    expect(validateBoundedInteger('2e3', 0, 10, 'Max depth')).toBe('Max depth must be a whole number.');
    expect(validateBoundedInteger('-1', 0, 10, 'Max depth')).toBe('Max depth must be a whole number.');
  });

  it('accepts a value padded with spaces', () => {
    expect(validateBoundedInteger('  3  ', 0, 10, 'Max depth')).toBeNull();
  });

  it('treats the bounds as inclusive', () => {
    expect(validateBoundedInteger('0', 0, 10, 'Max depth')).toBeNull();
    expect(validateBoundedInteger('10', 0, 10, 'Max depth')).toBeNull();
    expect(validateBoundedInteger('11', 0, 10, 'Max depth')).not.toBeNull();
  });
});

describe('validateSources', () => {
  it('returns no entry for a valid list', () => {
    expect(validateSources([draft({ name: 'Docs' }), draft({ name: 'Blog' })], LIMITS)).toEqual({});
  });

  it('flags duplicate names ignoring case, on the second source only', () => {
    const first = draft({ name: 'Docs' });
    const second = draft({ name: 'docs' });

    const errors = validateSources([first, second], LIMITS);

    // The first occurrence is left alone on purpose: marking both as duplicates makes it look like
    // there is no correct one, when what is wanted is to rename the one just added.
    expect(errors[first.key]).toBeUndefined();
    expect(errors[second.key]?.name).toContain('Two sources are both named "docs"');
  });

  it('does not treat one source as a duplicate of itself', () => {
    const only = draft({ name: 'Docs' });

    expect(validateSources([only, only], LIMITS)[only.key]?.name).toBeUndefined();
  });

  it('reports a duplicate name alongside that source\'s other errors', () => {
    const first = draft({ name: 'Docs' });
    const second = draft({ name: 'Docs', endpointUrl: 'nope' });

    const errors = validateSources([first, second], LIMITS);

    // The duplicate check must add to the per-source errors, not replace them - a source with two
    // problems that only reports one sends the user round the loop twice.
    expect(errors[second.key]?.name).toBeDefined();
    expect(errors[second.key]?.endpointUrl).toBeDefined();
  });
});

describe('validateProduct', () => {
  it('requires a name', () => {
    expect(validateProduct('  ', '').name).toBe('A name is required.');
  });

  it('enforces the length limits at their boundaries', () => {
    expect(validateProduct('x'.repeat(200), 'x'.repeat(2000))).toEqual({});
    expect(validateProduct('x'.repeat(201), '').name).toBe('The name must be 200 characters or fewer.');
    expect(validateProduct('Fine', 'x'.repeat(2001)).description).toBe(
      'The description must be 2000 characters or fewer.',
    );
  });
});
