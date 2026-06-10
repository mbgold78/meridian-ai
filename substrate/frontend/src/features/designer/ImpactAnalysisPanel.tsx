import { Badge } from '@/shared/components/Badge';
import { Card, CardHeader, CardTitle, CardContent } from '@/shared/components/Card';
import { LoadingSpinner } from '@/shared/components/LoadingSpinner';
import { ErrorBanner } from '@/shared/components/ErrorBanner';
import type { ImpactAnalysisResult, SystemImpact, ProcessImpact } from '@/shared/types/api';

/**
 * Impact-level badge styling, migrated from homepoc's hardcoded red/amber/
 * green palette onto the semantic token surfaces (tokens.css), so badges
 * re-tone with the active BrandingConfig.
 */
const IMPACT_LEVEL_CLASSES: Record<string, string> = {
  HIGH: 'bg-destructive-surface text-destructive',
  MEDIUM: 'bg-warning-surface text-warning',
  LOW: 'bg-success-surface text-success',
};

function getImpactBadgeClass(level: string): string {
  return IMPACT_LEVEL_CLASSES[level.toUpperCase()] ?? 'bg-muted text-muted-foreground';
}

interface ImpactAnalysisPanelProps {
  result: ImpactAnalysisResult | null;
  isLoading: boolean;
  error: Error | null;
  onClose: () => void;
}

function SystemImpactRow({ impact }: { impact: SystemImpact }) {
  return (
    <tr className="border-b border-border">
      <td className="px-3 py-2 text-sm text-foreground">{impact.systemName}</td>
      <td className="px-3 py-2">
        <Badge className={getImpactBadgeClass(impact.impactLevel)}>{impact.impactLevel}</Badge>
      </td>
      <td className="px-3 py-2 text-sm text-muted-foreground">{impact.description}</td>
    </tr>
  );
}

function ProcessImpactRow({ impact }: { impact: ProcessImpact }) {
  return (
    <tr className="border-b border-border">
      <td className="px-3 py-2 text-sm text-foreground">{impact.processName}</td>
      <td className="px-3 py-2">
        <Badge className={getImpactBadgeClass(impact.impactLevel)}>{impact.impactLevel}</Badge>
      </td>
      <td className="px-3 py-2 text-sm text-muted-foreground">{impact.description}</td>
    </tr>
  );
}

function ImpactTable({ caption, children }: { caption: string; children: React.ReactNode }) {
  return (
    <div>
      <h4 className="mb-2 text-sm font-semibold text-foreground">{caption}</h4>
      <div className="overflow-x-auto rounded-md border border-border">
        <table className="min-w-full divide-y divide-border">
          <thead className="bg-muted">
            <tr>
              <th className="px-3 py-2 text-left text-xs font-medium uppercase text-muted-foreground">
                {caption === 'System Impacts' ? 'System' : 'Process'}
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium uppercase text-muted-foreground">
                Impact
              </th>
              <th className="px-3 py-2 text-left text-xs font-medium uppercase text-muted-foreground">
                Description
              </th>
            </tr>
          </thead>
          <tbody>{children}</tbody>
        </table>
      </div>
    </div>
  );
}

export function ImpactAnalysisPanel({ result, isLoading, error, onClose }: ImpactAnalysisPanelProps) {
  return (
    <Card className="mt-4">
      <CardHeader>
        <CardTitle>Impact Analysis</CardTitle>
        <button
          onClick={onClose}
          className="rounded-md px-2 py-1 text-sm text-muted-foreground hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          Close
        </button>
      </CardHeader>
      <CardContent>
        {isLoading && (
          <div className="flex flex-col items-center gap-3 py-8">
            <LoadingSpinner size="lg" />
            <p className="text-sm text-muted-foreground">
              Running impact analysis... LLM is evaluating system and process effects.
            </p>
          </div>
        )}

        {error && <ErrorBanner message={error.message} />}

        {result && !isLoading && (
          <div className="space-y-4">
            <div className="rounded-md bg-muted p-3 text-sm text-foreground">
              {result.summary}
            </div>

            {result.systemImpacts.length > 0 && (
              <ImpactTable caption="System Impacts">
                {result.systemImpacts.map((si) => (
                  <SystemImpactRow key={si.systemId} impact={si} />
                ))}
              </ImpactTable>
            )}

            {result.processImpacts.length > 0 && (
              <ImpactTable caption="Process Impacts">
                {result.processImpacts.map((pi) => (
                  <ProcessImpactRow key={pi.processId} impact={pi} />
                ))}
              </ImpactTable>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
