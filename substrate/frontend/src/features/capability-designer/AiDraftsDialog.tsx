import { useEffect } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Sparkle, WarningCircle, FileText } from '@phosphor-icons/react';
import { generateAi, type DraftEntity, type GenerateResponse } from '@/api/endpoints';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { useTerminology } from '@/app/theme';

interface AiDraftsDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  domainId: string;
}

/**
 * "Draft from Universe" surface. Calls POST /api/v1/ai/generative with
 * {@code entityType: "capability"} and renders the returned drafts
 * read-only. The commit flow (turn a DraftEntity into a persisted
 * Capability) is the engine.diagnostic rebuild — port map §2.3 / S2.
 *
 * <p>Empty-result handling: when {@code drafts == []} and the response
 * carries an {@code explanation}, render the explanation prominently so
 * the user sees why nothing came back (empty Universe, all candidates
 * filtered by P6 grounding, etc.) rather than a blank panel.
 */
export function AiDraftsDialog({ open, onOpenChange, domainId }: AiDraftsDialogProps) {
  const { t } = useTerminology();
  const label = t('capability', 'singular');

  const generate = useMutation({
    mutationFn: () =>
      generateAi({
        domainId,
        surface: 'CAPABILITY_DESIGNER',
        entityType: 'capability',
        readerIds: [],
      }),
  });

  const response: GenerateResponse | null = generate.data?.data ?? null;

  // Auto-fire the generate call once per open-session so the user sees
  // results without clicking a second button inside the dialog. Reset on
  // close so the next open refires a fresh fetch.
  useEffect(() => {
    if (open) {
      generate.mutate();
    } else {
      generate.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Sparkle size={20} className="text-primary" aria-hidden />
            {`AI drafts from Universe`}
          </DialogTitle>
          <DialogDescription>
            {`${label} proposals grounded in the Universe of Inputs. Read-only preview — accepting drafts lands in a later phase.`}
          </DialogDescription>
        </DialogHeader>

        <Separator />

        <ScrollArea className="max-h-[60vh]">
          <div className="py-2">
            {generate.isPending && (
              <p className="text-sm text-muted-foreground px-1 py-8 text-center">
                Generating…
              </p>
            )}

            {generate.isError && (
              <div className="flex items-start gap-2 rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                <WarningCircle size={16} className="mt-0.5 shrink-0" aria-hidden />
                <div>
                  <p className="font-medium">Request failed</p>
                  <p className="text-xs opacity-90">
                    {generate.error instanceof Error ? generate.error.message : 'Unknown error'}
                  </p>
                </div>
              </div>
            )}

            {response && response.drafts.length === 0 && (
              <EmptyDraftsNote
                explanation={response.explanation}
                filteredCount={response.filteredCount}
                label={label}
              />
            )}

            {response && response.drafts.length > 0 && (
              <ul className="space-y-3">
                {response.drafts.map((d) => (
                  <DraftCard key={d.assertionId} draft={d} />
                ))}
              </ul>
            )}
          </div>
        </ScrollArea>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function DraftCard({ draft }: { draft: DraftEntity }) {
  const fields = draft.proposedFields;
  const displayName = typeof fields.displayName === 'string'
    ? fields.displayName
    : typeof fields.name === 'string' ? fields.name : '(unnamed draft)';
  const description = typeof fields.description === 'string'
    ? fields.description
    : null;
  return (
    <li className="rounded-md border border-border bg-card p-3 text-card-foreground">
      <div className="flex items-start gap-2">
        <FileText size={16} className="mt-0.5 text-muted-foreground shrink-0" aria-hidden />
        <div className="flex-1 min-w-0">
          <p className="font-medium text-sm">{displayName}</p>
          {description && (
            <p className="mt-1 text-sm text-muted-foreground">{description}</p>
          )}
          <div className="mt-2 flex flex-wrap gap-2 text-xs text-muted-foreground">
            <span>
              {draft.evidenceIds.length === 1
                ? '1 evidence source'
                : `${draft.evidenceIds.length} evidence sources`}
            </span>
            <span>·</span>
            <span className="font-mono">assertion {draft.assertionId.slice(0, 8)}…</span>
          </div>
        </div>
      </div>
    </li>
  );
}

function EmptyDraftsNote({
  explanation,
  filteredCount,
  label,
}: {
  explanation: string | null;
  filteredCount: number;
  label: string;
}) {
  return (
    <div className="rounded-md border border-border bg-muted p-4 text-sm">
      <p className="font-medium">
        {`No ${label.toLowerCase()} drafts returned.`}
      </p>
      {/* The backend's explanation, when present, already conveys the
          filter reason — don't duplicate it. When absent, fall back to
          the raw filteredCount signal. */}
      {explanation ? (
        <p className="mt-1 text-muted-foreground">{explanation}</p>
      ) : filteredCount > 0 ? (
        <p className="mt-1 text-muted-foreground">
          {`${filteredCount} candidate${filteredCount === 1 ? '' : 's'} filtered.`}
        </p>
      ) : null}
    </div>
  );
}
