import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { apiDownload, apiFetch } from './client';
import { queryKeys } from './queryClient';
import type { ChangeReport, Page } from './types';

/** The two export formats the backend can render. */
export type ExportFormat = 'pdf' | 'docx';

/** History page size. Enough to cover a few weeks of daily runs in one request. */
export const REPORTS_PAGE_SIZE = 20;

/**
 * Lists a product's change reports, newest first.
 *
 * @param productId the product
 * @param page zero-based page number
 * @returns the query result for one page of reports
 */
export function useReports(
  productId: string | undefined,
  page = 0,
): UseQueryResult<Page<ChangeReport>> {
  return useQuery({
    queryKey: queryKeys.products.reports(productId ?? '', page),
    queryFn: () =>
      apiFetch<Page<ChangeReport>>(`/api/saas-products/${productId}/reports`, {
        query: { page, size: REPORTS_PAGE_SIZE },
      }),
    enabled: Boolean(productId),
  });
}

/**
 * Downloads one report as a PDF or Word document and hands it to the browser.
 *
 * Goes through {@link apiDownload} rather than opening the URL in a new tab, because the endpoint
 * requires the `Authorization` header — a plain `window.open` sends no header and would 401. The
 * cost is that the file arrives in memory first; these documents are a few hundred kilobytes, so
 * that is an acceptable trade for not having to accept a token in a query string.
 *
 * @param productId the product the report belongs to
 * @param reportId the report to export
 * @param format `pdf` or `docx`
 * @throws ApiError if the document could not be generated, which the caller shows as a toast
 */
export async function downloadReport(
  productId: string,
  reportId: string,
  format: ExportFormat,
): Promise<void> {
  const { blob, filename } = await apiDownload(
    `/api/saas-products/${productId}/reports/${reportId}/export`,
    { query: { format } },
  );

  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement('a');
    link.href = url;
    // The server names the file after the product and run date; this fallback only applies if the
    // Content-Disposition header was stripped by something in the middle.
    link.download = filename ?? `change-report.${format}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    // Revoked in a `finally` so a failed click cannot leak the blob for the lifetime of the document.
    URL.revokeObjectURL(url);
  }
}
