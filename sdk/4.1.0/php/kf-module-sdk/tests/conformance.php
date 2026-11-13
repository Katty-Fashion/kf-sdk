<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/conformance/ConformanceSuiteTest.java + FixtureRunner.java. Do not edit by hand.

/*
 * The PHP runner of the shared conformance suite, plain PHP (no phpunit, no Composer package):
 *
 *   php sdk/php/kf-module-sdk/tests/conformance.php
 *
 * Every JSON file under sdk/conformance/ (relative to the package: ../../conformance) is one case. Each fixture is
 * checked against fixture.schema.json with explicit key checks (required, additionalProperties: false, the area enum,
 * id = <area>/<file>, a non-blank source) and run by area. An area the runner does not know fails, a failing fixture
 * fails, a fixture the runner cannot run fails, and zero fixtures fail the suite: there is no skip. Exit code 0 only
 * when every fixture passed.
 */

declare(strict_types=1);

use Kf\Sdk\AuditForbidden;
use Kf\Sdk\AuditRecord;
use Kf\Sdk\AuditUnauthorized;
use Kf\Sdk\BeforeContext;
use Kf\Sdk\FieldType;
use Kf\Sdk\FlowEvents;
use Kf\Sdk\FlowRules;
use Kf\Sdk\OrderServices;
use Kf\Sdk\Json;
use Kf\Sdk\KfException;
use Kf\Sdk\KfModule;
use Kf\Sdk\NotificationRejected;
use Kf\Sdk\NotificationOptions;
use Kf\Sdk\Log;
use Kf\Sdk\Manifest;
use Kf\Sdk\ManifestCheck;
use Kf\Sdk\ModelHooks;
use Kf\Sdk\OnFailure;
use Kf\Sdk\HookEvents\DeliveryContext;
use Kf\Sdk\HookEvents\ModuleEvent;
use Kf\Sdk\PlatformRefusal;
use Kf\Sdk\Rejected;
use Kf\Sdk\RequestContext;
use Kf\Sdk\Signatures;
use Kf\Sdk\Surface;

spl_autoload_register(static function (string $class): void {
    if (!str_starts_with($class, 'Kf\\Sdk\\')) return;
    $file = __DIR__ . '/../src/' . str_replace('\\', '/', substr($class, strlen('Kf\\Sdk\\'))) . '.php';
    if (is_file($file)) require_once $file;
});

const ROOT = __DIR__ . '/../../../conformance';
const TOKEN = 'kfm_conformance-token-never-logged';
const SECRET = 'conformance-secret';

/** A failed expectation of one fixture. */
final class Failed extends \RuntimeException
{
}

function check(bool $condition, string $message): void
{
    if (!$condition) throw new Failed($message);
}

/** JSON values compared as JSON: object keys in any order, objects and lists kept apart. */
function canonical(mixed $v): mixed
{
    if ($v instanceof \stdClass) {
        $a = get_object_vars($v);
        ksort($a, SORT_STRING);
        $o = new \stdClass();
        foreach ($a as $k => $x) $o->{$k} = canonical($x);
        return $o;
    }
    if (is_array($v)) {
        if (array_is_list($v)) return array_map('canonical', $v);
        return canonical(json_decode(json_encode($v, JSON_PRESERVE_ZERO_FRACTION)));
    }
    return $v;
}

function same(mixed $expected, mixed $actual, string $message): void
{
    $e = Json::encode(canonical($expected));
    $a = Json::encode(canonical($actual));
    check($e === $a, "$message: expected $e, got $a");
}

/** A PHP value (arrays built by the SDK, or a parsed tree) as the parsed JSON tree (objects as \stdClass). */
function tree(mixed $value): mixed
{
    return Json::decode(Json::encode($value));
}

// the SDK logs every refusal: keep the runner output readable
Log::use(static function (string $level, string $line): void {
});

// ---- the suite ----------------------------------------------------------------------------------------------

check(is_dir(ROOT), 'the conformance suite is not at ' . ROOT);
$schema = Json::decode((string) file_get_contents(ROOT . '/fixture.schema.json'));
check(Json::get($schema, 'additionalProperties') === false, 'the schema must close the fixture keys');
$required = Json::get($schema, 'required');
$properties = array_keys(get_object_vars(Json::get($schema, 'properties')));
$areas = Json::get(Json::get(Json::get($schema, 'properties'), 'area'), 'enum');

$fixtures = [];
$walk = static function (string $dir) use (&$walk, &$fixtures): void {
    $names = scandir($dir);
    sort($names, SORT_STRING);
    foreach ($names as $name) {
        if ($name === '.' || $name === '..') continue;
        $path = $dir . '/' . $name;
        if (is_dir($path)) $walk($path);
        elseif (str_ends_with($name, '.json') && $dir !== ROOT) $fixtures[] = $path; // fixture.schema.json and other root files
    }
};
$walk(ROOT);
sort($fixtures, SORT_STRING);

$failed = 0;
$passed = 0;
if ($fixtures === []) {
    fwrite(STDERR, 'FAIL: 0 fixtures found under ' . ROOT . "\n");
    exit(1);
}
$ids = [];
foreach ($fixtures as $path) {
    $rel = substr($path, strlen(ROOT) + 1);
    try {
        $fixture = Json::decode((string) file_get_contents($path));
        check(Json::isObject($fixture), "$rel must be a JSON object");
        foreach ($required as $key) check(Json::has($fixture, $key), "$rel: missing key \"$key\"");
        foreach (array_keys(get_object_vars($fixture)) as $key) check(in_array($key, $properties, true), "$rel: key \"$key\" is not allowed by fixture.schema.json");
        foreach (['id', 'area', 'description', 'source'] as $key) {
            $v = Json::get($fixture, $key);
            check(is_string($v) && trim($v) !== '', "$rel: \"$key\" must be a non-blank string");
        }
        foreach (['input', 'expect'] as $key) check(Json::isObject(Json::get($fixture, $key)), "$rel: \"$key\" must be an object");
        $area = $fixture->area;
        check(in_array($area, $areas, true), "$rel: area \"$area\" is not in fixture.schema.json");
        check(substr($rel, 0, (int) strpos($rel, '/')) === $area, "$rel: area must equal the directory name");
        check($fixture->id === substr($rel, 0, -strlen('.json')), "$rel: id must be <area>/<file name>");
        check(!isset($ids[$fixture->id]), "$rel: id {$fixture->id} is not unique");
        $ids[$fixture->id] = true;
        run($fixture);
        $passed++;
        echo "ok   $rel\n";
    } catch (\Throwable $e) {
        $failed++;
        echo "FAIL $rel: " . ($e instanceof Failed ? '' : $e::class . ': ') . $e->getMessage() . "\n";
    }
}
echo sprintf("\n%d fixtures, %d passed, %d failed, 0 skipped\n", count($fixtures), $passed, $failed);
exit($failed === 0 ? 0 : 1);

// ---- FixtureRunner ------------------------------------------------------------------------------------------

function run(\stdClass $fixture): void
{
    $id = $fixture->id;
    $input = $fixture->input;
    $expect = $fixture->expect;
    match ($fixture->area) {
        'errors' => errors($id, $input, $expect),
        'registration' => registration($id, $input, $expect),
        'manifest' => manifest($id, $input, $expect),
        'signature' => signature($id, $input, $expect),
        'envelope', 'before' => callback($id, $input, $expect),
        'flags' => flags($id, $input, $expect),
        'context' => context($id, $input, $expect),
        'audit' => audit($id, $input, $expect),
        'notifications' => notifications($id, $input, $expect),
        'reads', 'settings', 'secrets', 'extensions', 'storage', 'peers', 'services' => service($fixture->area, $id, $input, $expect),
        'slots' => slots($id, $input, $expect),
        'flow' => flow($id, $input, $expect),
        'events' => Json::has($input, 'envelope') ? moduleEvent($id, $input, $expect) : service($fixture->area, $id, $input, $expect),
        default => throw new Failed("$id: the PHP runner does not know the area \"{$fixture->area}\""),
    };
}

// ---- errors -------------------------------------------------------------------------------------------------

/** The platform's answer to the registration → whether the SDK retries, the class, the error line. */
function errors(string $id, \stdClass $input, \stdClass $expect): void
{
    $retry = $expect->retry;
    $server = null;
    if (Json::get($input, 'network') === 'connection-refused') {
        $url = 'http://127.0.0.1:' . freePort();
    } else {
        check(Json::isObject(Json::get($input, 'response')), "$id: input.response or input.network is required");
        $server = answering($input->response);
        $url = 'http://127.0.0.1:' . $server['port'];
    }
    $state = sys_get_temp_dir() . '/kf-sdk-conformance-' . bin2hex(random_bytes(4));
    $kf = KfModule::of($url, 'conformance', TOKEN, 'conformance-secret', null, $state)->withRetryDelays([50]);
    try {
        $thrown = null;
        try {
            // the blocking twin of the Java background loop: two attempts at most when the error is transient
            $kf->registerWithRetry($retry ? 2 : 3);
        } catch (KfException $e) {
            $thrown = $e;
        }
        check($thrown !== null, "$id: the registration must fail");
        $requests = $server === null ? null : requests($server);
        if ($server === null) {
            check($kf->lastRegistrationError() !== null, "$id: the error must be kept");
            check(!$kf->isRegistrationStopped() === $retry, "$id: retry");
        } elseif ($retry) {
            check($requests >= 2, "$id: a transient error must be retried (requests: $requests)");
            check(!$kf->isRegistrationStopped(), "$id: a transient error must not stop the registration");
        } else {
            check($kf->isRegistrationStopped(), "$id: a permanent error must stop the registration");
            check($requests === 1, "$id: a permanent error must not be retried (requests: $requests)");
        }
        check($kf->lastRegistrationProblem() !== null, "$id: the problem must be kept");
        check($kf->lastRegistrationProblem()->errorClass->wire() === $expect->class,
            "$id: class " . $kf->lastRegistrationProblem()->errorClass->wire());
        $line = (string) $kf->lastRegistrationError();
        foreach (Json::get($expect, 'messageContains') ?? [] as $fragment) {
            check(str_contains($line, (string) $fragment), "$id: the error line must contain \"$fragment\": $line");
        }
        check(!str_contains($line, TOKEN), "$id: the module token must never reach the error line");
    } finally {
        if ($server !== null) stopServer($server);
        removeDir($state);
    }
}

function freePort(): int
{
    $socket = stream_socket_server('tcp://127.0.0.1:0', $errno, $errstr);
    if ($socket === false) throw new \RuntimeException("no free port: $errstr");
    $name = stream_socket_get_name($socket, false);
    fclose($socket);
    return (int) substr($name, strrpos($name, ':') + 1);
}

/** A platform that answers every request with `$response`, counting the requests: `php -S` with a router script. */
function answering(\stdClass $response): array
{
    $dir = sys_get_temp_dir() . '/kf-sdk-platform-' . bin2hex(random_bytes(4));
    mkdir($dir);
    $body = Json::get($response, 'body');
    $bytes = $body === null ? '' : (is_string($body) ? $body : Json::encode($body));
    file_put_contents($dir . '/response.json', Json::encode([
        'status' => $response->status,
        'headers' => Json::obj(Json::toArray(Json::get($response, 'headers')) ?? []),
        'body' => $bytes,
    ]));
    file_put_contents($dir . '/count', '0');
    file_put_contents($dir . '/router.php', <<<'PHP'
        <?php
        $dir = __DIR__;
        file_get_contents('php://input');
        $fp = fopen($dir . '/count', 'c+');
        flock($fp, LOCK_EX);
        $n = (int) stream_get_contents($fp);
        ftruncate($fp, 0);
        rewind($fp);
        fwrite($fp, (string) ($n + 1));
        flock($fp, LOCK_UN);
        fclose($fp);
        $r = json_decode(file_get_contents($dir . '/response.json'), true);
        http_response_code($r['status']);
        foreach ($r['headers'] as $k => $v) header($k . ': ' . $v);
        echo $r['body'];
        PHP);
    $port = freePort();
    $process = proc_open([PHP_BINARY, '-S', '127.0.0.1:' . $port, $dir . '/router.php'], [
        0 => ['pipe', 'r'], 1 => ['file', $dir . '/server.log', 'a'], 2 => ['file', $dir . '/server.log', 'a'],
    ], $pipes, $dir);
    if (!is_resource($process)) throw new \RuntimeException('php -S did not start');
    $deadline = microtime(true) + 10;
    while (true) {
        $c = @stream_socket_client('tcp://127.0.0.1:' . $port, $errno, $errstr, 0.2);
        if ($c !== false) {
            fclose($c);
            break;
        }
        if (microtime(true) > $deadline) throw new \RuntimeException("php -S did not listen on $port");
        usleep(50_000);
    }
    return ['dir' => $dir, 'port' => $port, 'process' => $process, 'pipes' => $pipes];
}

function requests(array $server): int
{
    return (int) file_get_contents($server['dir'] . '/count');
}

function stopServer(array $server): void
{
    foreach ($server['pipes'] as $p) @fclose($p);
    $status = proc_get_status($server['process']);
    if (PHP_OS_FAMILY === 'Windows' && $status['running']) {
        exec('taskkill /F /T /PID ' . (int) $status['pid'] . ' 2>NUL');
    } else {
        proc_terminate($server['process']);
    }
    proc_close($server['process']);
    removeDir($server['dir']);
}

function removeDir(string $dir): void
{
    if (!is_dir($dir)) return;
    foreach (scandir($dir) as $f) {
        if ($f === '.' || $f === '..') continue;
        $p = $dir . '/' . $f;
        is_dir($p) ? removeDir($p) : @unlink($p);
    }
    @rmdir($dir);
}

// ---- registration -------------------------------------------------------------------------------------------

/** A declarative module description -> registrationBody(), equal as JSON to expect.body. */
function registration(string $id, \stdClass $input, \stdClass $expect): void
{
    same($expect->body, tree(module($input)->registrationBody()), "$id: registration body");
}

/**
 * Builds a module from a declarative description (shared by the areas that need one): slug, callbackBase,
 * fields[{entity,name,type,table,labelKey}], hooks[{event,priority,onFailure,fields}],
 * surfaces[{slug,kind,entry,export|tag,css,order,labelKey,flag,key,peer}].
 */
function module(\stdClass $input, string $platformUrl = 'http://127.0.0.1:1', ?string $state = null): KfModule
{
    $kf = KfModule::of($platformUrl, Json::get($input, 'slug') ?? 'conformance', TOKEN,
        Json::get($input, 'hookSecret') ?? 'conformance-secret', Json::get($input, 'callbackBase'), $state);
    foreach (Json::get($input, 'fields') ?? [] as $f) {
        $kf->registerField($f->entity, $f->name, FieldType::from($f->type), $f->table, Json::get($f, 'labelKey'));
    }
    foreach (Json::get($input, 'hooks') ?? [] as $h) hook($kf, $h);
    foreach (Json::get($input, 'surfaces') ?? [] as $d) {
        $s = match ((string) Json::get($d, 'kind')) {
            'wc' => Surface::wc($d->entry, $d->tag),
            // protocol 2.0 (§7.12): the kinds without code of a slot of another module
            'layer' => Surface::layer($d->layer, $d->source),
            'data' => Surface::data(slotData($d)),
            default => Surface::react($d->entry, $d->export),
        };
        if (Json::get($d, 'css') !== null) $s->css($d->css);
        if (Json::get($d, 'order') !== null) $s->order((int) $d->order);
        if (Json::get($d, 'labelKey') !== null) $s->labelKey($d->labelKey);
        if (Json::get($d, 'flag') !== null) $s->flag($d->flag);
        if (Json::get($d, 'key') !== null) $s->key($d->key);
        foreach (Json::entries(Json::get($d, 'peer')) as $k => $v) $s->peer((string) $k, (string) $v);
        $kf->ui()->contribute($d->slug, $s);
    }
    return $kf;
}

/** `data`, or `dataRepeat {field, char, count}`: an object with one text of `count` characters. */
function slotData(\stdClass $d): ?array
{
    $repeat = Json::get($d, 'dataRepeat');
    if ($repeat === null) return Json::toArray(Json::get($d, 'data'));
    return [$repeat->field => str_repeat((string) $repeat->char, (int) $repeat->count)];
}

// ---- slots (protocol 2.0) ----------------------------------------------------------------------

/**
 * The contributions to the slots of other modules: what the SDK sends (expect.surfaces) and the registration warnings
 * it exposes (expect.warnings) from the platform's scripted answer; or a contribution refused locally before
 * anything is sent (expect.localError).
 */
function slots(string $id, \stdClass $input, \stdClass $expect): void
{
    if (Json::get($expect, 'localError') !== null) {
        $refused = null;
        try {
            module($input);
        } catch (\InvalidArgumentException $e) {
            $refused = $e;
        }
        check($refused !== null, "$id: refused locally");
        check(str_contains($refused->getMessage(), $expect->localError),
            "$id: the local refusal must contain <{$expect->localError}>: " . $refused->getMessage());
        return;
    }
    $server = slotsPlatform($input->platform);
    $state = sys_get_temp_dir() . '/kf-sdk-conformance-' . bin2hex(random_bytes(4));
    try {
        $kf = module($input, 'http://127.0.0.1:' . $server['port'], $state);
        $kf->register();
        check($kf->isRegistered(), "$id: registered");
        $sent = json_decode((string) file_get_contents($server['dir'] . '/request.json'));
        same($expect->surfaces, tree($sent->surfaces), "$id: the contributions sent");
        same($expect->warnings, $kf->registrationWarnings(), "$id: the warnings exposed");
    } finally {
        stopServer($server);
        removeDir($state);
    }
}

/**
 * A platform that answers the registration with `$answer` ({status, body}) and keeps that request in request.json; the
 * flags read after it gets `[]`: `php -S` with a router script.
 */
function slotsPlatform(\stdClass $answer): array
{
    $dir = sys_get_temp_dir() . '/kf-sdk-slots-' . bin2hex(random_bytes(4));
    mkdir($dir);
    file_put_contents($dir . '/response.json', Json::encode(['status' => $answer->status, 'body' => Json::encode($answer->body)]));
    file_put_contents($dir . '/router.php', <<<'PHP'
        <?php
        $dir = __DIR__;
        $raw = file_get_contents('php://input');
        $uri = $_SERVER['REQUEST_URI'];
        $q = strpos($uri, '?');
        $path = $q === false ? $uri : substr($uri, 0, $q);
        header('Content-Type: application/json');
        if (str_ends_with($path, '/registrations')) {
            file_put_contents($dir . '/request.json', $raw);
            $r = json_decode(file_get_contents($dir . '/response.json'), true);
            http_response_code($r['status']);
            echo $r['body'];
        } else {
            echo '[]';
        }
        PHP);
    $port = freePort();
    $process = proc_open([PHP_BINARY, '-S', '127.0.0.1:' . $port, $dir . '/router.php'], [
        0 => ['pipe', 'r'], 1 => ['file', $dir . '/server.log', 'a'], 2 => ['file', $dir . '/server.log', 'a'],
    ], $pipes, $dir);
    if (!is_resource($process)) throw new \RuntimeException('php -S did not start');
    $deadline = microtime(true) + 10;
    while (true) {
        $c = @stream_socket_client('tcp://127.0.0.1:' . $port, $errno, $errstr, 0.2);
        if ($c !== false) {
            fclose($c);
            break;
        }
        if (microtime(true) > $deadline) throw new \RuntimeException("php -S did not listen on $port");
        usleep(50_000);
    }
    return ['dir' => $dir, 'port' => $port, 'process' => $process, 'pipes' => $pipes];
}

/** One declared hook; before handlers allow. */
function hook(KfModule $kf, \stdClass $h): void
{
    $priority = Json::get($h, 'priority') === null ? ModelHooks::DEFAULT_PRIORITY : (int) $h->priority;
    $onFailure = Json::get($h, 'onFailure') === 'block' ? OnFailure::BLOCK : OnFailure::ALLOW;
    $allow = static fn (BeforeContext $ctx) => $ctx->allow();
    $noop = static function (): void {
    };
    $m = $kf->models();
    match ($h->event) {
        'model.before.create' => $m->beforeCreate($allow, $priority, $onFailure),
        'model.before.update' => $m->beforeUpdate($allow, $priority, $onFailure),
        'model.before.delete' => $m->beforeDelete($allow, $priority, $onFailure),
        'model.after.create' => $m->afterCreate($noop),
        'model.after.update' => $m->afterUpdate($noop),
        'model.after.delete' => $m->afterDelete($noop),
        'model.field.changed' => $m->onFieldChange(array_map('strval', Json::get($h, 'fields') ?? []), $noop),
        default => throw new Failed("the declarative module does not know the event {$h->event}"),
    };
}

// ---- manifest -----------------------------------------------------------------------------------------------

/**
 * input.manifest (+ optional input.registration, a declarative module; platformLanguages) -> ManifestCheck:
 * expect.valid, errorsContain, warningsContain, and read (JSON pointer -> value on the typed Manifest).
 */
function manifest(string $id, \stdClass $input, \stdClass $expect): void
{
    $json = Json::encode($input->manifest);
    $languages = array_map('strval', Json::get($input, 'platformLanguages') ?? []);
    $result = Json::get($input, 'registration') === null
        ? ManifestCheck::validate($json, $languages)
        : ManifestCheck::check($json, module($input->registration)->registrationBody(), $languages);
    check($result->ok() === $expect->valid, "$id: valid; errors=" . Json::encode($result->errors));
    foreach (Json::get($expect, 'errorsContain') ?? [] as $fragment) {
        check(array_filter($result->errors, static fn ($e) => str_contains($e, (string) $fragment)) !== [],
            "$id: an error must contain <$fragment>: " . Json::encode($result->errors));
    }
    foreach (Json::get($expect, 'warningsContain') ?? [] as $fragment) {
        check(array_filter($result->warnings, static fn ($w) => str_contains($w, (string) $fragment)) !== [],
            "$id: a warning must contain <$fragment>: " . Json::encode($result->warnings));
    }
    if (Json::has($expect, 'warningsContain') && (Json::get($expect, 'warningsContain') ?? []) === []) {
        check($result->warnings === [], "$id: no warning expected: " . Json::encode($result->warnings));
    }
    $read = Json::get($expect, 'read');
    if ($read !== null) {
        $typed = tree(Manifest::parse($json));
        foreach (Json::entries($read) as $ptr => $value) same($value, pointer($typed, (string) $ptr), "$id: $ptr");
    }
}

// ---- flow (protocol 2.0, sdk-protocol.md 16.4) ----------------------------------------------------------

/**
 * The module's side of a flow rule. `input.query` (the raw query of the rule's request) then `expect.valid`
 * and `expect.projection` (the typed projection: each field the fixture writes, compared; `orderStages` a list, `onLine`
 * a boolean) or `expect.errorContains`. `input.decision` (`pass` | `block`, with `key` and `params`) then `expect.valid`
 * and `expect.json` (the answer's body) or `expect.errorContains` (refused locally).
 */
function flow(string $id, \stdClass $input, \stdClass $expect): void
{
    $valid = $expect->valid;
    if (Json::has($input, 'query')) {
        try {
            $p = FlowRules\Projection::fromQuery($input->query);
        } catch (\InvalidArgumentException $e) {
            check($valid === false, "$id: the projection must be read, it was refused: " . $e->getMessage());
            check(str_contains($e->getMessage(), $expect->errorContains), "$id: " . $e->getMessage());
            return;
        }
        check($valid === true, "$id: the projection must be refused, it was read");
        $actual = [
            'ruleKey' => $p->ruleKey, 'orderId' => $p->orderId, 'orderStatus' => $p->orderStatus, 'flowKey' => $p->flowKey,
            'flowVersion' => $p->flowVersion, 'instanceId' => $p->instanceId, 'stepKey' => $p->stepKey, 'stepTo' => $p->stepTo,
            'orderNumber' => $p->orderNumber, 'orderInternal' => $p->orderInternal, 'orderCustomerId' => $p->orderCustomerId,
            'stepBehavesLike' => $p->stepBehavesLike, 'orderStages' => $p->orderStages, 'lineId' => $p->lineId,
            'lineModelId' => $p->lineModelId, 'lineModelVersionId' => $p->lineModelVersionId,
            'lineQuantity' => $p->lineQuantity, 'onLine' => $p->onLine(),
        ];
        foreach (Json::entries(Json::get($expect, 'projection')) as $k => $v) {
            same($v, $actual[(string) $k] ?? null, "$id: projection.$k");
        }
        return;
    }
    try {
        $d = match ($input->decision) {
            'pass' => FlowRules\Decision::pass(),
            'block' => FlowRules\Decision::block(Json::get($input, 'key'), Json::toArray(Json::get($input, 'params'))),
            default => throw new Failed("$id: unknown decision {$input->decision}"),
        };
    } catch (\InvalidArgumentException $e) {
        check($valid === false, "$id: the decision must be built, it was refused: " . $e->getMessage());
        check(str_contains($e->getMessage(), $expect->errorContains), "$id: " . $e->getMessage());
        return;
    }
    check($valid === true, "$id: the decision must be refused locally, it was built");
    same($expect->json, tree($d->toJson()), "$id: json");
}

/** A JSON pointer (/a/0/b) into a parsed tree. */
function pointer(mixed $tree, string $ptr): mixed
{
    $at = $tree;
    foreach (explode('/', substr($ptr, 1)) as $token) {
        if (is_array($at)) $at = $at[(int) $token] ?? null;
        elseif ($at instanceof \stdClass) $at = Json::get($at, $token);
        else throw new Failed("pointer $ptr leaves the tree at $token");
    }
    return $at;
}

// ---- signature ----------------------------------------------------------------------------------------------

/** sign must produce expect.signature; verify must answer expect.valid. */
function signature(string $id, \stdClass $input, \stdClass $expect): void
{
    if (Json::has($expect, 'signature')) {
        check(Signatures::sign($input->secret, $input->timestamp, $input->body) === $expect->signature, "$id: signature");
    }
    if (Json::has($expect, 'valid')) {
        $reason = Signatures::verify($input->secret, $input->timestamp, Json::get($input, 'signature'), $input->body, (int) $input->now);
        check(($reason === null) === $expect->valid, "$id: valid (reason: " . ($reason ?? 'null') . ')');
        if (Json::get($expect, 'reasonContains') !== null) {
            check($reason !== null, "$id: a reason is expected");
            check(str_contains($reason, $expect->reasonContains), "$id: reason $reason");
        }
    }
}

// ---- envelope / before --------------------------------------------------------------------------------------

/**
 * One callback: input.envelope (or input.rawBody), signed by the runner with the module's secret now (input.signature:
 * valid by default, wrong); the module has a recording handler on every point, whose before handlers answer
 * input.answer. expect: status, handlerCalled, handler (what the handler saw), body (exact JSON) or bodyContains
 * (subset), contentType.
 */
function callback(string $id, \stdClass $input, \stdClass $expect): void
{
    $recorder = new Recorder(Json::get($input, 'answer'));
    $kf = $recorder->module();
    $result = send($kf, Json::get($input, 'envelope'), Json::get($input, 'rawBody'), Json::get($input, 'signature'));
    check($result->status === $expect->status, "$id: status {$result->status}; body {$result->body}");
    if (Json::has($expect, 'handlerCalled')) check(($recorder->seen !== null) === $expect->handlerCalled, "$id: handlerCalled");
    if (Json::get($expect, 'handler') !== null) {
        check($recorder->seen !== null, "$id: the handler was not called");
        foreach (Json::entries($expect->handler) as $k => $v) same($v, $recorder->seen[$k] ?? null, "$id: handler saw $k");
    }
    $body = Json::decode($result->body);
    if (Json::has($expect, 'body')) same($expect->body, $body, "$id: body");
    if (Json::get($expect, 'bodyContains') !== null) {
        foreach (Json::entries($expect->bodyContains) as $k => $v) same($v, Json::get($body, (string) $k), "$id: body.$k in {$result->body}");
    }
    if (Json::has($expect, 'contentType')) check($result->contentType === $expect->contentType, "$id: content type {$result->contentType}");
}

function send(KfModule $kf, mixed $envelope, ?string $rawBody, ?string $signature): \Kf\Sdk\HookResult
{
    $bytes = $rawBody ?? Json::encode($envelope);
    $now = time();
    $ts = (string) $now;
    $sig = $signature === 'wrong' ? str_repeat('0', 64) : Signatures::sign(SECRET, $ts, $bytes);
    return $kf->handle($bytes, $ts, $sig, $now);
}

/** A module with a recording handler on every point. */
final class Recorder
{
    public ?array $seen = null;
    private \stdClass $answer;

    public function __construct(?\stdClass $answer)
    {
        $this->answer = $answer ?? (object) ['decision' => 'allow'];
    }

    public function module(): KfModule
    {
        $kf = KfModule::of('http://127.0.0.1:1', 'conformance', TOKEN, SECRET, null);
        $before = function (BeforeContext $ctx) {
            $this->seen = self::seen($ctx->event, $ctx->envelope, $ctx->id());
            self::versions($this->seen, $ctx->changedVersionIds(), $ctx->changedVersionFields(), $ctx->before, $ctx->after);
            return $this->decide($ctx);
        };
        $after = function (\Kf\Sdk\HookEvents\AfterEvent $ev): void {
            $this->seen = self::seen($ev->event, $ev->envelope, $ev->id);
            self::versions($this->seen, $ev->changedVersionIds(), $ev->changedVersionFields(), $ev->before, $ev->after);
        };
        $kf->models()->beforeCreate($before)->beforeUpdate($before)->beforeDelete($before)
            ->afterCreate($after)->afterUpdate($after)->afterDelete($after)
            ->onFieldChange([], function (\Kf\Sdk\HookEvents\FieldChange $ch): void {
                $this->seen = self::seen('model.field.changed', $ch->envelope, $ch->id);
                $this->seen['field'] = $ch->field;
                $this->seen['versionField'] = self::versionField($ch->versionField());
            });
        // protocol 2.0 (sdk-protocol.md 16.2, 16.3): the order's field changes, the events of the flow engine
        $kf->orders()->onFieldChange([], function (\Kf\Sdk\HookEvents\FieldChange $ch): void {
            $this->seen = self::seen('order.field.changed', $ch->envelope, $ch->id);
            $this->seen['field'] = $ch->field;
            $this->seen['oldValue'] = $ch->oldValue;
            $this->seen['newValue'] = $ch->newValue;
        });
        foreach (FlowEvents::TYPES as $type) {
            $kf->flows()->on($type, function (\Kf\Sdk\FlowEvents\FlowEvent $ev): void {
                $this->seen = self::seen($ev->type, $ev->envelope, $ev->orderId);
                $this->seen['orderId'] = $ev->orderId;
                $this->seen['instanceId'] = $ev->instanceId;
                $this->seen['flowKey'] = $ev->flowKey;
                $this->seen['flowVersion'] = $ev->flowVersion;
                $this->seen['stepKey'] = $ev->stepKey;
            });
        }
        $kf->tenants()->onDeleted(function (\Kf\Sdk\HookEvents\TenantDeleted $deleted): void {
            $this->seen = self::seen('tenant.after.delete', $deleted->envelope, $deleted->tenantId);
        })->onSoftDelete(function (\Kf\Sdk\HookEvents\TenantSoftDeleted $e): void {
            $this->seen = self::seen('tenant.after.soft-delete', $e->envelope, $e->tenantId);
        })->onRestore(function (\Kf\Sdk\HookEvents\TenantRestored $e): void {
            $this->seen = self::seen('tenant.after.restore', $e->envelope, $e->tenantId);
        });
        return $kf;
    }

    /**
     * A version's values in the model's envelope: `changedVersionIds`,
     * `changedVersionFields` (`[{versionId, slug, field}]`) and `versionValues` (the same, with `before` / `after` read
     * by `versionExtension`).
     *
     * @param list<string> $ids
     * @param list<\Kf\Sdk\HookEvents\VersionField> $fields
     */
    private static function versions(array &$seen, array $ids, array $fields, ?\Kf\Sdk\ModelSnapshot $before,
                                     ?\Kf\Sdk\ModelSnapshot $after): void
    {
        $changed = [];
        $values = [];
        foreach ($fields as $f) {
            $changed[] = self::versionField($f);
            $v = self::versionField($f);
            $v['before'] = $before?->versionExtension($f->versionId, $f->slug, $f->field);
            $v['after'] = $after?->versionExtension($f->versionId, $f->slug, $f->field);
            $values[] = $v;
        }
        $seen['changedVersionIds'] = $ids;
        $seen['changedVersionFields'] = $changed;
        $seen['versionValues'] = $values;
    }

    private static function versionField(?\Kf\Sdk\HookEvents\VersionField $f): ?array
    {
        return $f === null ? null : ['versionId' => $f->versionId, 'slug' => $f->slug, 'field' => $f->field];
    }

    private static function seen(string $event, \Kf\Sdk\HookEvents\Envelope $e, ?string $id): array
    {
        return ['event' => $event, 'id' => $id, 'tenant' => $e->tenant, 'locale' => $e->locale, 'eventId' => $e->eventId,
            'deliveryId' => $e->deliveryId, 'envelopeVersion' => $e->envelopeVersion, 'actor' => $e->actor,
            'aggregateType' => $e->aggregateType];
    }

    private function decide(BeforeContext $ctx): \Kf\Sdk\Decision
    {
        $a = $this->answer;
        switch ($a->decision) {
            case 'allow':
                return $ctx->allow();
            case 'reject':
                return Json::has($a, 'params') ? $ctx->reject($a->key, Json::toArray($a->params)) : $ctx->reject($a->key);
            case 'patch':
                $patch = $ctx->patch();
                foreach (Json::entries(Json::get($a, 'set')) as $k => $v) $patch->set((string) $k, Json::toArray($v));
                foreach (Json::entries(Json::get($a, 'extensions')) as $k => $v) $patch->extension((string) $k, Json::toArray($v));
                return $patch;
            default:
                throw new \InvalidArgumentException('unknown answer ' . $a->decision);
        }
    }
}

// ---- flags --------------------------------------------------------------------------------------------------

/** input.envelopes handled in order; then expect.flags (key -> enabled). */
function flags(string $id, \stdClass $input, \stdClass $expect): void
{
    $kf = (new Recorder(null))->module();
    foreach (Json::get($input, 'envelopes') ?? [] as $envelope) {
        $result = send($kf, $envelope, null, null);
        check($result->status === 200, "$id: every callback is answered 200; {$result->body}");
    }
    foreach (Json::entries($expect->flags) as $key => $enabled) {
        check($kf->flags()->isEnabled((string) $key) === $enabled, "$id: flag $key");
    }
}

// ---- context -----------------------------------------------------------------------------------------

/**
 * input: secret, now, headers (the three X-KF-* headers; any may be absent), body. expect: valid: true + context (every
 * key, actor with its wire type), or valid: false + status, class, reasonContains.
 */
function context(string $id, \stdClass $input, \stdClass $expect): void
{
    $h = $input->headers;
    try {
        $ctx = RequestContext::verify(Json::get($h, RequestContext::CONTEXT_HEADER), Json::get($h, RequestContext::TIMESTAMP_HEADER),
            Json::get($h, RequestContext::SIGNATURE_HEADER), Json::get($input, 'body') ?? '', $input->secret, (int) $input->now);
    } catch (Rejected $rejected) {
        check($expect->valid === false, "$id: the context must be accepted, it was refused: " . $rejected->getMessage());
        check($rejected->problem()->status === $expect->status, "$id: status");
        check($rejected->errorClass()->wire() === $expect->class, "$id: class");
        if (Json::get($expect, 'reasonContains') !== null) {
            check(str_contains($rejected->getMessage(), $expect->reasonContains), "$id: reason " . $rejected->getMessage());
        }
        check(Json::get(Json::decode($rejected->problemJson()), 'class') === $expect->class, "$id: RFC 9457 body class");
        return;
    }
    check($expect->valid === true, "$id: the context must be refused, it was accepted");
    $actual = [
        'v' => $ctx->v, 'tenant' => $ctx->tenant, 'user' => $ctx->user,
        'actor' => ['type' => $ctx->actor->type, 'via' => $ctx->actor->via, 'hookId' => $ctx->actor->hookId],
        'locale' => $ctx->locale, 'rights' => $ctx->rights, 'method' => $ctx->method, 'path' => $ctx->path, 'query' => $ctx->query,
        'ts' => $ctx->ts, 'requestId' => $ctx->requestId,
    ];
    // protocol 1.2: person only when the context carries it
    if ($ctx->person !== null) $actual['person'] = ['email' => $ctx->person->email, 'displayName' => $ctx->person->displayName];
    same($expect->context, tree($actual), "$id: context");
}

// ---- audit -----------------------------------------------------------------------------------------

/**
 * `AuditClient::record/list` against a platform stub: the request it sends (method, path, query, the three headers, the
 * body) and what it makes of the answer. The context of `input.context` is signed by the runner with `ts` = now and
 * verified through `KfModule::verifyContext` with `input.proof`, as an adapter does; `"$context"` in the expected
 * headers is that exact `X-KF-Context`.
 */
function audit(string $id, \stdClass $input, \stdClass $expect): void
{
    $secret = 'conformance-audit-secret';
    $now = time();
    $contextJson = clone $input->context;
    $contextJson->ts = $now;
    $context = rtrim(strtr(base64_encode(Json::encode($contextJson)), '+/', '-_'), '=');
    $signature = Signatures::sign($secret, (string) $now, $context . '.');

    $server = recording($input->platform);
    try {
        $kf = KfModule::of('http://127.0.0.1:' . $server['port'], $input->slug, TOKEN, $secret, null);
        $ctx = $kf->verifyContext($context, (string) $now, $signature, '', Json::get($input, 'proof'));
        $rows = null;
        $error = null;
        try {
            if ($input->call === 'record') {
                $r = $input->record;
                $kf->audit()->record($ctx, new AuditRecord($r->action, $r->aggregateType, $r->aggregateRef));
            } elseif ($input->call === 'list') {
                $rows = $kf->audit()->list($ctx, $input->query->aggregateType, Json::get($input->query, 'aggregateRef'));
            } else {
                throw new Failed("$id: unknown call {$input->call}");
            }
        } catch (KfException $e) {
            $error = $e;
        }

        $request = Json::get($expect, 'request');
        if ($request === null) {
            check(requests($server) === 0, "$id: no request may be sent");
        } else {
            check(requests($server) === 1, "$id: exactly one request");
            $wanted = clone $request;
            $wanted->headers = clone $request->headers;
            foreach (get_object_vars($wanted->headers) as $k => $v) {
                if ($v === '$context') $wanted->headers->{$k} = $context;
            }
            same($wanted, Json::decode((string) file_get_contents($server['dir'] . '/request.json')), "$id: request");
        }

        if ($expect->outcome === 'ok') {
            check($error === null, "$id: must succeed, got " . ($error?->getMessage() ?? ''));
            if (Json::has($expect, 'rows')) same($expect->rows, tree($rows), "$id: rows");
        } else {
            check($error !== null, "$id: must fail");
            $e = $expect->error;
            check($error->problem()->status === $e->status, "$id: status " . $error->problem()->status);
            check($error->errorClass()->wire() === $e->class, "$id: class");
            $kind = $error instanceof AuditForbidden ? 'forbidden' : ($error instanceof AuditUnauthorized ? 'unauthorized' : 'other');
            check($kind === $e->kind, "$id: kind $kind");
            foreach (Json::get($e, 'messageContains') ?? [] as $fragment) {
                check(str_contains($error->getMessage(), (string) $fragment), "$id: message " . $error->getMessage());
            }
            check(!str_contains($error->getMessage(), TOKEN), "$id: the token in an error");
        }
    } finally {
        stopServer($server);
    }
}

// ---- the platform services of protocol 1.1: reads, settings, secrets, extensions -----------------------

/**
 * One call of a protocol 1.1 client (`reads()->list/get`, …) against a platform stub, exactly as `audit()`: the context
 * of `input.context` is signed with `ts` = now and verified through `KfModule::verifyContext` with `input.proof`;
 * `input.manifest`, when present, is given to the module (`withManifest`); the call is `input.call` with `input.args`;
 * the server answers `input.platform`. `expect.request` is the request sent (or `null` = none); `expect.result` the
 * value returned (`null` = an empty object read); `expect.error` = `{status (0 = local), class, kind: invalid |
 * unauthorized | forbidden | not-found | other, reason?, errors?, messageContains[]}`. Neither the token, nor the
 * proof, nor a secret value of `args.value` may appear in an error.
 */
function service(string $area, string $id, \stdClass $input, \stdClass $expect): void
{
    $secret = 'conformance-service-secret';
    $now = time();
    // protocol 2.0: an after delivery's context for a background job, possibly aged past its window
    $delivered = Json::get($input, 'contextSource') === 'delivery';
    $age = Json::get($input, 'contextAgeSeconds');
    $contextJson = clone $input->context;
    $contextJson->ts = $now - (is_int($age) ? $age : 0);
    $context = rtrim(strtr(base64_encode(Json::encode($contextJson)), '+/', '-_'), '=');
    $signature = Signatures::sign($secret, (string) $now, $context . '.');

    $server = recording($input->platform);
    try {
        $kf = KfModule::of('http://127.0.0.1:' . $server['port'], $input->slug, TOKEN, $secret, null);
        if (Json::get($input, 'manifest') !== null) $kf->withManifest(Json::encode($input->manifest));
        $ctx = $delivered ? DeliveryContext::of($context, Json::get($input, 'proof'))
            : $kf->verifyContext($context, (string) $now, $signature, '', Json::get($input, 'proof'));
        $args = $input->args;
        $result = null;
        $error = null;
        try {
            $result = serviceCall($id, $area . '.' . $input->call, $kf, $ctx, $args);
        } catch (KfException $e) {
            $error = $e;
        }

        $request = Json::get($expect, 'request');
        if ($request === null) {
            check(requests($server) === 0, "$id: no request may be sent");
        } else {
            check(requests($server) === 1, "$id: exactly one request");
            $wanted = clone $request;
            $wanted->headers = clone $request->headers;
            foreach (get_object_vars($wanted->headers) as $k => $v) {
                if ($v === '$context') $wanted->headers->{$k} = $context;
            }
            same($wanted, Json::decode((string) file_get_contents($server['dir'] . '/request.json')), "$id: request");
        }

        if ($expect->outcome === 'ok') {
            check($error === null, "$id: must succeed, got " . ($error?->getMessage() ?? ''));
            same($expect->result, tree($result), "$id: result");
        } else {
            check($error !== null, "$id: must fail");
            $e = $expect->error;
            check($error->problem()->status === $e->status, "$id: status " . $error->problem()->status);
            check($error->errorClass()->wire() === $e->class, "$id: class");
            $kind = $error instanceof PlatformRefusal ? $error->kind()->wire() : 'other';
            check($kind === $e->kind, "$id: kind $kind");
            if (Json::has($e, 'reason')) check($error instanceof PlatformRefusal && $error->reason() === $e->reason, "$id: reason");
            if (Json::has($e, 'errors')) {
                check($error instanceof PlatformRefusal, "$id: errors on a refusal");
                same($e->errors, tree(Json::obj($error->errors())), "$id: errors");
            }
            foreach (Json::get($e, 'messageContains') ?? [] as $fragment) {
                check(str_contains($error->getMessage(), (string) $fragment), "$id: message " . $error->getMessage());
            }
            check(!str_contains($error->getMessage(), TOKEN), "$id: the token in an error");
            $proof = Json::get($input, 'proof');
            if (is_string($proof)) check(!str_contains($error->getMessage(), $proof), "$id: the proof in an error");
            $value = Json::get($args, 'value');
            if (is_string($value) && $value !== '') check(!str_contains($error->getMessage(), $value), "$id: the secret value in an error");
        }
    } finally {
        stopServer($server);
    }
}

/** The call of service(), by `<area>.<call>`; the value it returns, as the fixtures write it. */
function serviceCall(string $id, string $call, KfModule $kf, RequestContext|DeliveryContext $ctx, \stdClass $args): mixed
{
    switch ($call) {
        case 'peers.read':
            $a = $kf->peers()->read($ctx, $args->owner, $args->port, Json::toArray(Json::get($args, 'query')));
            return ['status' => $a->status, 'body' => $a->body];
        case 'peers.command':
            $body = Json::get($args, 'body');
            $a = $kf->peers()->command($ctx, $args->owner, $args->name, Json::get($args, 'idempotencyKey'),
                $body === null ? null : Json::toArray($body));
            return ['status' => $a->status, 'body' => $a->body];
        case 'events.emit':
            $e = $kf->events()->emit($ctx, $args->name, Json::toArray(Json::get($args, 'data')));
            return ['eventId' => $e->eventId, 'type' => $e->type];
        case 'reads.list':
            $filters = Json::toArray(Json::get($args, 'filters')) ?? [];
            $page = Json::has($args, 'size')
                ? $kf->reads()->list($ctx, $args->port, $filters, $args->page, $args->size)
                : $kf->reads()->list($ctx, $args->port, $filters);
            return ['items' => $page->items, 'page' => $page->page, 'size' => $page->size, 'total' => $page->total];
        case 'reads.get':
            return Json::has($args, 'include')
                ? $kf->reads()->get($ctx, $args->port, $args->id, $args->include)
                : $kf->reads()->get($ctx, $args->port, $args->id);
        case 'settings.effective':
            return $kf->settings()->effective($ctx);
        case 'secrets.write':
            return $kf->secrets()->write($ctx, $args->name, $args->value);
        case 'secrets.resolve':
            return $kf->secrets()->resolve($ctx, $args->ref)->value();
        case 'storage.uploadUrl':
            $u = $kf->storage()->uploadUrl($ctx, $args->contentType, (int) $args->size);
            return ['key' => $u->key, 'url' => $u->url, 'method' => $u->method, 'headers' => $u->headers, 'expiresAt' => $u->expiresAt];
        case 'storage.downloadUrl':
            $d = $kf->storage()->downloadUrl($ctx, $args->key);
            return ['url' => $d->url, 'expiresAt' => $d->expiresAt];
        case 'extensions.write':
            return $kf->extensions()->write($ctx, $args->entity, $args->id, Json::toArray($args->values));
        case 'services.upsert':
            // protocol 2.0 (sdk-protocol.md 16.6): order.services.v1; the amount as text keeps 12.50 exact
            $amount = Json::get($args, 'amount');
            $kf->orderServices()->upsert($ctx, new OrderServices\Line(Json::get($args, 'orderId'), Json::get($args, 'entryId'),
                Json::get($args, 'name'), $amount === null ? null : (string) $amount, Json::get($args, 'currency'),
                Json::get($args, 'externalRef')));
            return null;
        case 'services.delete':
            $kf->orderServices()->delete($ctx, $args->orderId, $args->externalRef);
            return null;
        default:
            throw new Failed("$id: unknown call $call");
    }
}

/**
 * Protocol 2.0 (§6.3): a module event `input.envelope`, signed with `ts` = now, delivered to a
 * module subscribed with `events()->on(input.subscribe.owner, input.subscribe.name)`, with the delivery's `X-KF-Context`
 * (built from `input.context`, `ts` = now) and `input.proof`. `expect.status` is the answer; `expect.handler` what the
 * handler saw: `owner`, `name`, `eventId`, `tenant`, `data` and `deliveryContext` = `{tenant, contextHeader ("$context"
 * = the header sent), proof}` or null when none.
 */
function moduleEvent(string $id, \stdClass $input, \stdClass $expect): void
{
    $now = time();
    $header = null;
    if (Json::get($input, 'context') !== null) {
        $contextJson = clone $input->context;
        $contextJson->ts = $now;
        $header = rtrim(strtr(base64_encode(Json::encode($contextJson)), '+/', '-_'), '=');
    }
    $kf = KfModule::of('http://127.0.0.1:1', 'conformance', TOKEN, SECRET, null);
    $seen = [];
    $kf->events()->on($input->subscribe->owner, $input->subscribe->name, static function (ModuleEvent $ev) use (&$seen): void {
        $seen[] = $ev;
    });
    $bytes = Json::encode($input->envelope);
    $ts = (string) $now;
    $result = $kf->handle($bytes, $ts, Signatures::sign(SECRET, $ts, $bytes), $now, $header, Json::get($input, 'proof'));
    check($result->status === $expect->status, "$id: status {$result->status}; body {$result->body}");
    $handler = Json::get($expect, 'handler');
    if ($handler === null) {
        check($seen === [], "$id: the handler must not be called");
        return;
    }
    check(count($seen) === 1, "$id: the handler was called " . count($seen) . ' times');
    $ev = $seen[0];
    $d = $ev->deliveryContext();
    $actual = [
        'owner' => $ev->owner, 'name' => $ev->name, 'eventId' => $ev->eventId(), 'tenant' => $ev->tenant(),
        'data' => Json::obj($ev->data),
        'deliveryContext' => $d === null ? null : ['tenant' => $d->tenant(), 'contextHeader' => $d->contextHeader, 'proof' => $d->proof],
    ];
    $wanted = clone $handler;
    if (Json::isObject(Json::get($wanted, 'deliveryContext')) && Json::get($wanted->deliveryContext, 'contextHeader') === '$context') {
        $wanted->deliveryContext = clone $wanted->deliveryContext;
        $wanted->deliveryContext->contextHeader = $header;
    }
    same($wanted, tree($actual), "$id: handler");
}

// ---- notifications ---------------------------------------------------------------------------------

/**
 * `Notifications::send` against a platform stub that answers the n-th request with `input.platform[n]` (the last one
 * repeats): how many requests the SDK sends, that EVERY one of them is `expect.request` (method, path, the module
 * token, the body -- a retry sends the same idempotency key), and what it makes of the answer. The runner shortens the
 * retry schedule to three steps of 10 ms (the default has three steps too).
 */
function notifications(string $id, \stdClass $input, \stdClass $expect): void
{
    $answers = [];
    foreach ($input->platform as $a) $answers[] = ['status' => $a->status, 'body' => $a->body];
    $server = sequenced($answers);
    try {
        $kf = KfModule::of('http://127.0.0.1:' . $server['port'], $input->slug, TOKEN, 'conformance-notifications-secret', null);
        $kf->notifications()->withRetryDelays([10, 10, 10]);
        if ($input->call !== 'send') throw new Failed("$id: unknown call {$input->call}");
        $o = Json::get($input, 'options');
        $options = $o === null ? null : new NotificationOptions(
            deepLink: Json::get($o, 'deepLink'),
            channels: Json::get($o, 'channels'),
            idempotencyKey: Json::get($o, 'idempotencyKey'),
            recipientRoles: Json::get($o, 'recipientRoles'),
        );
        $result = null;
        $error = null;
        try {
            $result = $kf->notifications()->send($input->tenantId, $input->key, $input->recipients,
                (array) $input->variables, $options);
        } catch (KfException $e) {
            $error = $e;
        }

        $seen = sequencedRequests($server);
        check(count($seen) === $expect->requests, "$id: requests sent " . count($seen));
        foreach ($seen as $one) same($expect->request, $one, "$id: request");

        if ($expect->outcome === 'ok') {
            check($error === null, "$id: must succeed, got " . ($error?->getMessage() ?? ''));
            check($result->accepted === $expect->result->accepted, "$id: accepted");
            check($result->duplicates === $expect->result->duplicates, "$id: duplicates");
        } else {
            check($error !== null, "$id: must fail");
            $e = $expect->error;
            check($error->problem()->status === $e->status, "$id: status " . $error->problem()->status);
            check($error->errorClass()->wire() === $e->class, "$id: class");
            $kind = $error instanceof NotificationRejected ? 'rejected' : 'other';
            check($kind === $e->kind, "$id: kind $kind");
            if ($error instanceof NotificationRejected) check($error->reason() === Json::get($e, 'reason'), "$id: reason");
            foreach (Json::get($e, 'messageContains') ?? [] as $fragment) {
                check(str_contains($error->getMessage(), (string) $fragment), "$id: message " . $error->getMessage());
            }
            check(!str_contains($error->getMessage(), TOKEN), "$id: the token in an error");
        }
    } finally {
        stopServer($server);
    }
}

/**
 * A platform that answers the n-th request with `$answers[n]` ({status, body}; the last one repeats) and appends every
 * request ({method, path, headers: {Authorization}, body}) to requests.jsonl: `php -S` with a router script.
 *
 * @param list<array{status: int, body: string}> $answers
 */
function sequenced(array $answers): array
{
    $dir = sys_get_temp_dir() . '/kf-sdk-notifications-' . bin2hex(random_bytes(4));
    mkdir($dir);
    file_put_contents($dir . '/answers.json', json_encode($answers, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
    file_put_contents($dir . '/requests.jsonl', '');
    file_put_contents($dir . '/router.php', <<<'PHP'
        <?php
        $dir = __DIR__;
        $raw = file_get_contents('php://input');
        $uri = $_SERVER['REQUEST_URI'];
        $q = strpos($uri, '?');
        $line = json_encode([
            'method' => $_SERVER['REQUEST_METHOD'],
            'path' => $q === false ? $uri : substr($uri, 0, $q),
            'headers' => ['Authorization' => $_SERVER['HTTP_AUTHORIZATION'] ?? null],
            'body' => $raw === '' ? null : json_decode($raw),
        ], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        $fp = fopen($dir . '/requests.jsonl', 'a+');
        flock($fp, LOCK_EX);
        rewind($fp);
        $n = substr_count(stream_get_contents($fp), "\n");
        fwrite($fp, $line . "\n");
        flock($fp, LOCK_UN);
        fclose($fp);
        $answers = json_decode(file_get_contents($dir . '/answers.json'), true);
        $a = $answers[min($n, count($answers) - 1)];
        http_response_code($a['status']);
        header('Content-Type: ' . ($a['status'] >= 400 ? 'application/problem+json' : 'application/json'));
        echo $a['body'];
        PHP);
    $socket = stream_socket_server('tcp://127.0.0.1:0');
    $name = stream_socket_get_name($socket, false);
    fclose($socket);
    $port = (int) substr($name, strrpos($name, ':') + 1);
    $process = proc_open([PHP_BINARY, '-S', '127.0.0.1:' . $port, $dir . '/router.php'], [
        0 => ['pipe', 'r'], 1 => ['file', $dir . '/server.log', 'a'], 2 => ['file', $dir . '/server.log', 'a'],
    ], $pipes, $dir);
    if (!is_resource($process)) throw new \RuntimeException('php -S did not start');
    $deadline = microtime(true) + 10;
    while (($c = @stream_socket_client('tcp://127.0.0.1:' . $port, $errno, $errstr, 0.2)) === false) {
        if (microtime(true) > $deadline) throw new \RuntimeException("php -S did not listen on $port");
        usleep(50_000);
    }
    fclose($c);
    return ['dir' => $dir, 'port' => $port, 'process' => $process, 'pipes' => $pipes];
}

/** The requests a sequenced() platform received, in order (objects as \stdClass). */
function sequencedRequests(array $server): array
{
    $out = [];
    foreach (explode("\n", (string) file_get_contents($server['dir'] . '/requests.jsonl')) as $line) {
        if (trim($line) !== '') $out[] = json_decode($line);
    }
    return $out;
}

/** A platform that answers with `$answer` ({status, body}) and keeps the last request in request.json: `php -S`. */
function recording(\stdClass $answer): array
{
    $dir = sys_get_temp_dir() . '/kf-sdk-audit-' . bin2hex(random_bytes(4));
    mkdir($dir);
    file_put_contents($dir . '/response.json', Json::encode(['status' => $answer->status, 'body' => $answer->body]));
    file_put_contents($dir . '/count', '0');
    file_put_contents($dir . '/router.php', <<<'PHP'
        <?php
        $dir = __DIR__;
        $raw = file_get_contents('php://input');
        $n = (int) file_get_contents($dir . '/count');
        file_put_contents($dir . '/count', (string) ($n + 1));
        $uri = $_SERVER['REQUEST_URI'];
        $q = strpos($uri, '?');
        $seen = [
            'method' => $_SERVER['REQUEST_METHOD'],
            'path' => $q === false ? $uri : substr($uri, 0, $q),
            'query' => $q === false ? null : substr($uri, $q + 1),
            'headers' => [
                'Authorization' => $_SERVER['HTTP_AUTHORIZATION'] ?? null,
                'X-KF-Context' => $_SERVER['HTTP_X_KF_CONTEXT'] ?? null,
                'X-KF-Context-Proof' => $_SERVER['HTTP_X_KF_CONTEXT_PROOF'] ?? null,
            ],
            'body' => $raw === '' ? null : json_decode($raw),
        ];
        // protocol 2.0: a command's key, only when sent
        if (isset($_SERVER['HTTP_IDEMPOTENCY_KEY'])) $seen['headers']['Idempotency-Key'] = $_SERVER['HTTP_IDEMPOTENCY_KEY'];
        file_put_contents($dir . '/request.json', json_encode($seen, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE));
        $r = json_decode(file_get_contents($dir . '/response.json'), true);
        http_response_code($r['status']);
        header('Content-Type: ' . ($r['status'] >= 400 ? 'application/problem+json' : 'application/json'));
        echo $r['body'];
        PHP);
    $port = freePort();
    $process = proc_open([PHP_BINARY, '-S', '127.0.0.1:' . $port, $dir . '/router.php'], [
        0 => ['pipe', 'r'], 1 => ['file', $dir . '/server.log', 'a'], 2 => ['file', $dir . '/server.log', 'a'],
    ], $pipes, $dir);
    if (!is_resource($process)) throw new \RuntimeException('php -S did not start');
    $deadline = microtime(true) + 10;
    while (true) {
        $c = @stream_socket_client('tcp://127.0.0.1:' . $port, $errno, $errstr, 0.2);
        if ($c !== false) {
            fclose($c);
            break;
        }
        if (microtime(true) > $deadline) throw new \RuntimeException("php -S did not listen on $port");
        usleep(50_000);
    }
    return ['dir' => $dir, 'port' => $port, 'process' => $process, 'pipes' => $pipes];
}
