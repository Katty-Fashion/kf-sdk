// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/SettingsClient.java. Do not edit by hand: fix Java, then re-derive.
import type { KfModule } from './kfModule.js';
import { log } from './log.js';
import type { SettingCapability } from './manifest.js';
import { local, PlatformChannel } from './platformChannel.js';
import type { RequestContext } from './requestContext.js';

/**
 * The module's settings in a tenant: capability `settings` in `kf-module-conf.json`, protocol 1.1 §15.2 (
 * "settings carry no data": a setting is a closed scalar). Obtained from `KfModule.settings()`.
 *
 *   const s = await kf.settings().effective(ctx);
 *   const live = s.live as boolean;              // BOOLEAN -> boolean
 *   const batch = s['batch-size'] as number;     // NUMBER  -> number (Java: BigDecimal)
 *   const mode = s['push-mode'] as string;       // ENUM    -> string, one of the declared values
 *
 * {@link effective} sends `GET …/settings` with the module token, the context and its proof, unchanged
 * (`PlatformChannel`); the platform answers every declared key of the context's tenant, defaults included. The values
 * are typed by the module's manifest (`SettingCapability`): `BOOLEAN` → `boolean`, `NUMBER` → `number` (within
 * `min..max` when declared), `ENUM` → `string` (one of `values`). A declared key missing from the answer, or a value
 * that does not match its declaration, takes the declared default (with a WARN); a key the manifest does not declare
 * is ignored (with a WARN). Without the manifest the call is refused locally (nothing to type the values with). The
 * same local refusals as `ReadsClient` for the context; the platform's refusals are `PlatformRefusal`s.
 */
export class SettingsClient {
  private readonly channel: PlatformChannel;

  /** @internal */
  constructor(kf: KfModule) {
    this.channel = new PlatformChannel(kf, 'settings');
  }

  /**
   * The effective settings of the module in the tenant of `context`, typed by the manifest, in the order the manifest
   * declares them.
   *
   * @throws KfException a local refusal (no manifest, unknown context, no proof), a transient error or an unreadable
   *                     answer
   * @throws PlatformRefusal the platform refused the call (`401`, `403`)
   */
  async effective(context: RequestContext): Promise<Readonly<Record<string, unknown>>> {
    const manifest = this.channel.kf().manifest();
    if (manifest == null) {
      throw local('settings need the module manifest (KF_MODULE_MANIFEST) to type the values');
    }
    const forwarded = this.channel.forwarded(context);
    const answer = await this.channel.send('GET', forwarded, '', '', null);
    if (answer.status !== 200) throw this.channel.failure(answer);
    const values = this.channel.answerObject(answer, 'an object of values');

    const result: Record<string, unknown> = {};
    for (const setting of manifest.capabilities.settings) {
      const key = setting.key as string;
      const fallback = typed(setting, setting.defaultValue);
      if (!Object.hasOwn(values, key)) {
        log().warn(`KF SDK: setting ${key} is missing from the platform's answer; the declared default applies`);
        result[key] = fallback;
        continue;
      }
      let value = typed(setting, values[key]);
      if (value == null) {
        log().warn(`KF SDK: setting ${key} does not match its declaration (${setting.type}); the declared default applies`);
        value = fallback;
      }
      result[key] = value;
    }
    for (const key of Object.keys(values)) {
      if (!Object.hasOwn(result, key)) log().warn(`KF SDK: setting ${key} is not declared in the manifest; ignored`);
    }
    return Object.freeze(result);
  }
}

/** @internal The value typed by its declaration, or null when it does not match it. */
export function typed(setting: SettingCapability, value: unknown): unknown {
  if (value == null || setting.type == null) return null;
  switch (setting.type) {
    case 'BOOLEAN': return typeof value === 'boolean' ? value : null;
    case 'NUMBER': {
      if (typeof value !== 'number') return null;
      if (setting.min != null && value < setting.min) return null;
      if (setting.max != null && value > setting.max) return null;
      return value;
    }
    case 'ENUM': return typeof value === 'string' && setting.values.includes(value) ? value : null;
    default: return null;
  }
}
