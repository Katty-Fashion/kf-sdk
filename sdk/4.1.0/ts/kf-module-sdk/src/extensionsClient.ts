// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ExtensionsClient.java. Do not edit by hand: fix Java, then re-derive.
import type { KfModule } from './kfModule.js';
import { local, PlatformChannel, segment } from './platformChannel.js';
import type { RequestContext } from './requestContext.js';

const ENTITY_LIST = 'model, order, customer, model_version';

/**
 * The module's own extension values on platform objects: protocol 1.1 §15.4. Obtained from
 * `KfModule.extensions()`. This is the only write a module makes on platform objects; `ReadsClient` never writes.
 *
 *   await kf.extensions().write(ctx, 'model_version', '17', { aas_shell: 'urn:kf:shell:17', aas_last_push: null });
 *
 * {@link write} sends `PATCH …/extensions/{entity}/{id}` with **exactly the fields given** (a field not given is left as
 * it is; `null` clears), the module token, the context and its proof, unchanged (`PlatformChannel`); the answer is the
 * module's values on that object. Only the fields the module declares in `capabilities.fields[]` for `entity`, on an
 * object visible in the context's tenant. Refused locally, `permanent`, nothing sent: an entity other than
 * `model, order, customer, model_version`, a blank id, no values, a field name outside `^[a-z][a-z0-9_]{0,40}$` (or not
 * declared for the entity, when the manifest is known), and the context rules of `ReadsClient`. The platform's refusals
 * are `PlatformRefusal`s: `400` with `errors["extensions.<slug>.<field>"]` for a value of the wrong type, `404` for an
 * object not visible in the tenant.
 */
export class ExtensionsClient {
  /** The extensible entities of protocol 1.1 (§6.2). */
  static readonly ENTITIES: readonly string[] = Object.freeze(['model', 'order', 'customer', 'model_version']);
  /** A field name, as the manifest declares it. */
  static readonly FIELD = /^[a-z][a-z0-9_]{0,40}$/;

  private readonly channel: PlatformChannel;

  /** @internal */
  constructor(kf: KfModule) {
    this.channel = new PlatformChannel(kf, 'extensions');
  }

  /**
   * Writes the module's values of the given fields on one object of `entity` in the tenant of `context`; returns the
   * module's values on that object after the write.
   *
   * @param values field → value (`null` clears); sent exactly, in this order
   * @throws KfException a local refusal, a transient error or an unreadable answer
   * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`, `404`)
   */
  async write(context: RequestContext, entity: string, id: string, values: Record<string, unknown> | null | undefined):
    Promise<Readonly<Record<string, unknown>>> {
    const errors: string[] = [];
    if (entity == null || !ExtensionsClient.ENTITIES.includes(entity)) errors.push(`entity must be one of ${ENTITY_LIST}`);
    if (id == null || id.trim() === '') errors.push('id is required');
    const fields = values == null ? [] : Object.keys(values);
    if (fields.length === 0) {
      errors.push('at least one value is required');
    } else {
      for (const field of fields) {
        if (!ExtensionsClient.FIELD.test(field)) errors.push(`field ${field} must match ^[a-z][a-z0-9_]{0,40}$`);
      }
    }
    if (errors.length > 0) throw local(`invalid extension write: ${errors.join('; ')}`);
    const manifest = this.channel.kf().manifest();
    if (manifest != null) {
      for (const field of fields) {
        const declared = manifest.capabilities.fields.some((f) => entity === f.entity && field === f.name);
        if (!declared) throw local(`${field} is not declared in capabilities.fields for ${entity} in the manifest`);
      }
    }
    const forwarded = this.channel.forwarded(context);

    const body = { ...(values as Record<string, unknown>) };
    const answer = await this.channel.send('PATCH', forwarded, `/${entity}/${segment(id)}`, '', body);
    if (answer.status !== 200) throw this.channel.failure(answer);
    return Object.freeze({ ...this.channel.answerObject(answer, 'an object of values') });
  }
}
