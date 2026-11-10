/**
 * kf-demo-model-card UI contributions (kind "react"), the tutorial's demo module. Rendered by the KF host INSIDE its
 * React tree: `react`, `react-dom`, `@kf/ui` and `@kf/host-sdk` are the host's own instances (page import map →
 * window.__KF__), so they are external in the build; `qrcode-generator` is in the bundle. Types: the pinned SDK's
 * sdk/ui/kf-host.d.ts (exported into ../.sdk by build.sh).
 *
 * One export per contribution, declared in kf-module-conf.json `surfaces[]`:
 *
 *   models.detail.overview.cards   ModelCard — the model's card in its Overview tab (protocol 1.3.1, platform 6.1)
 *   module.pages, page "cards"     ModelCardsPage — the tenant's models, paged, and the cards the user pinned
 *
 * Both fix the slug the host gives (`module.slug`) before any call: the same bundle runs as `kf-demo-model-card` and
 * as its dev build `kf-demo-model-card-dev`, and the slug is never written in the code.
 */

export { REQUIRED_HOST_API } from './lib/hostGuard';
export { ModelCard } from './ModelCard';
export { ModelCardsPage } from './ModelCardsPage';
