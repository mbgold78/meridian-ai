import { apiClient } from '../client';
import type { ApiResponse } from '@/shared/types/api';
import type { BrandingConfig } from '@/types/branding';

/**
 * Resolve the active BrandingConfig for a domain. Returns the persisted
 * ACTIVE row if one exists, or a transient default BrandingConfig otherwise
 * — the backend never persists a default as a side-effect of a read.
 */
export async function getActiveBrandingConfig(
  domainId: string,
): Promise<ApiResponse<BrandingConfig>> {
  const { data } = await apiClient.get<ApiResponse<BrandingConfig>>(
    '/branding/configs/active',
    { params: { domainId } },
  );
  return data;
}
