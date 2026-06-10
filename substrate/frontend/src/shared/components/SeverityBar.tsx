import { cn } from '@/lib/utils';
import { SEVERITY_COLORS } from '@/shared/constants/design-tokens';

interface SeverityBarProps {
  severity: string;
  className?: string;
}

export function SeverityBar({ severity, className }: SeverityBarProps) {
  const colors = SEVERITY_COLORS[severity as keyof typeof SEVERITY_COLORS] ?? SEVERITY_COLORS.INFO;
  return <div className={cn('w-1 rounded-full', colors.bg, className)} />;
}
