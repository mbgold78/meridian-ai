import { useState, useCallback } from 'react';
import { PageHeader } from '@/shared/components/PageHeader';
import { Badge } from '@/shared/components/Badge';
import { DataTable } from '@/shared/components/DataTable';
import { LoadingSpinner } from '@/shared/components/LoadingSpinner';
import { ErrorBanner } from '@/shared/components/ErrorBanner';
import { Card, CardContent } from '@/shared/components/Card';
import { Button } from '@/components/ui/button';
import { useApiQuery, useApiMutation } from '@/shared/hooks/useApi';
import { useWorkspace } from '@/shared/hooks/useWorkspace';
import { getDecisions, createDecision, analyzeImpact } from '@/api/endpoints/decisions';
import { DecisionForm, type DecisionFormData } from './DecisionForm';
import { ImpactAnalysisPanel } from './ImpactAnalysisPanel';
import type { DesignDecision, ImpactAnalysisResult } from '@/shared/types/api';

type DecisionRow = DesignDecision & Record<string, unknown>;

/**
 * Decision status → badge styling, on the semantic token surfaces.
 * Statuses mirror the backend {@code DecisionStatus} enum
 * (PROPOSED, APPROVED, REJECTED, SUPERSEDED). Deviation from homepoc:
 * homepoc used the legacy hardcoded-palette STATUS_COLORS constant; this
 * surface is token-clean so it re-skins with the active BrandingConfig.
 */
const STATUS_BADGE_CLASSES: Record<string, string> = {
  PROPOSED: 'bg-info-surface text-info',
  APPROVED: 'bg-success-surface text-success',
  REJECTED: 'bg-destructive-surface text-destructive',
  SUPERSEDED: 'bg-muted text-muted-foreground',
};

function statusBadgeClass(status: string): string {
  return STATUS_BADGE_CLASSES[status] ?? 'bg-muted text-muted-foreground';
}

/**
 * Decision type → badge styling. Keys mirror the backend
 * {@code DecisionType} enum (homepoc's frontend listed seven type strings
 * that don't exist in that enum — see DecisionForm). Grouped onto semantic
 * token surfaces: removal-shaped decisions read destructive, improvement-
 * shaped read success, structural integration reads info, process-shape
 * changes read warning.
 */
const TYPE_BADGE_CLASSES: Record<string, string> = {
  CONSOLIDATE: 'bg-info-surface text-info',
  INTEGRATE: 'bg-info-surface text-info',
  MODERNIZE: 'bg-success-surface text-success',
  AUTOMATE_MANUAL_PROCESS: 'bg-success-surface text-success',
  FORMALIZE_MANUAL_PROCESS: 'bg-success-surface text-success',
  RETIRE: 'bg-destructive-surface text-destructive',
  ELIMINATE_MANUAL_PROCESS: 'bg-destructive-surface text-destructive',
  DECOUPLE: 'bg-warning-surface text-warning',
  ABSORB_MANUAL_PROCESS: 'bg-warning-surface text-warning',
};

function typeBadgeClass(decisionType: string): string {
  return TYPE_BADGE_CLASSES[decisionType] ?? 'bg-muted text-muted-foreground';
}

/**
 * Future State Designer page — design-decision register with creation and
 * LLM-backed impact analysis. Talks to /design/decisions and
 * /design/impact-analysis.
 *
 * Domain scope comes from {@link useWorkspace} — the explicit workspace
 * resolution policy that replaced homepoc's hardcoded DOMAIN_ID
 * (port map §2.5 step 8 / S14).
 */
export function FutureStateDesigner() {
  const [showForm, setShowForm] = useState(false);
  const [analysisDecisionId, setAnalysisDecisionId] = useState<string | null>(null);
  const [analysisResult, setAnalysisResult] = useState<ImpactAnalysisResult | null>(null);
  const [analysisError, setAnalysisError] = useState<Error | null>(null);
  const [isAnalyzing, setIsAnalyzing] = useState(false);

  const workspace = useWorkspace();
  const domainId = workspace.domainId;

  const {
    data: decisions,
    isLoading,
    error,
    refetch,
  } = useApiQuery<DesignDecision[]>(
    ['decisions', domainId ?? 'no-workspace'],
    () => getDecisions(domainId as string),
    { enabled: !!domainId },
  );

  const createMutation = useApiMutation<Partial<DesignDecision>, DesignDecision>(createDecision);

  const handleCreateDecision = useCallback(
    (formData: DecisionFormData) => {
      if (!domainId) return;
      createMutation.mutate(
        {
          // Port fix (invisible constraint): homepoc omitted domainId on
          // create, persisting decisions with domainId=null — invisible to
          // the domain-scoped GET this page reads from. The workspace domain
          // is required for the row to surface anywhere.
          domainId,
          title: formData.title,
          description: formData.description,
          decisionType: formData.decisionType,
          rationale: formData.rationale,
          affectedSystemIds: formData.affectedSystemIds,
          affectedProcessIds: formData.affectedProcessIds,
        },
        {
          onSuccess: () => {
            setShowForm(false);
            void refetch();
          },
        },
      );
    },
    [createMutation, refetch, domainId],
  );

  const handleRunImpactAnalysis = useCallback(
    (decision: DesignDecision) => {
      setAnalysisDecisionId(decision.id);
      setAnalysisResult(null);
      setAnalysisError(null);
      setIsAnalyzing(true);

      analyzeImpact({
        description: decision.description,
        domainId: decision.domainId ?? domainId ?? '',
      })
        .then((response) => {
          if (response.errors && response.errors.length > 0) {
            setAnalysisError(new Error(response.errors[0].message));
          } else {
            setAnalysisResult(response.data);
          }
        })
        .catch((err: unknown) => {
          setAnalysisError(err instanceof Error ? err : new Error('Impact analysis failed'));
        })
        .finally(() => {
          setIsAnalyzing(false);
        });
    },
    [domainId],
  );

  const closeAnalysis = useCallback(() => {
    setAnalysisDecisionId(null);
    setAnalysisResult(null);
    setAnalysisError(null);
  }, []);

  const tableData: DecisionRow[] = (decisions ?? []).map((d) => ({
    ...d,
    affectedCount: (d.affectedSystemIds?.length ?? 0) + (d.affectedProcessIds?.length ?? 0),
  }));

  const columns = [
    {
      key: 'title',
      header: 'Title',
      sortable: true,
    },
    {
      key: 'decisionType',
      header: 'Type',
      sortable: true,
      render: (item: DecisionRow) => (
        <Badge className={typeBadgeClass(item.decisionType)}>
          {item.decisionType.replace(/_/g, ' ')}
        </Badge>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortable: true,
      render: (item: DecisionRow) => (
        <Badge className={statusBadgeClass(item.status)}>{item.status}</Badge>
      ),
    },
    {
      key: 'affectedCount',
      header: 'Affected',
      sortable: true,
      render: (item: DecisionRow) => {
        // Port fix: homepoc labeled the combined system+process count
        // "N system(s)" — show each population truthfully instead.
        const systems = item.affectedSystemIds?.length ?? 0;
        const processes = item.affectedProcessIds?.length ?? 0;
        return (
          <span className="text-sm text-muted-foreground">
            {`${systems} system${systems === 1 ? '' : 's'} · ${processes} process${processes === 1 ? '' : 'es'}`}
          </span>
        );
      },
    },
    {
      key: 'createdAt',
      header: 'Date',
      sortable: true,
      render: (item: DecisionRow) => (
        <span className="text-sm text-muted-foreground">
          {new Date(item.createdAt).toLocaleDateString()}
        </span>
      ),
    },
    {
      key: '_actions',
      header: 'Actions',
      sortable: false,
      render: (item: DecisionRow) =>
        item.status === 'PROPOSED' ? (
          <Button
            variant="outline"
            size="sm"
            onClick={(e) => {
              e.stopPropagation();
              handleRunImpactAnalysis(item);
            }}
          >
            Run Impact Analysis
          </Button>
        ) : null,
    },
  ];

  if (workspace.status === 'loading' || isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (workspace.status === 'error') {
    return (
      <div className="p-6">
        <ErrorBanner message="Failed to resolve the workspace domain." />
      </div>
    );
  }

  if (workspace.status === 'empty') {
    return (
      <div className="flex h-64 items-center justify-center p-6 text-center">
        <p className="text-sm text-muted-foreground">
          No workspace yet — ingest data to create your first domain.
        </p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-6">
        <ErrorBanner message={error.message} onRetry={() => void refetch()} />
      </div>
    );
  }

  return (
    <div className="flex flex-col">
      <PageHeader
        title="Future State Designer"
        subtitle="Define and evaluate design decisions for your target architecture"
        actions={
          <Button
            variant={showForm ? 'outline' : 'default'}
            onClick={() => setShowForm((prev) => !prev)}
          >
            {showForm ? 'Cancel' : 'New Decision'}
          </Button>
        }
      />

      <div className="space-y-4 p-6">
        {showForm && (
          <Card>
            <CardContent className="pt-4">
              <DecisionForm
                onSubmit={handleCreateDecision}
                onCancel={() => setShowForm(false)}
                isSubmitting={createMutation.isPending}
              />
            </CardContent>
          </Card>
        )}

        <DataTable<DecisionRow>
          data={tableData}
          columns={columns}
          keyExtractor={(item) => item.id}
          emptyMessage="No design decisions yet. Click 'New Decision' to create one."
        />

        {analysisDecisionId && (
          <ImpactAnalysisPanel
            result={analysisResult}
            isLoading={isAnalyzing}
            error={analysisError}
            onClose={closeAnalysis}
          />
        )}
      </div>
    </div>
  );
}
