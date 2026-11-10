/**
 * The stand-in of `@kf/ui` in the module's UI tests (copied from kf-feedback, reduced to what the bundle uses): the
 * primitives as plain elements with the same props (`sdk/ui/kf-host.d.ts`) and none of the host's styling.
 */
import type { ComponentProps, HTMLAttributes } from 'react';

export function cn(...inputs: unknown[]): string {
  return inputs.filter((v) => typeof v === 'string' && v).join(' ');
}

type Variant = { variant?: string; size?: string; asChild?: boolean };

export function Button({ variant, size, asChild: _asChild, ...props }: ComponentProps<'button'> & Variant) {
  return <button data-variant={variant} data-size={size} type="button" {...props} />;
}
export function Badge({ variant, asChild: _asChild, ...props }: ComponentProps<'span'> & Variant) {
  return <span data-variant={variant} {...props} />;
}
export function Card(props: HTMLAttributes<HTMLDivElement>) {
  return <div data-ui="card" {...props} />;
}
export function CardHeader(props: HTMLAttributes<HTMLDivElement>) {
  return <div {...props} />;
}
export function CardTitle(props: HTMLAttributes<HTMLHeadingElement>) {
  return <h3 {...props} />;
}
export function CardContent(props: HTMLAttributes<HTMLDivElement>) {
  return <div {...props} />;
}
export function CardFooter(props: HTMLAttributes<HTMLDivElement>) {
  return <div {...props} />;
}
