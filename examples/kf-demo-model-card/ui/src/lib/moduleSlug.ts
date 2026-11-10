/**
 * The slug the bundle runs under, given by the host (copied from kf-aas-r3, plan 222-15 Task 3): every
 * contribution receives `module.slug` in its props (`KfReactSurfaceProps.module`), and every export of surfaces.tsx
 * fixes it here before rendering its children. The same bundle thus runs as `kf-demo-model-card` and as its dev build
 * `kf-demo-model-card-dev` (served from `/m/kf-demo-model-card-dev/ui/surfaces.mjs`, a separate ES module, so the two never share this
 * value): the module's routes (`mod`), its texts (`tr`) and its links read it.
 *
 * Without a slug from the host, `moduleSlug()` throws: a call to the module's routes is an error, never a call to the
 * base's slug. The schema names, the cosmetic DOM ids and the texts' keys are contracts of the code and do not follow it.
 */

/** The platform's slug (`ModuleSlug.PATTERN`). */
const SLUG = /^[a-z0-9][a-z0-9-]{1,40}$/;

let current: string | null = null;

/** Fixes the slug given by the host (`props.module.slug`). */
export function setModuleSlug(slug: string): void {
  if (typeof slug !== 'string' || !SLUG.test(slug)) {
    throw new Error(`KF Demo: ${JSON.stringify(slug)} is not a module slug`);
  }
  current = slug;
}

/** The slug given by the host; throws when none was given yet. */
export function moduleSlug(): string {
  if (current === null) {
    throw new Error('KF Demo: the host gave no module slug (props.module.slug); the module routes cannot be addressed');
  }
  return current;
}

/** Forgets the slug (the tests' teardown). */
export function forgetModuleSlug(): void {
  current = null;
}

/** A page of the module in the host: `/m/<slug>/<page>`. */
export function modulePage(page: string): string {
  return `/m/${moduleSlug()}/${page.replace(/^\/+/, '')}`;
}

/** A section of the module in the host's setup: `/setup/m/<slug>/<section>`. */
export function moduleSetupSection(section: string): string {
  return `/setup/m/${moduleSlug()}/${section.replace(/^\/+/, '')}`;
}
