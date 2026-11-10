/**
 * A QR code drawn by React from the matrix of `qrcode-generator` (in the bundle): one `<path>` of the dark modules,
 * no markup string, no `dangerouslySetInnerHTML`. `data-qr-value` carries the encoded text (the tests read it).
 */
import { useMemo } from 'react';
import qrcode from 'qrcode-generator';

const MARGIN = 2;

export function qrPath(value: string): { size: number; d: string } {
  const qr = qrcode(0, 'M');
  qr.addData(value);
  qr.make();
  const count = qr.getModuleCount();
  let d = '';
  for (let row = 0; row < count; row++) {
    for (let col = 0; col < count; col++) {
      if (qr.isDark(row, col)) d += `M${col + MARGIN} ${row + MARGIN}h1v1h-1z`;
    }
  }
  return { size: count + 2 * MARGIN, d };
}

export function QrCode({ value, label, className }: { value: string; label: string; className?: string }) {
  const { size, d } = useMemo(() => qrPath(value), [value]);
  return (
    <svg
      role="img"
      aria-label={label}
      data-qr-value={value}
      viewBox={`0 0 ${size} ${size}`}
      shapeRendering="crispEdges"
      className={className}
    >
      <title>{label}</title>
      <rect width={size} height={size} fill="#ffffff" />
      <path d={d} fill="#000000" />
    </svg>
  );
}
