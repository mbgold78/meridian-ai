import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Button } from '@/components/ui/button';
import { Badge, Card, CardHeader, CardTitle, CardContent, LoadingSpinner, SeverityBar } from '@/shared/components';
import { cn } from '@/lib/utils';

/**
 * A3 shell smoke test — proves the scaffold wiring (vitest + jsdom + setup.ts,
 * @ alias resolution, shadcn primitives, shared components, cn consolidation)
 * before any product surface exists. Real behavioral suites land with C7+.
 */
describe('A3 frontend shell', () => {
  it('cn merges tailwind classes with later-wins semantics', () => {
    expect(cn('p-2', 'p-4')).toBe('p-4');
    expect(cn('text-sm', false && 'hidden', 'font-medium')).toBe('text-sm font-medium');
  });

  it('renders the shadcn Button with token-backed variant classes', () => {
    render(<Button variant="destructive">Delete</Button>);
    const button = screen.getByRole('button', { name: 'Delete' });
    expect(button).toBeInTheDocument();
    expect(button.className).toContain('bg-destructive');
  });

  it('renders shared components from the consolidated barrel', () => {
    render(
      <Card>
        <CardHeader>
          <CardTitle>Shell Card</CardTitle>
          <Badge variant="outline">fresh</Badge>
        </CardHeader>
        <CardContent>
          <SeverityBar severity="HIGH" className="h-4" />
          <LoadingSpinner size="sm" />
        </CardContent>
      </Card>,
    );
    expect(screen.getByText('Shell Card')).toBeInTheDocument();
    expect(screen.getByText('fresh')).toBeInTheDocument();
  });
});
