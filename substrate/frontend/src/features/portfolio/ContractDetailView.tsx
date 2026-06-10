import { Link, useParams, useSearchParams } from 'react-router-dom';
import { CaretLeft, GitBranch, Handshake } from '@phosphor-icons/react';
import { Button } from '@/components/ui/button';
import { getContract, getSubsidiary } from '@/fixtures/beacon';
import type { BeaconContract } from '@/fixtures/beacon';
import { cn } from '@/lib/utils';
import { portfolioPaths } from './paths';

/**
 * Coordination contract detail surface — the substrate's third
 * substrate-as-governance demo beat (ported from homepoc under the
 * product route namespace, chunk C9).
 *
 * Federation invitations and cross-portfolio opportunities are
 * MCP-enforced contracts (P19), not proposals. This view renders the
 * contract terms first-class: parties, commitments, monitoring, and the
 * decline triggers that scope each party's right of refusal.
 *
 * Routing: `/portfolio/contract/:contractId`. Unknown ids render a
 * fallback panel with a back link rather than crashing.
 *
 * Entry point: opportunity cards on the Operating Partner dashboard
 * (Zone 4) and the federation invitation segment in the Subsidiary
 * COO portfolio context band. The optional `?from=subsidiary:<slug>`
 * search param preserves the back-link target so a user routed in
 * from a Curasync subsidiary view returns there rather than to the
 * portfolio dashboard.
 */
export function ContractDetailView() {
  const { contractId = '' } = useParams();
  const contract = getContract(contractId);
  const [searchParams] = useSearchParams();
  const fromParam = searchParams.get('from') ?? '';

  if (!contract) return <ContractNotFound id={contractId} fromParam={fromParam} />;

  const back = resolveBackLink(fromParam);

  return (
    <div className="mx-auto max-w-[1440px]">
      <ContractBackLink back={back} />
      <ContractHeader contract={contract} />
      <div className="px-8 pb-12 pt-8">
        <ContextPanel contract={contract} />
        <PartiesSection contract={contract} />
        <EnforcementSection contract={contract} />
        <ContractFooter back={back} />
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Back link                                                          */
/* ------------------------------------------------------------------ */

interface BackLink {
  to: string;
  label: string;
}

function resolveBackLink(fromParam: string): BackLink {
  if (fromParam.startsWith('subsidiary:')) {
    const slug = fromParam.slice('subsidiary:'.length);
    if (slug && getSubsidiary(slug)) {
      return {
        to: `${portfolioPaths.subsidiary(slug)}?tab=plan`,
        label: 'Back to subsidiary plan tab',
      };
    }
  }
  return {
    to: portfolioPaths.dashboard,
    label: 'Back to portfolio dashboard',
  };
}

function ContractBackLink({ back }: { back: BackLink }) {
  return (
    <div className="px-8 pb-2 pt-6">
      <Link
        to={back.to}
        className="inline-flex items-center gap-1 text-[13px] font-medium leading-[18px] text-foreground-muted transition-colors duration-100 hover:text-foreground"
      >
        <CaretLeft size={14} weight="regular" aria-hidden />
        {back.label}
      </Link>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Header strip — eyebrow, title, sub-line, optional value            */
/* ------------------------------------------------------------------ */

const CONTRACT_TYPE_LABEL: Record<BeaconContract['type'], string> = {
  'cross-portfolio-opportunity': 'Cross-portfolio opportunity',
  'federation-invitation': 'Federation invitation',
};

function ContractHeader({ contract }: { contract: BeaconContract }) {
  const linkedNames = contract.linkedSubsidiarySlugs
    .map((slug) => getSubsidiary(slug)?.name ?? slug)
    .join(' · ');
  const proposedLabel = `Proposed ${formatContractDate(contract.proposedAt)} · Linked to ${linkedNames}`;
  return (
    <header className="border-b border-border px-8 py-8">
      <div className="flex flex-col gap-8 md:flex-row md:items-start md:justify-between">
        <div className="md:basis-3/5">
          <div className="flex items-center gap-1.5">
            <GitBranch
              size={16}
              weight="regular"
              aria-hidden
              className="text-primary"
            />
            <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-primary">
              {CONTRACT_TYPE_LABEL[contract.type]}
            </p>
          </div>
          <h1 className="mt-3 font-serif text-[36px] font-semibold leading-[40px] -tracking-[0.01em] text-foreground">
            {contract.title}
          </h1>
          <p className="mt-3 text-[13px] leading-[18px] text-foreground-muted">
            {proposedLabel}
          </p>
        </div>
        {contract.estimatedValue && (
          <aside className="rounded-md border border-border bg-surface p-5 md:basis-2/5 md:text-right">
            <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
              Estimated value
            </p>
            <p
              className="mt-2 text-[18px] font-semibold leading-[24px] text-foreground"
              style={{ fontFeatureSettings: '"tnum"' }}
            >
              {contract.estimatedValue}
            </p>
          </aside>
        )}
      </div>
    </header>
  );
}

/* ------------------------------------------------------------------ */
/*  Context panel                                                      */
/* ------------------------------------------------------------------ */

function ContextPanel({ contract }: { contract: BeaconContract }) {
  return (
    <section
      aria-label="Contract context"
      className="rounded-md border border-border bg-surface-raised p-5"
    >
      <p className="text-[14px] leading-[20px] text-foreground">
        {contract.context}
      </p>
    </section>
  );
}

/* ------------------------------------------------------------------ */
/*  Parties section                                                    */
/* ------------------------------------------------------------------ */

function PartiesSection({ contract }: { contract: BeaconContract }) {
  return (
    <section aria-label="Parties and commitments" className="mt-10">
      <header className="mb-4">
        <h2 className="text-[18px] font-semibold leading-[24px] text-foreground">
          Parties and commitments
        </h2>
        <p className="mt-1 text-sm text-foreground-muted">
          Each party's first-class obligations under the contract.
        </p>
      </header>
      <ol role="list" className="flex flex-col gap-4">
        {contract.parties.map((party, idx) => (
          <li
            key={`${party.partyLabel}-${idx}`}
            className="rounded-md border border-border bg-surface-raised p-5"
          >
            <header className="flex items-center gap-2">
              <Handshake
                size={20}
                weight="regular"
                aria-hidden
                className="text-foreground-muted"
              />
              <h3 className="text-[18px] font-semibold leading-[24px] text-foreground">
                {party.partyLabel}
              </h3>
            </header>
            <p className="mt-3 text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
              Commitments
            </p>
            <ul role="list" className="mt-2 flex flex-col gap-2">
              {party.commitments.map((commitment, cIdx) => (
                <li
                  key={cIdx}
                  className="flex gap-2 text-[14px] leading-[20px] text-foreground"
                >
                  <span aria-hidden className="text-foreground-muted">
                    —
                  </span>
                  <span>{commitment}</span>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ol>
    </section>
  );
}

/* ------------------------------------------------------------------ */
/*  Enforcement section                                                */
/* ------------------------------------------------------------------ */

function EnforcementSection({ contract }: { contract: BeaconContract }) {
  const { monitor, monitorMechanism, declineTriggers } = contract.enforcement;
  return (
    <section aria-label="Enforcement" className="mt-10">
      <header className="mb-4">
        <h2 className="text-[18px] font-semibold leading-[24px] text-foreground">
          Enforcement
        </h2>
        <p className="mt-1 text-sm text-foreground-muted">
          How the substrate monitors compliance and what triggers a Decline.
        </p>
      </header>
      <div className="flex flex-col gap-4">
        <article
          aria-label="Monitoring"
          className="rounded-md border border-border bg-surface-raised p-5"
        >
          <h3 className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            Monitoring
          </h3>
          <p className="mt-2 text-[14px] leading-[20px] text-foreground">
            {monitor}
          </p>
        </article>
        <article
          aria-label="Mechanism"
          className="rounded-md border border-border bg-surface-raised p-5"
        >
          <h3 className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            Mechanism
          </h3>
          <pre
            className="mt-2 overflow-x-auto rounded-md border border-border bg-surface p-4 font-mono text-[12px] leading-[18px] text-foreground"
            style={{ whiteSpace: 'pre-wrap' }}
          >
            {monitorMechanism}
          </pre>
        </article>
        <article
          aria-label="Decline triggers"
          className="rounded-md border border-border bg-surface-raised p-5"
        >
          <h3 className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            Decline triggers
          </h3>
          <p className="mt-1 text-[13px] leading-[18px] text-foreground-muted">
            Documented conditions under which either party may invoke Decline
            without breach.
          </p>
          <ul role="list" className="mt-3 flex flex-col gap-2">
            {declineTriggers.map((trigger, idx) => (
              <li
                key={idx}
                className="flex gap-3 rounded-md border border-l-2 border-border border-l-destructive/40 bg-surface px-3 py-2 text-[14px] leading-[20px] text-foreground"
              >
                <span>{trigger}</span>
              </li>
            ))}
          </ul>
        </article>
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ */
/*  Footer panel — affordance buttons + back link                      */
/* ------------------------------------------------------------------ */

function ContractFooter({ back }: { back: BackLink }) {
  // Both affordances render as visually styled but interaction-inert
  // buttons. The substrate makes Review and Decline first-class actions
  // for coordination contracts, but the Review/Decline product flows are
  // explicitly unbuilt (port-map §6 CONTRACT-COMMITMENT-STATES, queue
  // #28 inert pattern) — design precedes implementation. Per
  // spec-gap-protocol.md "Interactive elements", undefined click behavior
  // is not allowed. Buttons are marked `disabled` + `aria-disabled` and
  // carry a tooltip explaining the inert state. Foundation-correct color
  // treatments are preserved so the surface still communicates the
  // substrate-supports-this claim.
  return (
    <footer className="mt-10 rounded-md border border-border bg-surface p-6">
      <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
        Available actions
      </p>
      <p className="mt-1 text-[13px] leading-[18px] text-foreground-muted">
        Review and Decline are first-class affordances on every coordination
        contract. The product flows ship in a follow-up phase.
      </p>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button
          type="button"
          disabled
          aria-disabled="true"
          title="Review flow not defined in v1"
          className={cn(
            'inline-flex h-10 items-center justify-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground',
            'cursor-not-allowed opacity-60',
          )}
        >
          Review proposal
        </button>
        <button
          type="button"
          disabled
          aria-disabled="true"
          title="Decline flow not defined in v1"
          className={cn(
            'inline-flex h-10 items-center justify-center gap-2 rounded-md border border-destructive/40 bg-background px-4 py-2 text-sm font-medium text-destructive',
            'cursor-not-allowed opacity-60',
          )}
        >
          Decline
        </button>
      </div>
      <div className="mt-4">
        <Link
          to={back.to}
          className="inline-flex items-center gap-1 text-[13px] font-medium leading-[18px] text-foreground-muted transition-colors duration-100 hover:text-foreground"
        >
          <CaretLeft size={14} weight="regular" aria-hidden />
          {back.label}
        </Link>
      </div>
    </footer>
  );
}

/* ------------------------------------------------------------------ */
/*  Fallback — contract id not in fixture                              */
/* ------------------------------------------------------------------ */

function ContractNotFound({ id, fromParam }: { id: string; fromParam: string }) {
  const back = resolveBackLink(fromParam);
  return (
    <div className="mx-auto max-w-screen-md px-8 py-12">
      <div className="rounded-md border border-destructive/40 bg-destructive-surface p-8">
        <h1 className="text-[22px] font-semibold leading-[28px] text-destructive">
          Contract not found
        </h1>
        <p className="mt-2 text-[14px] leading-[20px] text-foreground-muted">
          No Beacon coordination contract matches the id "{id}".
        </p>
        <Button asChild variant="outline" size="sm" className="mt-6">
          <Link to={back.to} className="gap-1">
            <CaretLeft size={16} aria-hidden />
            {back.label}
          </Link>
        </Button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Date helper                                                        */
/* ------------------------------------------------------------------ */

const SHORT_MONTH = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
] as const;

function formatContractDate(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!m) return iso;
  const monthIdx = Number(m[2]) - 1;
  if (monthIdx < 0 || monthIdx > 11) return iso;
  return `${SHORT_MONTH[monthIdx]} ${Number(m[3])} ${m[1]}`;
}
