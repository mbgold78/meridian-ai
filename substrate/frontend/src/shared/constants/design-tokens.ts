export const SEVERITY_COLORS = {
  CRITICAL: { bg: 'bg-severity-critical', text: 'text-severity-critical', badge: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200' },
  HIGH: { bg: 'bg-severity-high', text: 'text-severity-high', badge: 'bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200' },
  MEDIUM: { bg: 'bg-severity-medium', text: 'text-severity-medium', badge: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200' },
  LOW: { bg: 'bg-severity-low', text: 'text-severity-low', badge: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200' },
  INFO: { bg: 'bg-severity-info', text: 'text-severity-info', badge: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200' },
} as const;

export const CATEGORY_COLORS = {
  Technology: { bg: 'bg-category-technology', badge: 'bg-violet-100 text-violet-800 dark:bg-violet-900 dark:text-violet-200' },
  Organization: { bg: 'bg-category-organization', badge: 'bg-cyan-100 text-cyan-800 dark:bg-cyan-900 dark:text-cyan-200' },
  Policy: { bg: 'bg-category-policy', badge: 'bg-indigo-100 text-indigo-800 dark:bg-indigo-900 dark:text-indigo-200' },
  Procedure: { bg: 'bg-category-procedure', badge: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-200' },
  Regulatory: { bg: 'bg-category-regulatory', badge: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200' },
} as const;

export const STATUS_COLORS = {
  APPROVED: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
  PROPOSED: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200',
  REJECTED: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200',
  SUPERSEDED: 'bg-gray-100 text-gray-800 dark:bg-gray-900 dark:text-gray-200',
} as const;

export const COMPLEXITY_COLORS = {
  Small: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
  Medium: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
  Large: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200',
} as const;

export const WAVE_COLORS = {
  'Wave 1': '#7c3aed',
  'Wave 2': '#0891b2',
  'Wave 3': '#059669',
} as const;
