import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { sendChat } from '@/api/endpoints/chat';
import { useTheme } from '@/app/theme';
import { cn } from '@/lib/utils';

interface ToolCall {
  name: string;
}

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  toolCalls?: ToolCall[];
}

const SUGGESTIONS = [
  'What is my biggest risk?',
  'Which system should retire first?',
  'Summarize this for my board',
  'Who is my biggest key-person risk?',
];

// Tools that should trigger spotlight on findings
const FINDING_TOOLS = new Set(['find_findings', 'get_evidence_for_finding']);
const SYSTEM_TOOLS = new Set(['find_systems', 'get_system_profile']);
const OVERLAP_TOOLS = new Set(['find_overlaps']);

export interface AmbientSignal {
  kind: 'findings' | 'systems' | 'overlaps' | 'none';
  at: number;
}

/**
 * Ambient chat bar for the Living Canvas.
 *
 * Port deviations from homepoc (port-map §2.5 step 7 / seam S14):
 *  - domainId arrives as a prop from the workspace-resolved LivingCanvas
 *    instead of the hardcoded DOMAIN_ID constant.
 *  - The raw `fetch('/api/v1/chat', …)` call is replaced by the shared
 *    {@link sendChat} endpoint module (axios client + persona interceptor);
 *    the api layer owns the wire contract, surfaces never re-create clients.
 */
export function AmbientChatBar({
  domainId,
  onSignal,
}: {
  domainId: string;
  onSignal: (s: AmbientSignal) => void;
}) {
  const { config } = useTheme();
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, busy]);

  const send = useCallback(
    async (raw: string) => {
      const message = raw.trim();
      if (!message || busy) return;

      setExpanded(true);
      setBusy(true);
      setInput('');
      const userMsg: Message = { id: `u-${Date.now()}`, role: 'user', content: message };
      const holder: Message = { id: `a-${Date.now()}`, role: 'assistant', content: '' };
      setMessages((m) => [...m, userMsg, holder]);

      try {
        const env = await sendChat({ domainId, message });
        if (env.errors && env.errors.length > 0) {
          throw new Error(env.errors[0].message);
        }
        // Wire contract (backend ChatResponse record): `message` is the
        // assistant text; toolCalls are ToolCallTrace OBJECTS — map to the
        // tool NAME for the pills/spotlight (rendering the trace object as a
        // JSX child crashed React pre-fix).
        const reply = env.data?.message ?? '';
        const tools: ToolCall[] = (env.data?.toolCalls ?? []).map((t) => ({ name: t.toolName }));

        // Emit spotlight signal based on tools used
        for (const t of tools) {
          if (FINDING_TOOLS.has(t.name)) {
            onSignal({ kind: 'findings', at: Date.now() });
            break;
          } else if (OVERLAP_TOOLS.has(t.name)) {
            onSignal({ kind: 'overlaps', at: Date.now() });
            break;
          } else if (SYSTEM_TOOLS.has(t.name)) {
            onSignal({ kind: 'systems', at: Date.now() });
            break;
          }
        }

        setMessages((m) => {
          const last = m[m.length - 1];
          if (last?.role === 'assistant') {
            return [...m.slice(0, -1), { ...last, content: reply, toolCalls: tools }];
          }
          return m;
        });
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Something went wrong.';
        setMessages((m) => {
          const last = m[m.length - 1];
          if (last?.role === 'assistant' && last.content === '') {
            return [...m.slice(0, -1), { ...last, content: msg }];
          }
          return m;
        });
      } finally {
        setBusy(false);
      }
    },
    [busy, domainId, onSignal]
  );

  const handleKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void send(input);
    }
  };

  return (
    <div className="pointer-events-auto absolute bottom-4 left-1/2 z-30 w-[min(720px,calc(100vw-3rem))] -translate-x-1/2">
      {/* Conversation bubble */}
      <AnimatePresence>
        {expanded && messages.length > 0 && (
          <motion.div
            initial={{ opacity: 0, y: 12, height: 0 }}
            animate={{ opacity: 1, y: 0, height: 'auto' }}
            exit={{ opacity: 0, y: 12, height: 0 }}
            className="mb-3 overflow-hidden rounded-2xl border border-border bg-surface-raised/80 shadow-2xl backdrop-blur-xl"
          >
            <div className="max-h-[40vh] overflow-y-auto px-4 py-3">
              <div className="flex flex-col gap-3">
                {messages.map((m) => (
                  <div
                    key={m.id}
                    className={cn('flex', m.role === 'user' ? 'justify-end' : 'justify-start')}
                  >
                    <div
                      className={cn(
                        'max-w-[85%] rounded-2xl px-3 py-2 text-sm leading-relaxed',
                        m.role === 'user'
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-foreground/5 text-foreground/90 ring-1 ring-border'
                      )}
                    >
                      {m.content ? (
                        <p className="whitespace-pre-wrap">{m.content}</p>
                      ) : (
                        <span className="flex items-center gap-1 text-foreground-muted">
                          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-foreground/60 [animation-delay:-0.3s]" />
                          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-foreground/60 [animation-delay:-0.15s]" />
                          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-foreground/60" />
                        </span>
                      )}
                      {m.toolCalls && m.toolCalls.length > 0 && (
                        <div className="mt-1.5 flex flex-wrap gap-1">
                          {m.toolCalls.map((t, i) => (
                            <span
                              key={i}
                              className="rounded-full bg-foreground/10 px-1.5 py-0.5 text-[10px] font-mono text-foreground-muted"
                            >
                              {t.name}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
                <div ref={endRef} />
              </div>
            </div>
            <button
              onClick={() => setExpanded(false)}
              className="w-full border-t border-border py-1.5 text-[11px] font-medium text-foreground-subtle hover:bg-foreground/5 hover:text-foreground-muted"
            >
              Collapse ↓
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Input bar */}
      <div
        className={cn(
          'flex items-end gap-2 rounded-2xl border border-border bg-surface-raised/80 p-2 shadow-2xl backdrop-blur-xl transition-all',
          busy && 'ring-1 ring-primary/40'
        )}
      >
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary to-primary-hover text-primary-foreground shadow-lg shadow-primary/30">
          <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
          </svg>
        </div>
        <textarea
          data-testid="chat-input"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKey}
          onFocus={() => messages.length > 0 && setExpanded(true)}
          placeholder={`Ask ${config.appDisplayName} anything…`}
          rows={1}
          disabled={busy}
          className="min-h-[36px] flex-1 resize-none bg-transparent px-2 py-2 text-sm text-foreground placeholder:text-foreground-subtle outline-none"
        />
        <button
          onClick={() => void send(input)}
          disabled={busy || !input.trim()}
          className="shrink-0 rounded-xl bg-primary px-3 py-2 text-xs font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:scale-105 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:scale-100"
        >
          Ask
        </button>
      </div>

      {/* Suggestions (only when empty) */}
      {messages.length === 0 && (
        <div className="mt-2 flex flex-wrap justify-center gap-1.5">
          {SUGGESTIONS.map((s) => (
            <button
              key={s}
              onClick={() => void send(s)}
              className="rounded-full border border-border bg-surface-raised/60 px-3 py-1 text-[11px] font-medium text-foreground-muted backdrop-blur hover:bg-foreground/10 hover:text-foreground"
            >
              {s}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
