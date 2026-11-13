<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/ExtensionsClient.java. Do not edit by hand: fix Java, then re-derive.

declare(strict_types=1);

namespace Kf\Sdk;

/**
 * The module's own extension values on platform objects: protocol 1.1 §15.4. Obtained from
 * `KfModule::extensions()`. This is the only write a module makes on platform objects; ReadsClient never writes.
 *
 *     $kf->extensions()->write($ctx, 'model_version', '17', ['aas_shell' => 'urn:kf:shell:17', 'aas_last_push' => null]);
 *
 * `write()` sends `PATCH …/extensions/{entity}/{id}` with **exactly the fields given** (a field not given is left as it
 * is; `null` clears), the module token, the context and its proof, unchanged (PlatformChannel); the answer is the
 * module's values on that object. Only the fields the module declares in `capabilities.fields[]` for `$entity`, on an
 * object visible in the context's tenant. Refused locally, PERMANENT, nothing sent: an entity other than
 * `model, order, customer, model_version`, a blank id, no values, a field name outside `^[a-z][a-z0-9_]{0,40}$` (or not
 * declared for the entity, when the manifest is known), and the context rules of ReadsClient. The platform's refusals
 * are PlatformRefusals: `400` with `errors["extensions.<slug>.<field>"]` for a value of the wrong type, `404` for an
 * object not visible in the tenant.
 */
final class ExtensionsClient
{
    /** The extensible entities of protocol 1.1 (§6.2). */
    public const ENTITIES = ['model', 'order', 'customer', 'model_version'];
    /** A field name, as the manifest declares it. */
    public const FIELD = '/^[a-z][a-z0-9_]{0,40}$/';
    private const ENTITY_LIST = 'model, order, customer, model_version';

    private readonly PlatformChannel $channel;

    /** @internal */
    public function __construct(KfModule $kf)
    {
        $this->channel = new PlatformChannel($kf, 'extensions');
    }

    /**
     * Writes the module's values of the given fields on one object of `$entity` in the tenant of `$context`; returns the
     * module's values on that object after the write.
     *
     * @param ?array<string, mixed> $values field → value (`null` clears); sent exactly, in this order
     * @return array<string, mixed>
     * @throws KfException a local refusal, a transient error or an unreadable answer
     * @throws PlatformRefusal the platform refused the call (`400`, `401`, `403`, `404`)
     */
    public function write(RequestContext $context, string $entity, string $id, ?array $values): array
    {
        $errors = [];
        if (!in_array($entity, self::ENTITIES, true)) $errors[] = 'entity must be one of ' . self::ENTITY_LIST;
        if (trim($id) === '') $errors[] = 'id is required';
        $fields = $values === null ? [] : array_map('strval', array_keys($values));
        if ($fields === []) {
            $errors[] = 'at least one value is required';
        } else {
            foreach ($fields as $field) {
                if (preg_match(self::FIELD, $field) !== 1) $errors[] = "field $field must match ^[a-z][a-z0-9_]{0,40}$";
            }
        }
        if ($errors !== []) throw PlatformChannel::local('invalid extension write: ' . implode('; ', $errors));
        $manifest = $this->channel->kf()->manifest();
        if ($manifest !== null) {
            foreach ($fields as $field) {
                $declared = false;
                foreach ($manifest->capabilities->fields as $f) {
                    if ($f->entity === $entity && $f->name === $field) $declared = true;
                }
                if (!$declared) {
                    throw PlatformChannel::local("$field is not declared in capabilities.fields for $entity in the manifest");
                }
            }
        }
        $forwarded = $this->channel->forwarded($context);

        $answer = $this->channel->send('PATCH', $forwarded, '/' . $entity . '/' . PlatformChannel::segment($id), '', $values);
        if ($answer['status'] !== 200) throw $this->channel->failure($answer);
        return $this->channel->answerObject($answer, 'an object of values');
    }
}
