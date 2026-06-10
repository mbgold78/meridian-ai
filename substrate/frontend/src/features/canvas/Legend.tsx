export function Legend() {
  return (
    <div className="pointer-events-auto absolute bottom-4 left-4 z-20">
      <div className="rounded-xl border border-border bg-surface-raised/70 p-3 text-[11px] shadow-2xl backdrop-blur-xl">
        <p className="mb-2 text-[9px] font-bold uppercase tracking-widest text-foreground-subtle">
          Legend
        </p>
        <div className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-foreground-muted">
          <Row color="bg-sky-400" label="Core system" />
          <Row color="bg-teal-400" label="Ancillary" />
          <Row color="bg-violet-400" label="Vendor" />
          <Row color="bg-zinc-400" label="Legacy" />
        </div>
        <div className="mt-3 border-t border-border pt-2">
          <div className="flex items-center gap-2 text-foreground-muted">
            <span className="inline-flex h-4 min-w-[16px] items-center justify-center rounded-full bg-red-500 px-1 text-[9px] font-bold text-white">
              3
            </span>
            <span>Open findings</span>
          </div>
          <div className="mt-1.5 flex items-center gap-2 text-foreground-muted">
            <span className="rounded border border-dashed border-amber-400/60 bg-amber-500/10 px-1.5 py-0.5 text-[9px] text-amber-200">
              Manual
            </span>
            <span>Manual process</span>
          </div>
          <div className="mt-1.5 flex items-center gap-2 text-foreground-muted">
            <span className="relative h-0.5 w-8 bg-sky-400/40">
              <span className="absolute -top-[3px] left-0 h-2 w-2 rounded-full bg-sky-400 animate-[ping_2.5s_ease-in-out_infinite]" />
            </span>
            <span>Data flow</span>
          </div>
        </div>
      </div>
    </div>
  );
}

function Row({ color, label }: { color: string; label: string }) {
  return (
    <div className="flex items-center gap-2">
      <span className={`inline-block h-2 w-2 rounded-full ${color} shadow-[0_0_6px_currentColor]`} />
      <span>{label}</span>
    </div>
  );
}
