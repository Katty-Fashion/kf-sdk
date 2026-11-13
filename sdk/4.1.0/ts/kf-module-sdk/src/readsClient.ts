// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ReadsClient.java. Do not edit by hand: fix Java, then re-derive.
import { ErrorClass } from './errorClass.js';
import { KfException } from './kfException.js';
import type { KfModule } from './kfModule.js';
import { Manifest } from './manifest.js';
import { local, PlatformChannel, segment, queryValue } from './platformChannel.js';
import { PlatformProblem } from './platformProblem.js';
import { ReadPage } from './readPage.js';
import type { RequestContext } from './requestContext.js';

const PORT = new RegExp(Manifest.PORT_ID);

/**
 * The module's reads of platform objects: capability `reads` in `kf-module-conf.json` (`[{"port": "model.v1"}]`),
 * protocol 1.1 §15.1 (the ports decided on 2026-10-21 in `docs/modules/specifications/10-citirile.md`).
 * Obtained from `KfModule.reads()`.
 *
 *   const page = await kf.reads().list(ctx, 'order.v1', { modelVersionId: '17' }, 0, 50);
 *   const version = await kf.reads().get(ctx, 'model-version.v1', '17', ['grading']);
 *
 * - {@link list}: `GET …/reads/{port}?<filters>&page=<n>&size=<n>` → {@link ReadPage}; `page` from 0, `size`
 *   1–{@link MAX_SIZE} (default {@link DEFAULT_SIZE}); the filters in the order given; the list filter `ids` takes at
 *   most {@link MAX_IDS} values, comma-separated;
 * - {@link get}: `GET …/reads/{port}/{id}[?include=a,b]` → the object, or `null` when the platform answers `404` (not
 *   visible in the context's tenant).
 *
 * Both calls carry the module token, the context and its proof, unchanged (`PlatformChannel`); the platform reads in
 * the context's tenant, with the right of the port held by the context's user. Refused locally, `permanent`, nothing
 * sent: a port outside the grammar (`Manifest.PORT_ID`), a port the module's manifest does not declare (when
 * the manifest is known), `page < 0`, `size` outside 1–{@link MAX_SIZE}, more than {@link MAX_IDS} values in `ids`, a
 * filter or include name outside `[a-z][A-Za-z0-9]{0,40}` (or a reserved name), a blank id, a context not verified or without proof, and the two rules
 * of the system context: it reads *only objects* and *only the base fields* (no `include`). The one exception
 * (protocol 1.3): the platform's own context (`system` via `platform`) lists the ports of
 * {@link PLATFORM_LISTS} -- `tenant.v1`, without `ids`. The platform's
 * refusals are `PlatformRefusal`s (`403` with `insufficient-rights`, `capability-not-declared` or `module-not-live`;
 * `401` `invalid-context`; `400` on the filters); a `503` is a transient {@link KfException}.
 */
export class ReadsClient {
  /** The default page size of the protocol. */
  static readonly DEFAULT_SIZE = 20;
  /** The largest page the protocol serves. */
  static readonly MAX_SIZE = 200;
  /** The most values the list filter `ids` takes. */
  static readonly MAX_IDS = 200;
  /** The list filter by ids, its values comma-separated. */
  static readonly IDS = 'ids';
  /** A filter name or an `include` name. */
  static readonly NAME = /^[a-z][A-Za-z0-9]{0,40}$/;
  /** Query names the client writes itself. */
  static readonly RESERVED: readonly string[] = Object.freeze(['page', 'size', 'include']);
  /**
   * The ports the platform's own context (`system` via `platform`) may list (protocol 1.3 §15.1):
   * `tenant.v1`, every tenant with `deletedAt`. Any other list from a system context is refused locally.
   */
  static readonly PLATFORM_LISTS: readonly string[] = Object.freeze(['tenant.v1']);

  private readonly channel: PlatformChannel;

  /** @internal */
  constructor(kf: KfModule) {
    this.channel = new PlatformChannel(kf, 'reads');
  }

  /**
   * One page of the port's list in the tenant of `context` (Java's two `list` overloads: without `page` and `size`,
   * page 0 with the default size).
   *
   * @param filters the port's filters (name → value, sent in this order); a list filter such as `ids` takes its values
   *                comma-separated, `ids` at most {@link MAX_IDS} of them; empty or null for none
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`, `404`)
   */
  async list(context: RequestContext, port: string, filters: Record<string, string> | null | undefined,
    page = 0, size = ReadsClient.DEFAULT_SIZE): Promise<ReadPage> {
    const errors: string[] = [];
    checkPort(errors, port);
    if (page < 0) errors.push('page must be at least 0');
    if (size < 1 || size > ReadsClient.MAX_SIZE) errors.push(`size must be between 1 and ${ReadsClient.MAX_SIZE}`);
    const given = Object.entries(filters ?? {});
    for (const [name, value] of given) {
      if (name == null || !ReadsClient.NAME.test(name)) errors.push(`filter ${name} must match [a-z][A-Za-z0-9]{0,40}`);
      else if (ReadsClient.RESERVED.includes(name)) errors.push(`filter ${name} is reserved`);
      else if (value == null) errors.push(`filter ${name} has no value`);
      else if (name === ReadsClient.IDS && String(value).split(',').length > ReadsClient.MAX_IDS) {
        errors.push(`filter ${ReadsClient.IDS} takes at most ${ReadsClient.MAX_IDS} values`);
      }
    }
    if (errors.length > 0) throw local(`invalid read: ${errors.join('; ')}`);
    const platformActor = context != null && context.actor.type === 'system' && context.actor.via === 'platform'
      && context.actor.hookId == null;
    if (context != null && context.actor.type === 'system'
      && !(platformActor && ReadsClient.PLATFORM_LISTS.includes(port))) {
      throw local(`the system context reads only objects (${port} list refused)`);
    }
    this.checkDeclared(port);
    const forwarded = this.channel.forwarded(context);

    let query = '';
    for (const [name, value] of given) query += `${query === '' ? '?' : '&'}${name}=${queryValue(value)}`;
    query += `${query === '' ? '?' : '&'}page=${page}&size=${size}`;
    const answer = await this.channel.send('GET', forwarded, `/${port}`, query, null);
    if (answer.status !== 200) throw this.channel.failure(answer);

    const body = this.channel.answerObject(answer, 'a page {items, page, size, total}');
    const items = body.items;
    if (!Array.isArray(items) || typeof body.page !== 'number' || typeof body.size !== 'number'
      || typeof body.total !== 'number') {
      throw new KfException(new PlatformProblem(200, null, null, 'the reads answer is not a page {items, page, size, total}',
        ErrorClass.PERMANENT, null));
    }
    const objects = items.filter((o): o is Record<string, unknown> => o !== null && typeof o === 'object' && !Array.isArray(o))
      .map((o) => ({ ...o }));
    return new ReadPage(objects, Math.trunc(body.page), Math.trunc(body.size), Math.trunc(body.total));
  }

  /**
   * One object of the port in the tenant of `context`, with the heavy sub-collections named in `include` (
   * none = the base fields); `null` when the platform answers `404`.
   *
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`)
   */
  async get(context: RequestContext, port: string, id: string, include: readonly string[] = []):
    Promise<Record<string, unknown> | null> {
    const errors: string[] = [];
    checkPort(errors, port);
    if (id == null || id.trim() === '') errors.push('id is required');
    const names = include ?? [];
    for (const name of names) {
      if (name == null || !ReadsClient.NAME.test(name)) errors.push(`include ${name} must match [a-z][A-Za-z0-9]{0,40}`);
    }
    if (errors.length > 0) throw local(`invalid read: ${errors.join('; ')}`);
    if (context != null && context.actor.type === 'system' && names.length > 0) {
      throw local(`the system context reads only the base fields (include ${names.join(',')} refused)`);
    }
    this.checkDeclared(port);
    const forwarded = this.channel.forwarded(context);

    const query = names.length === 0 ? '' : `?include=${names.join(',')}`;
    const answer = await this.channel.send('GET', forwarded, `/${port}/${segment(id)}`, query, null);
    if (answer.status === 404) return null;
    if (answer.status !== 200) throw this.channel.failure(answer);
    return this.channel.answerObject(answer, 'an object');
  }

  /** With the manifest known, the port must be one the module declares (the platform would answer 403). */
  private checkDeclared(port: string): void {
    const manifest = this.channel.kf().manifest();
    if (manifest == null) return;
    const declared = manifest.capabilities.reads.some((r) => port === r.port);
    if (!declared) throw local(`${port} is not declared in capabilities.reads of the manifest`);
  }
}

function checkPort(errors: string[], port: string | null | undefined): void {
  if (port == null || !PORT.test(port)) errors.push(`port must match ${Manifest.PORT_ID}`);
}
