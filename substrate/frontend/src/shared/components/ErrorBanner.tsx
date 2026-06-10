import { cn } from '@/lib/utils';

interface ErrorBannerProps {
  message: string;
  onRetry?: () => void;
  className?: string;
}

export function ErrorBanner({ message, onRetry, className }: ErrorBannerProps) {
  return (
    <div className={cn('rounded-lg border border-red-200 bg-red-50 p-4 dark:border-red-800 dark:bg-red-950', className)}>
      <div className="flex items-center justify-between">
        <p className="text-sm text-red-800 dark:text-red-200">{message}</p>
        {onRetry && (
          <button
            onClick={onRetry}
            className="ml-4 rounded-md bg-red-100 px-3 py-1 text-xs font-medium text-red-800 hover:bg-red-200 dark:bg-red-900 dark:text-red-200 dark:hover:bg-red-800"
          >
            Retry
          </button>
        )}
      </div>
    </div>
  );
}
