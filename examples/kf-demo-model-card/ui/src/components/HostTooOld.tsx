import { hostApiVersion } from '../lib/hostGuard';
import { tr } from '../lib/texts';

/** Shown in place of a contribution when the host's API is older than the module needs. */
export function HostTooOld() {
  return (
    <p role="alert" className="text-sm text-muted-foreground">
      {tr('ui.host.too-old', { version: hostApiVersion() })}
    </p>
  );
}
