/**
 * The PUBLISHED types of what the KF host page gives module UI bundles (sdk-protocol.md §7).
 *
 * Three modules, one source of truth each:
 *  - `@kf/ui` and `@kf/host-sdk` — the runtime members, published on `window.__KF__.ui` / `.sdk` by
 *    the host page and re-exported through the page import map; the host keeps the runtime members and
 *    this file in step.
 *  - `@kf/surfaces` — type-only: the shapes the host's PUBLIC surfaces hand a contribution
 *    (`surfaces/surfaces.public.json` is the per-surface contract; `ModelSurfaceRow` is the model projection
 *    every model surface uses).
 *
 * This file is published as `ui/kf-host.d.ts` of the SDK. Mark `@kf/ui`
 * and `@kf/host-sdk` `external` in a module build; add this file to the module UI's tsconfig `include`.
 *
 * The `Form*` members of `@kf/ui` (v3) are typed on `react-hook-form`: a module that uses them installs
 * `react-hook-form` (^7, for its types and its `useForm()`) and marks it `external` too — the page import map resolves it
 * to the host's own copy, the one `Form*` is bound to.
 */

declare module '@kf/ui' {
  import type * as React from 'react';
  // the types of the form binding (v3); a module that uses Form* has react-hook-form installed
  import type * as RHF from 'react-hook-form';

  type ButtonVariant = 'default' | 'outline' | 'secondary' | 'ghost' | 'destructive' | 'link';
  type ButtonSize = 'default' | 'xs' | 'sm' | 'lg' | 'icon' | 'icon-xs' | 'icon-sm' | 'icon-lg';
  type BadgeVariant = 'default' | 'secondary' | 'destructive' | 'outline' | 'ghost' | 'link';

  export function Badge(props: React.ComponentProps<'span'> & { variant?: BadgeVariant; asChild?: boolean }): React.JSX.Element;
  export function badgeVariants(opts?: { variant?: BadgeVariant; className?: string }): string;
  export function Button(
    props: React.ComponentProps<'button'> & { variant?: ButtonVariant; size?: ButtonSize; asChild?: boolean },
  ): React.JSX.Element;
  export function buttonVariants(opts?: { variant?: ButtonVariant; size?: ButtonSize; className?: string }): string;
  export function Card(props: React.HTMLAttributes<HTMLDivElement>): React.JSX.Element;
  export function CardContent(props: React.HTMLAttributes<HTMLDivElement>): React.JSX.Element;
  export function CardDescription(props: React.HTMLAttributes<HTMLParagraphElement>): React.JSX.Element;
  export function CardFooter(props: React.HTMLAttributes<HTMLDivElement>): React.JSX.Element;
  export function CardHeader(props: React.HTMLAttributes<HTMLDivElement>): React.JSX.Element;
  export function CardTitle(props: React.HTMLAttributes<HTMLHeadingElement>): React.JSX.Element;
  export function Input(props: React.ComponentProps<'input'>): React.JSX.Element;
  export function Label(props: React.ComponentProps<'label'>): React.JSX.Element;
  export function Switch(props: {
    id?: string;
    checked?: boolean;
    defaultChecked?: boolean;
    onCheckedChange?: (checked: boolean) => void;
    disabled?: boolean;
    className?: string;
    'aria-label'?: string;
  }): React.JSX.Element;
  export function Textarea(props: React.ComponentProps<'textarea'>): React.JSX.Element;
  /** clsx + tailwind-merge, like the host. */
  export function cn(...inputs: unknown[]): string;

  // ---------------------------------------------------------------------------------------------------------------
  // v3 (host API 3; check `apiVersion >= 3`): the primitives the big module pages use — measured from the
  // host's AAS / utils / factory / planner / setup pages. Radix underneath, the host's theme on top; the props listed
  // are the ones a module may rely on, plus the element's own attributes.
  // ---------------------------------------------------------------------------------------------------------------

  type Children = { children?: React.ReactNode };
  type OpenState = { open?: boolean; defaultOpen?: boolean; onOpenChange?: (open: boolean) => void };
  type AsChild = { asChild?: boolean };

  export function Accordion(
    props: React.ComponentProps<'div'> &
      (
        | { type: 'single'; collapsible?: boolean; value?: string; defaultValue?: string; onValueChange?: (value: string) => void }
        | { type: 'multiple'; value?: string[]; defaultValue?: string[]; onValueChange?: (value: string[]) => void }
      ),
  ): React.JSX.Element;
  export function AccordionItem(props: React.ComponentProps<'div'> & { value: string; disabled?: boolean }): React.JSX.Element;
  export function AccordionTrigger(props: React.ComponentProps<'button'>): React.JSX.Element;
  export function AccordionContent(props: React.ComponentProps<'div'> & { forceMount?: true }): React.JSX.Element;

  export function Checkbox(
    props: Omit<React.ComponentProps<'button'>, 'value'> & {
      checked?: boolean | 'indeterminate';
      defaultChecked?: boolean | 'indeterminate';
      onCheckedChange?: (checked: boolean | 'indeterminate') => void;
      required?: boolean;
      name?: string;
      value?: string;
    },
  ): React.JSX.Element;

  export function Collapsible(props: React.ComponentProps<'div'> & OpenState & { disabled?: boolean }): React.JSX.Element;
  export function CollapsibleTrigger(props: React.ComponentProps<'button'> & AsChild): React.JSX.Element;
  export function CollapsibleContent(props: React.ComponentProps<'div'> & { forceMount?: true }): React.JSX.Element;

  /** A searchable list (cmdk): `Command` > `CommandInput` + `CommandList` > `CommandEmpty` / `CommandGroup` > `CommandItem`. */
  export function Command(
    props: React.ComponentProps<'div'> & {
      value?: string;
      onValueChange?: (value: string) => void;
      shouldFilter?: boolean;
      filter?: (value: string, search: string, keywords?: string[]) => number;
      loop?: boolean;
    },
  ): React.JSX.Element;
  export function CommandInput(
    props: Omit<React.ComponentProps<'input'>, 'value' | 'onChange'> & { value?: string; onValueChange?: (search: string) => void },
  ): React.JSX.Element;
  export function CommandList(props: React.ComponentProps<'div'>): React.JSX.Element;
  export function CommandEmpty(props: React.ComponentProps<'div'>): React.JSX.Element;
  export function CommandGroup(props: React.ComponentProps<'div'> & { heading?: React.ReactNode; value?: string }): React.JSX.Element;
  export function CommandItem(
    props: Omit<React.ComponentProps<'div'>, 'onSelect'> & {
      value?: string;
      keywords?: string[];
      disabled?: boolean;
      onSelect?: (value: string) => void;
    },
  ): React.JSX.Element;

  /** The host's confirmation dialog (title, description, confirm / cancel); `onConfirm` may return a Promise. */
  export interface ConfirmOptions {
    title?: string;
    description?: string;
    confirmLabel?: string;
    cancelLabel?: string;
    /** Typed confirmation: the confirm button stays disabled until the user types exactly this value. */
    expectedValue?: string;
    expectedValueLabel?: string;
  }
  export function ConfirmDialog(
    props: ConfirmOptions & { open: boolean; onOpenChange: (open: boolean) => void; onConfirm: () => void | Promise<void> },
  ): React.JSX.Element;
  /** `const { confirm, dialog } = useConfirm()`: render `dialog`, `await confirm({...})` resolves `true` on confirm. */
  export function useConfirm(): { confirm: (options: ConfirmOptions) => Promise<boolean>; dialog: React.ReactElement };

  export function Dialog(props: Children & OpenState & { modal?: boolean }): React.JSX.Element;
  export function DialogContent(
    props: React.ComponentProps<'div'> & { showCloseButton?: boolean; onEscapeKeyDown?: (event: KeyboardEvent) => void },
  ): React.JSX.Element;
  export function DialogHeader(props: React.ComponentProps<'div'>): React.JSX.Element;
  export function DialogFooter(props: React.ComponentProps<'div'> & { showCloseButton?: boolean }): React.JSX.Element;
  export function DialogTitle(props: React.ComponentProps<'h2'>): React.JSX.Element;
  export function DialogDescription(props: React.ComponentProps<'p'>): React.JSX.Element;

  export function Popover(props: Children & OpenState & { modal?: boolean }): React.JSX.Element;
  export function PopoverTrigger(props: React.ComponentProps<'button'> & AsChild): React.JSX.Element;
  export function PopoverContent(
    props: React.ComponentProps<'div'> & {
      align?: 'start' | 'center' | 'end';
      side?: 'top' | 'right' | 'bottom' | 'left';
      sideOffset?: number;
    },
  ): React.JSX.Element;

  export function Select(
    props: Children & {
      value?: string;
      defaultValue?: string;
      onValueChange?: (value: string) => void;
      open?: boolean;
      onOpenChange?: (open: boolean) => void;
      disabled?: boolean;
      required?: boolean;
      name?: string;
    },
  ): React.JSX.Element;
  export function SelectTrigger(props: React.ComponentProps<'button'> & { size?: 'sm' | 'default' }): React.JSX.Element;
  export function SelectValue(props: React.ComponentProps<'span'> & { placeholder?: React.ReactNode }): React.JSX.Element;
  export function SelectContent(props: React.ComponentProps<'div'> & { position?: 'item-aligned' | 'popper' }): React.JSX.Element;
  export function SelectItem(props: React.ComponentProps<'div'> & { value: string; disabled?: boolean }): React.JSX.Element;

  export function Sheet(props: Children & OpenState & { modal?: boolean }): React.JSX.Element;
  export function SheetContent(
    props: React.ComponentProps<'div'> & { side?: 'top' | 'right' | 'bottom' | 'left'; showCloseButton?: boolean },
  ): React.JSX.Element;
  export function SheetHeader(props: React.ComponentProps<'div'>): React.JSX.Element;
  export function SheetFooter(props: React.ComponentProps<'div'>): React.JSX.Element;
  export function SheetTitle(props: React.ComponentProps<'h2'>): React.JSX.Element;
  export function SheetDescription(props: React.ComponentProps<'p'>): React.JSX.Element;

  export function Skeleton(props: React.ComponentProps<'div'>): React.JSX.Element;

  export function Tabs(
    props: React.ComponentProps<'div'> & {
      value?: string;
      defaultValue?: string;
      onValueChange?: (value: string) => void;
      orientation?: 'horizontal' | 'vertical';
    },
  ): React.JSX.Element;
  export function TabsList(props: React.ComponentProps<'div'>): React.JSX.Element;
  export function TabsTrigger(props: React.ComponentProps<'button'> & { value: string }): React.JSX.Element;
  export function TabsContent(props: React.ComponentProps<'div'> & { value: string; forceMount?: true }): React.JSX.Element;

  // ---------------------------------------------------------------------------------------------------------------
  // v3, the form binding: the host's shadcn `form`, bound to the host's
  // react-hook-form. Build with `react-hook-form` external and take `useForm` from it: the import map gives the host's
  // copy, so `<Form {...form}>` and the host's fields share one instance. A second copy in the bundle breaks the fields.
  // ---------------------------------------------------------------------------------------------------------------

  /** `<Form {...useForm()}>`: react-hook-form's `FormProvider`. */
  export function Form<TFieldValues extends RHF.FieldValues, TContext = unknown, TTransformedValues = TFieldValues>(
    props: RHF.FormProviderProps<TFieldValues, TContext, TTransformedValues>,
  ): React.JSX.Element;
  /** One field: react-hook-form's `Controller`, plus the context `FormItem` / `FormLabel` / `FormControl` read. */
  export function FormField<
    TFieldValues extends RHF.FieldValues = RHF.FieldValues,
    TName extends RHF.FieldPath<TFieldValues> = RHF.FieldPath<TFieldValues>,
  >(props: RHF.ControllerProps<TFieldValues, TName>): React.JSX.Element;
  export function FormItem(props: React.ComponentProps<'div'>): React.JSX.Element;
  /** The field's label; turns to the destructive colour while the field has an error. */
  export function FormLabel(props: React.ComponentProps<'label'>): React.JSX.Element;
  /** Wraps the input (Radix `Slot`): sets its `id`, `aria-describedby` and `aria-invalid`. */
  export function FormControl(props: React.HTMLAttributes<HTMLElement> & { children?: React.ReactNode }): React.JSX.Element;
  export function FormDescription(props: React.ComponentProps<'p'>): React.JSX.Element;
  /** The field's error message (or `children` while there is none); renders nothing when both are empty. */
  export function FormMessage(props: React.ComponentProps<'p'>): React.JSX.Element | null;
  /** Inside a `FormField` + `FormItem`: the ids the parts share and the field's state (`error`, `invalid`, …). */
  export function useFormField(): ReturnType<RHF.UseFormGetFieldState<RHF.FieldValues>> & {
    id: string;
    name: string;
    formItemId: string;
    formDescriptionId: string;
    formMessageId: string;
  };

  /** A tooltip; the host wraps each one in its own provider, a module needs none. */
  export function Tooltip(props: Children & OpenState & { delayDuration?: number }): React.JSX.Element;
  export function TooltipTrigger(props: React.ComponentProps<'button'> & AsChild): React.JSX.Element;
  export function TooltipContent(
    props: React.ComponentProps<'div'> & { side?: 'top' | 'right' | 'bottom' | 'left'; sideOffset?: number },
  ): React.JSX.Element;
}

declare module '@kf/host-sdk' {
  /**
   * 2 (`locale`, `onLocaleChange`, `t`); a module that needs them checks `apiVersion >= 2`.
   * 3 (the `@kf/ui` primitives of the big pages: dialog, sheet, tabs, select, …, and — — the form binding `Form*` with `react-hook-form` in the import map); a module that uses one checks
   * `apiVersion >= 3`.
   * 4 (protocol 1.2): `moduleUrl` returns the portal's route space when the user is in the portal
   * (see {@link moduleUrl}); a module that calls its API from the portal checks `apiVersion >= 4`. Nothing else changed.
   * 5 (protocol 2.0, sdk-protocol.md §7.12): the slots of a module — {@link Slot},
   * {@link useSlotContributions}, {@link onSlotEvent}, {@link emitSlotEvent} — and {@link useLiveField};
   * a module that uses them checks `apiVersion >= 5`.
   */
  export const apiVersion: number;
  /** React version of the host page. */
  export const reactVersion: string;
  /**
   * Same-origin JSON fetch to the platform API through the host's proxy (the caller's own token, never one the
   * module chooses). A non-2xx answer is thrown as {@link ApiError}; `204` resolves `undefined`.
   */
  export function http<T>(path: string, init?: RequestInit): Promise<T>;
  /** RFC 9457 problem details, as a module UI sees a failed platform call. */
  export class ApiError extends Error {
    readonly status: number;
    /** The parsed problem body, or `null` when the body was not JSON. */
    readonly problem: unknown;
  }
  /**
   * `moduleUrl('api-test', 'health')` → `/api/proxy/m/api-test/health` — the module's own API, via the host.
   *
   * Since host API 4, when the user's active persona is the portal's (a customer-only account, or the
   * dual account with CUSTOMER chosen — the rule of `ctx.inPortal` on `app.overlay`), the same call returns the
   * portal's route: `moduleUrl('kf-feedback', '/tickets')` → `/api/proxy/api/customer/v1/m/kf-feedback/tickets`. Only
   * the routes the manifest declares `audience: "member"`, `portal: true` answer there (and the UI assets, as before);
   * any other route is a 404 in the portal.
   */
  export function moduleUrl(module: string, path?: string): string;
  /** The language the user chose in the host (`en`, `ro`, …). */
  export function locale(): string;
  /** Called with the new language once its texts have arrived; returns the unsubscribe function. */
  export function onLocaleChange(listener: (locale: string) => void): () => void;
  /**
   * The module's own text for `key` in the current language: the user's language, else `en` (server-side),
   * else `options.fallback`, else the slug. `{name}` placeholders are filled from `options.params`. Plain text.
   */
  export function t(
    module: string,
    key: string,
    options?: { params?: Record<string, string | number>; fallback?: string },
  ): string;

  /** A slot as the page module's approved manifest declares it (`capabilities.slots[]`, §7.12). */
  export interface SlotDeclaration {
    key: string;
    kind: 'A' | 'B' | 'D' | 'R';
    type: string;
    visibility: 'internal' | 'public';
    publicMembers?: string[];
    actions?: string[];
    layerKinds?: string[];
    events?: string[];
    commands?: string[];
    dataSchema?: string;
  }
  /** What {@link useSlotContributions} returns. Everything is empty when the slot may not be mounted here. */
  export interface SlotContributions {
    /** False for a key of another module, or one the page module's approved manifest does not declare. */
    mountable: boolean;
    declaration: SlotDeclaration | null;
    /** Kind A: the contributions with code (rendered by {@link Slot}). */
    components: ReadonlyArray<{ module: string; slug: string; kind: 'react' | 'wc'; key: string | null }>;
    /** Kind B: one entry per layer, with the data the host fetched from the contributor's `source`. */
    layers: ReadonlyArray<{ module: string; layer: string; data: unknown }>;
    /** Kind R: the contributors' JSON objects; validate them against the slot's `dataSchema`. */
    resources: ReadonlyArray<{ module: string; data: Record<string, unknown> }>;
  }
  /**
   * Host API 5: mounts a slot `m.{slug}.{key}` of the module whose page renders — only that module's own keys,
   * declared in its approved manifest (the host knows the page's module; any other key renders nothing). Kind A
   * contributions render in the page, each in its own error boundary, with `ctx` cut to the slot's `publicMembers`
   * and `actions` to its `actions`; kinds B and R come back through `children`.
   *
   * Kind A renders by the slot's `type`: `extension` a framed list, `action` the same without the
   * frame, `tab` as the host's tabs — mount it twice inside YOUR Tabs root (`@kf/ui`), once with
   * `part="trigger"` and once with `part="panel"` (+ `activeTab`, `keepMounted`); a `tab` slot without `part`
   * renders nothing. The contributions render in a null page context: a contribution cannot mount any slot.
   */
  export function Slot<Ctx = unknown>(props: {
    slug: string;
    ctx?: Ctx;
    actions?: Record<string, (...args: never[]) => unknown>;
    /** `tab` slots only: which half to mount. */
    part?: 'trigger' | 'panel';
    /** `tab` panels: the active tab value, `{module}.{key}`. */
    activeTab?: string;
    /** `tab` panels: keep a panel mounted after its first activation (default true). */
    keepMounted?: boolean;
    children?: (data: Pick<SlotContributions, 'layers' | 'resources'>) => unknown;
  }): unknown;
  /** Host API 5: the contributions served on a slot of the page's module (a React hook). */
  export function useSlotContributions(slug: string): SlotContributions;
  /**
   * The `module` a contribution receives (`props.module`), bound to the calling module by the host.
   * Since it carries the bus of the kind-D slots: {@link SurfaceModule.onSlotEvent} /
   * {@link SurfaceModule.emitSlotEvent}.
   */
  export interface SurfaceModule {
    slug: string;
    name: string;
    /** {@link moduleUrl} bound to this module. */
    moduleUrl: (path?: string) => string;
    /** {@link http} bound to this module (sends `X-KF-Calling-Module`). */
    http: <T>(path: string, init?: RequestInit) => Promise<T>;
    /**
     * Host API 5 (kind D, the bus in the page), as THIS module: listens to `name` on the slot `slug`; returns the
     * unsubscribe function. Allowed only on a declared kind-D slot, for a name in its `events` / `commands`: the slot
     * owner listens to `commands`, a module in the slot's `users` listens to `events`. Anything else is refused with
     * one warning (the text does not tell an internal slot from a missing one) and the returned function is inert.
     * The right is re-checked at every delivery. The payload is a structured clone; functions are refused.
     */
    onSlotEvent: (slug: string, name: string, listener: (payload: unknown) => void) => () => void;
    /**
     * Host API 5 (kind D), as THIS module: delivers a clone of `payload` to the listeners of `name` on `slug`; the
     * slot owner emits `events`, a module in `users` emits `commands`. Returns how many got it (0 when refused).
     */
    emitSlotEvent: (slug: string, name: string, payload?: unknown) => number;
  }
  /**
   * Always refused (no caller to bind): warns and returns an inert function. Use
   * `props.module.onSlotEvent` ({@link SurfaceModule.onSlotEvent}). The key stays for host API 5 parity.
   */
  export function onSlotEvent(slug: string, name: string, listener: (payload: unknown) => void): () => void;
  /** Always refused (returns 0): use `props.module.emitSlotEvent` ({@link SurfaceModule.emitSlotEvent}). */
  export function emitSlotEvent(slug: string, name: string, payload?: unknown): number;
  /**
   * Host API 5: the live edit of one field of your surface (a React hook, call it during the render of
   * your contribution). The field is shared as `m.<your slug>.<field>` in the page's collaboration room (the order's,
   * the model's): `setValue` updates it locally and sends it to the other editors (debounced by the host), `focus` /
   * `blur` announce the editing, `peer` / `isLockedByPeer` show a colleague editing it (an advisory lock: never
   * disable the input). Outside a room it is a local field: `peer` null, never locked. `field` matches
   * `^[a-z][a-z0-9-]{0,63}$`; the slug is the host's, never an argument. `options` is reserved (none is read yet).
   * Throws when called outside a module surface or with a `field` outside the grammar.
   */
  export function useLiveField(
    field: string,
    initial: string,
    options?: Record<string, unknown>,
  ): {
    value: string;
    setValue: (value: string) => void;
    focus: () => void;
    blur: () => void;
    peer: { userId: string; displayName?: string } | null;
    isLockedByPeer: boolean;
  };
}

declare module '@kf/surfaces' {
  /**
   * The model as every public model surface hands it to a contribution: `row` in
   * `models.list.columns` (cell) and `models.list.card.after`, `model` in `models.detail.tabs` (panel) and
   * `models.form.edit.fields.after`. Exactly these seven keys, always present; `extensions` holds ONLY the
   * calling module's own values, keyed by its slug: `row.extensions['my-module'].myField`.
   *
   * Next to `model`, the `models.detail.tabs` panel gets `version: { id: number; name: string | null } | null`
   * (protocol 1.3 §7.3): the version the page has selected — the id and the name only — or `null` while
   * none is selected. A new selection re-renders the mounted panel with the new `ctx` (no event; the module never
   * reads the address). A host before 1.3 sends no `version` key: test `'version' in ctx`.
   *
   * `models.detail.overview.cards` (protocol 1.3.1, plan) — the module cards in the Overview
   * tab of the model page, on staff and on the portal — gets `{ model, version, href }`: `model` is this projection,
   * `version` follows the same rule as above, and `href: string` is the path of the model page for that version on the
   * face the user is on, built by the host (`/models/{id}?version={vid}` on staff, `/customer/models/{id}?version={vid}`
   * on the portal, without `version` when it is `null`); prefix `location.origin` for an absolute link (a QR code).
   * Its actions are `navigate(href)` and `toast(message, options?)`. A host before 1.3.1 refuses a contribution on
   * this slug at registration (`422 unknown surface`).
   */
  export interface ModelSurfaceRow {
    id: number | null;
    name: string | null;
    styleNumber: string | null;
    status: 'DRAFT' | 'SAMPLING' | 'PRODUCTION' | 'ARCHIVED' | null;
    createdAt: string | null;
    updatedAt: string | null;
    extensions: Record<string, Record<string, unknown>>;
  }

  /**
   * What `app.overlay` hands a contribution (protocol 1.2): the current path, whether the user is in the
   * customer portal (the portal rule, never the URL) and the commit of the host's frontend build (`null` without one).
   * The surface's one action is `toast`. Mounted in the staff shell, the portal and Setup; the contribution positions
   * itself in a fixed host layer above the page and below the host's dialogs.
   */
  export interface AppOverlayCtx {
    route: string;
    inPortal: boolean;
    appVersion: string | null;
  }
}
