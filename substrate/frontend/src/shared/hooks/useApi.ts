import { useQuery, useMutation, type UseQueryOptions } from '@tanstack/react-query';
import type { ApiResponse } from '@/shared/types/api';

export function useApiQuery<T>(
  key: string[],
  fetcher: () => Promise<ApiResponse<T>>,
  options?: Omit<UseQueryOptions<T, Error>, 'queryKey' | 'queryFn'>
) {
  return useQuery<T, Error>({
    queryKey: key,
    queryFn: async () => {
      const response = await fetcher();
      if (response.errors && response.errors.length > 0) {
        throw new Error(response.errors[0].message);
      }
      return response.data;
    },
    ...options,
  });
}

export function useApiMutation<TInput, TOutput>(
  mutator: (input: TInput) => Promise<ApiResponse<TOutput>>
) {
  return useMutation<TOutput, Error, TInput>({
    mutationFn: async (input) => {
      const response = await mutator(input);
      if (response.errors && response.errors.length > 0) {
        throw new Error(response.errors[0].message);
      }
      return response.data;
    },
  });
}
