import { useState, useRef, useEffect } from 'react';
import { useAppDispatch, useAppSelector } from '@/app/hooks';
import { setPersonaLevel, type PersonaLevel } from './personaSlice';
import { cn } from '@/lib/utils';

interface PersonaOption {
  level: PersonaLevel;
  label: string;
  description: string;
  ready: boolean;
}

const PERSONAS: PersonaOption[] = [
  { level: 'L3_ANALYST', label: 'Architect', description: 'Technical architecture analysis', ready: true },
  { level: 'L1_EXECUTIVE', label: 'Executive', description: 'Business outcome focus', ready: true },
  { level: 'L2_DOMAIN_LEAD', label: 'Domain Lead', description: 'Domain-specific view', ready: false },
  { level: 'L4_SME', label: 'Subject Matter Expert', description: 'Deep domain expertise', ready: false },
  { level: 'L5_AUDITOR', label: 'Auditor', description: 'Compliance and control focus', ready: false },
  { level: 'L6_DATA_OWNER', label: 'Data Owner', description: 'Data governance perspective', ready: false },
];

/**
 * Persona dropdown — selects the persona level sent to the backend via the
 * X-Persona-Level header (api/client.ts request interceptor).
 *
 * DEVIATION (invisible constraint): the localStorage key stays
 * 'meridian-persona-level' because api/client.ts (chunk C7 scope) reads that
 * exact key on every request; renaming only here would silently desync the
 * UI from the wire header. Rename both together when C7's owner migrates
 * the client storage keys.
 */
export function PersonaSwitcher() {
  const dispatch = useAppDispatch();
  const currentLevel = useAppSelector((state) => state.persona.level);
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const currentPersona = PERSONAS.find((p) => p.level === currentLevel) ?? PERSONAS[0];

  // Close dropdown on outside click
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleSelect = (persona: PersonaOption) => {
    dispatch(setPersonaLevel(persona.level));
    localStorage.setItem('meridian-persona-level', persona.level);
    setIsOpen(false);
  };

  return (
    <div className="relative" ref={dropdownRef}>
      <button
        onClick={() => setIsOpen((prev) => !prev)}
        className={cn(
          'flex items-center gap-2 rounded-md border px-3 py-1.5 text-sm font-medium transition-colors',
          'border-border bg-surface text-foreground hover:bg-muted',
        )}
      >
        <svg className="h-4 w-4 text-primary" viewBox="0 0 20 20" fill="currentColor">
          <path d="M10 8a3 3 0 100-6 3 3 0 000 6zM3.465 14.493a1.23 1.23 0 00.41 1.412A9.957 9.957 0 0010 18c2.31 0 4.438-.784 6.131-2.1.43-.333.604-.903.408-1.41a7.002 7.002 0 00-13.074.003z" />
        </svg>
        {currentPersona.label}
        <svg className={cn('h-4 w-4 transition-transform', isOpen && 'rotate-180')} viewBox="0 0 20 20" fill="currentColor">
          <path fillRule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z" clipRule="evenodd" />
        </svg>
      </button>

      {isOpen && (
        <div className="absolute right-0 top-full z-50 mt-1 w-72 rounded-lg border border-border bg-popover py-1 text-popover-foreground shadow-lg">
          {PERSONAS.map((persona) => (
            <button
              key={persona.level}
              onClick={() => handleSelect(persona)}
              className={cn(
                'flex w-full items-start gap-3 px-3 py-2.5 text-left transition-colors',
                persona.level === currentLevel ? 'bg-accent' : 'hover:bg-muted',
              )}
            >
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span
                    className={cn(
                      'text-sm font-medium',
                      persona.level === currentLevel ? 'text-primary' : 'text-foreground',
                    )}
                  >
                    {persona.label}
                  </span>
                  {!persona.ready && (
                    <span className="rounded bg-warning-surface px-1.5 py-0.5 text-[10px] font-semibold uppercase text-warning-foreground">
                      Preview
                    </span>
                  )}
                </div>
                <p className="mt-0.5 text-xs text-foreground-muted">
                  {persona.description}
                </p>
              </div>
              {persona.level === currentLevel && (
                <svg className="mt-0.5 h-4 w-4 shrink-0 text-primary" viewBox="0 0 20 20" fill="currentColor">
                  <path fillRule="evenodd" d="M16.704 4.153a.75.75 0 01.143 1.052l-8 10.5a.75.75 0 01-1.127.075l-4.5-4.5a.75.75 0 011.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 011.05-.143z" clipRule="evenodd" />
                </svg>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
