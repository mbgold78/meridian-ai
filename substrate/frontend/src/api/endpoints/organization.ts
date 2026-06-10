import { apiClient } from '../client';
import type { ApiResponse, OrganizationMember } from '@/shared/types/api';

export async function getOrganizationMembers(domainId?: string): Promise<ApiResponse<OrganizationMember[]>> {
  const { data } = await apiClient.get<ApiResponse<OrganizationMember[]>>('/organization/members', {
    params: domainId ? { domainId } : undefined,
  });
  return data;
}
