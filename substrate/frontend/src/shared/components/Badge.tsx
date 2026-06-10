import { cn } from '@/lib/utils';

interface BadgeProps {
  children: React.ReactNode;
  variant?: 'default' | 'severity' | 'status' | 'outline';
  className?: string;
}

export function Badge({ children, variant = 'default', className }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
        variant === 'default' && 'bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-gray-200',
        variant === 'outline' && 'border border-gray-300 text-gray-700 dark:border-gray-600 dark:text-gray-300',
        className
      )}
    >
      {children}
    </span>
  );
}
