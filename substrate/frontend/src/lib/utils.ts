import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * shadcn/ui's canonical class-name helper. Merges conditional class strings
 * with Tailwind-aware deduplication so later classes override earlier ones
 * correctly (e.g. `cn("p-2", condition && "p-4")` yields `p-4`).
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
