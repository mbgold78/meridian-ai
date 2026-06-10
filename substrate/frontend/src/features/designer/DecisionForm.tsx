import { useState, type FormEvent } from 'react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

export interface DecisionFormData {
  title: string;
  description: string;
  decisionType: string;
  rationale: string;
  affectedSystemIds: string[];
  affectedProcessIds: string[];
}

interface DecisionFormProps {
  onSubmit: (data: DecisionFormData) => void;
  onCancel: () => void;
  isSubmitting: boolean;
}

/**
 * Decision type options.
 *
 * Port fix (invisible constraint): homepoc's form offered CONSOLIDATION /
 * MIGRATION / RETIREMENT / NEW_SYSTEM / PROCESS_CHANGE / INTEGRATION /
 * ORGANIZATIONAL — none of which exist in the backend {@code DecisionType}
 * enum, so every create POST would fail Jackson enum deserialization (400).
 * This list mirrors
 * {@code common/src/main/java/com/meridian/substrate/common/domain/enums/DecisionType.java}
 * exactly; keep the two in sync.
 */
const DECISION_TYPES = [
  'CONSOLIDATE',
  'RETIRE',
  'INTEGRATE',
  'DECOUPLE',
  'MODERNIZE',
  'ELIMINATE_MANUAL_PROCESS',
  'AUTOMATE_MANUAL_PROCESS',
  'FORMALIZE_MANUAL_PROCESS',
  'ABSORB_MANUAL_PROCESS',
] as const;

/** Token-backed control styling for the non-Input controls (select/textarea),
 * matching components/ui/input.tsx so the form re-skins with BrandingConfig. */
const controlClass =
  'flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50';

export function DecisionForm({ onSubmit, onCancel, isSubmitting }: DecisionFormProps) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [decisionType, setDecisionType] = useState<string>(DECISION_TYPES[0]);
  const [rationale, setRationale] = useState('');
  const [affectedSystems, setAffectedSystems] = useState('');
  const [affectedProcesses, setAffectedProcesses] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};
    if (!title.trim()) newErrors.title = 'Title is required';
    if (!description.trim()) newErrors.description = 'Description is required';
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    const systemIds = affectedSystems
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    const processIds = affectedProcesses
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);

    onSubmit({
      title: title.trim(),
      description: description.trim(),
      decisionType,
      rationale: rationale.trim(),
      affectedSystemIds: systemIds,
      affectedProcessIds: processIds,
    });
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label htmlFor="decision-title" className="mb-1 block text-sm font-medium text-foreground">
          Title <span className="text-destructive">*</span>
        </label>
        <Input
          id="decision-title"
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          className={cn(errors.title && 'border-destructive')}
          placeholder="e.g., Consolidate payment processing systems"
        />
        {errors.title && <p className="mt-1 text-xs text-destructive">{errors.title}</p>}
      </div>

      <div>
        <label htmlFor="decision-type" className="mb-1 block text-sm font-medium text-foreground">
          Decision Type
        </label>
        <select
          id="decision-type"
          value={decisionType}
          onChange={(e) => setDecisionType(e.target.value)}
          className={cn(controlClass, 'h-10')}
        >
          {DECISION_TYPES.map((dt) => (
            <option key={dt} value={dt}>
              {dt.replace(/_/g, ' ')}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor="decision-description" className="mb-1 block text-sm font-medium text-foreground">
          Description <span className="text-destructive">*</span>
        </label>
        <textarea
          id="decision-description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          rows={3}
          className={cn(controlClass, errors.description && 'border-destructive')}
          placeholder="Describe the design decision in detail..."
        />
        {errors.description && (
          <p className="mt-1 text-xs text-destructive">{errors.description}</p>
        )}
      </div>

      <div>
        <label htmlFor="decision-rationale" className="mb-1 block text-sm font-medium text-foreground">
          Rationale
        </label>
        <textarea
          id="decision-rationale"
          value={rationale}
          onChange={(e) => setRationale(e.target.value)}
          rows={2}
          className={controlClass}
          placeholder="Why is this decision being made?"
        />
      </div>

      <div>
        <label htmlFor="affected-systems" className="mb-1 block text-sm font-medium text-foreground">
          Affected Systems
        </label>
        <Input
          id="affected-systems"
          type="text"
          value={affectedSystems}
          onChange={(e) => setAffectedSystems(e.target.value)}
          placeholder="Comma-separated system IDs"
        />
        <p className="mt-1 text-xs text-muted-foreground">Separate multiple values with commas</p>
      </div>

      <div>
        <label htmlFor="affected-processes" className="mb-1 block text-sm font-medium text-foreground">
          Affected Manual Processes
        </label>
        <Input
          id="affected-processes"
          type="text"
          value={affectedProcesses}
          onChange={(e) => setAffectedProcesses(e.target.value)}
          placeholder="Comma-separated process IDs"
        />
        <p className="mt-1 text-xs text-muted-foreground">Separate multiple values with commas</p>
      </div>

      <div className="flex items-center justify-end gap-3 border-t border-border pt-4">
        <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? 'Creating...' : 'Create Decision'}
        </Button>
      </div>
    </form>
  );
}
