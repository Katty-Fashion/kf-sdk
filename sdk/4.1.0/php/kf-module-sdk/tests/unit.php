<?php // kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/{ManifestPeersTest,ContextViaModuleTest,PeersClientTest,EventsClientTest,AuditClientTest,ContextRouteTest,DescribeErrorTest,LocalManifestCheckTest,ManifestDescriptionTest,ManifestEditableTest,ManifestNavTest,ManifestContract11Test,ManifestContract12Test,ManifestRequiresTest,ManifestSlotsTest,ContextPlatformActorTest,ContextPersonTest,ManifestRollbackFloorTest,NotificationsTest,RegistrationErrorTest,ReadsClientTest,SettingsClientTest,SecretsClientTest,ExtensionsClientTest,VersionExtensionTest,StorageClientTest,TenantHooksTest,TenantDataExportTest,SlotContributionsTest,ManifestOrderKeysTest,FlowRulesTest,FlowEventsTest,OrderServicesTest,ManifestFlowsTest}.java. Do not edit by hand.

/*
 * The SDK's own tests, plain PHP (no phpunit):  php sdk/php/kf-module-sdk/tests/unit.php
 * Exit code 0 only when every test passed.
 */

declare(strict_types=1);

use Kf\Sdk\Actor;
use Kf\Sdk\AuditForbidden;
use Kf\Sdk\AuditRecord;
use Kf\Sdk\AuditUnauthorized;
use Kf\Sdk\ErrorClass;
use Kf\Sdk\HookEvents;
use Kf\Sdk\HookResult;
use Kf\Sdk\KfException;
use Kf\Sdk\Json;
use Kf\Sdk\KfModule;
use Kf\Sdk\Log;
use Kf\Sdk\Manifest;
use Kf\Sdk\ManifestCheck;
use Kf\Sdk\PlatformRefusal;
use Kf\Sdk\PlatformRefusalKind;
use Kf\Sdk\ReadsClient;
use Kf\Sdk\RequestContext;
use Kf\Sdk\Sapi;
use Kf\Sdk\SdkVersion;
use Kf\Sdk\Signatures;

spl_autoload_register(static function (string $class): void {
    if (!str_starts_with($class, 'Kf\\Sdk\\')) return;
    $file = __DIR__ . '/../src/' . str_replace('\\', '/', substr($class, strlen('Kf\\Sdk\\'))) . '.php';
    if (is_file($file)) require_once $file;
});

$lines = [];
Log::use(static function (string $level, string $line) use (&$lines): void {
    $lines[] = $line;
});

function check(bool $condition, string $message): void
{
    if (!$condition) throw new \RuntimeException($message);
}

$tests = [];
$test = static function (string $name, callable $body) use (&$tests): void {
    $tests[$name] = $body;
};

// ---- AuditClientTest --------------------------------------------------------------------------------------------
// AuditClient against a platform stub: the module token, the context and the platform's proof of it go back
// unchanged; the record is checked locally first; 401 / 403 map to AuditUnauthorized / AuditForbidden.

const AUDIT_SECRET = 'audit-secret';
const AUDIT_TOKEN = 'kfm_audit-token';
define('AUDIT_PROOF', str_repeat('f', 64));

/** A platform stub (`php -S` with a router): answers with answer.json, keeps the last request in request.json. */
$auditPlatform = static function (): array {
    $dir = sys_get_temp_dir() . '/kf-sdk-audit-unit-' . bin2hex(random_bytes(4));
    mkdir($dir);
    file_put_contents($dir . '/answer.json', json_encode(['status' => 201, 'body' => '{"aggregateType":"m.audit-mod.Note"}']));
    file_put_contents($dir . '/count', '0');
    file_put_contents($dir . '/router.php', <<<'PHP'
        <?php
        $dir = __DIR__;
        $raw = file_get_contents('php://input');
        file_put_contents($dir . '/count', (string) ((int) file_get_contents($dir . '/count') + 1));
        file_put_contents($dir . '/request.json', json_encode([
            'method' => $_SERVER['REQUEST_METHOD'], 'url' => $_SERVER['REQUEST_URI'],
            'authorization' => $_SERVER['HTTP_AUTHORIZATION'] ?? null, 'context' => $_SERVER['HTTP_X_KF_CONTEXT'] ?? null,
            'proof' => $_SERVER['HTTP_X_KF_CONTEXT_PROOF'] ?? null, 'body' => $raw,
        ], JSON_UNESCAPED_SLASHES));
        $a = json_decode(file_get_contents($dir . '/answer.json'), true);
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
    $deadline = microtime(true) + 10;
    while (($c = @stream_socket_client('tcp://127.0.0.1:' . $port, $errno, $errstr, 0.2)) === false) {
        if (microtime(true) > $deadline) throw new \RuntimeException("php -S did not listen on $port");
        usleep(50_000);
    }
    fclose($c);
    $kf = KfModule::of('http://127.0.0.1:' . $port, 'audit-mod', AUDIT_TOKEN, AUDIT_SECRET, null);
    $stop = static function () use ($process, $pipes, $dir): void {
        foreach ($pipes as $p) @fclose($p);
        $status = proc_get_status($process);
        if (PHP_OS_FAMILY === 'Windows' && $status['running']) exec('taskkill /F /T /PID ' . (int) $status['pid'] . ' 2>NUL');
        else proc_terminate($process);
        proc_close($process);
        foreach (scandir($dir) as $f) if ($f !== '.' && $f !== '..') @unlink($dir . '/' . $f);
        @rmdir($dir);
    };
    $answer = static fn (int $status, string $body) => file_put_contents($dir . '/answer.json', json_encode(['status' => $status, 'body' => $body]));
    $requests = static fn (): int => (int) file_get_contents($dir . '/count');
    $last = static fn (): array => json_decode((string) file_get_contents($dir . '/request.json'), true);
    return [$kf, $stop, $answer, $requests, $last];
};

/** A context as the platform forwards it, verified through the module (which keeps it with its proof). */
$auditVerified = static function (KfModule $kf, string $requestId, ?string $proof): RequestContext {
    $ts = time();
    $json = '{"v":1,"tenant":"00000000-0000-0000-0000-00000000000a","user":"sub-1","locale":"en","rights":[],"method":"POST",'
        . '"path":"/notes","query":null,"ts":' . $ts . ',"requestId":"' . $requestId . '"}';
    $context = rtrim(strtr(base64_encode($json), '+/', '-_'), '=');
    $signature = Signatures::sign(AUDIT_SECRET, (string) $ts, $context . '.');
    return $kf->verifyContext($context, (string) $ts, $signature, '', $proof);
};

$test('record: POST …/audit-mod/audit with the token, the context and the proof unchanged, the record as JSON',
    static function () use ($auditPlatform, $auditVerified): void {
        [$kf, $stop, , $requests, $last] = $auditPlatform();
        try {
            $kf->audit()->record($auditVerified($kf, 'req-1', AUDIT_PROOF), new AuditRecord('NOTE_CREATED', 'Note', '42'));
            $r = $last();
            check($requests() === 1, 'one request');
            check($r['method'] === 'POST', $r['method']);
            check($r['url'] === '/api/v1/platform/modules/audit-mod/audit', $r['url']);
            check($r['authorization'] === 'Bearer ' . AUDIT_TOKEN, 'token');
            check($r['proof'] === AUDIT_PROOF, 'proof');
            check(str_contains((string) base64_decode(strtr((string) $r['context'], '-_', '+/')), '"requestId":"req-1"'), 'context');
            check(json_decode($r['body'], true) === ['action' => 'NOTE_CREATED', 'aggregateType' => 'Note', 'aggregateRef' => '42'], $r['body']);
        } finally {
            $stop();
        }
    });

$test('list: GET …/audit?aggregateType&aggregateRef, the rows of the answer', static function () use ($auditPlatform, $auditVerified): void {
    [$kf, $stop, $answer, , $last] = $auditPlatform();
    try {
        $ctx = $auditVerified($kf, 'req-2', AUDIT_PROOF);
        $answer(200, '{"rows":[{"id":7,"aggregateType":"Note","aggregateRef":"42","action":"NOTE_CREATED","actor":"sub-1",'
            . '"performedAt":"2026-10-20T10:00:00Z","requestId":"req-1"}]}');
        $rows = $kf->audit()->list($ctx, 'Note', '4 2');
        check($last()['method'] === 'GET', 'GET');
        check($last()['url'] === '/api/v1/platform/modules/audit-mod/audit?aggregateType=Note&aggregateRef=4+2', $last()['url']);
        check($last()['proof'] === AUDIT_PROOF, 'proof');
        check(count($rows) === 1 && $rows[0]['action'] === 'NOTE_CREATED', json_encode($rows));
        $kf->audit()->list($ctx, 'Note', null);
        check($last()['url'] === '/api/v1/platform/modules/audit-mod/audit?aggregateType=Note', $last()['url']);
    } finally {
        $stop();
    }
});

$test('an invalid record is refused locally, permanent, every problem named, no request',
    static function () use ($auditPlatform, $auditVerified): void {
        [$kf, $stop, , $requests] = $auditPlatform();
        try {
            $ctx = $auditVerified($kf, 'req-3', AUDIT_PROOF);
            try {
                $kf->audit()->record($ctx, new AuditRecord('note.created', 'm/x', ' '));
                check(false, 'record must throw');
            } catch (KfException $e) {
                check($e->errorClass() === ErrorClass::PERMANENT, 'class');
                foreach (['action must match', 'aggregateType must match', 'aggregateRef is required'] as $f) {
                    check(str_contains($e->getMessage(), $f), $e->getMessage());
                }
            }
            check($requests() === 0, 'no request may be sent');
        } finally {
            $stop();
        }
    });

$test("a context not verified by this module, or verified without the platform's proof: local refusal",
    static function () use ($auditPlatform, $auditVerified): void {
        [$kf, $stop, , $requests] = $auditPlatform();
        try {
            $built = new RequestContext(1, 't', 'u', Actor::user(), 'en', [], 'POST', '/n', null, time(), 'never-verified');
            try {
                $kf->audit()->record($built, new AuditRecord('NOTE_CREATED', 'Note', '42'));
                check(false, 'must throw');
            } catch (KfException $e) {
                check(str_contains($e->getMessage(), 'was not verified by this module'), $e->getMessage());
            }
            $withoutProof = $auditVerified($kf, 'req-4', null);
            try {
                $kf->audit()->list($withoutProof, 'Note', null);
                check(false, 'must throw');
            } catch (KfException $e) {
                check(str_contains($e->getMessage(), 'X-KF-Context-Proof') && $e->errorClass() === ErrorClass::PERMANENT, $e->getMessage());
            }
            check($requests() === 0, 'no request may be sent');
        } finally {
            $stop();
        }
    });

$test('403 permanent from the platform -> AuditForbidden; 401 -> AuditUnauthorized',
    static function () use ($auditPlatform, $auditVerified): void {
        [$kf, $stop, $answer] = $auditPlatform();
        try {
            $ctx = $auditVerified($kf, 'req-5', AUDIT_PROOF);
            $answer(403, '{"type":"https://kf.ro/problems/capability-not-declared","title":"Capability not declared","status":403,'
                . '"detail":"The active version of audit-mod does not declare capabilities.audit","class":"permanent"}');
            try {
                $kf->audit()->record($ctx, new AuditRecord('NOTE_CREATED', 'Note', '42'));
                check(false, 'must throw');
            } catch (KfException $e) {
                check($e instanceof AuditForbidden, $e::class);
                check($e->errorClass() === ErrorClass::PERMANENT && $e->problem()->status === 403, 'class/status');
            }
            $answer(401, '{"type":"https://kf.ro/problems/invalid-context","title":"Invalid context","status":401,"class":"permanent"}');
            try {
                $kf->audit()->list($ctx, 'Note', null);
                check(false, 'must throw');
            } catch (KfException $e) {
                check($e instanceof AuditUnauthorized, $e::class);
            }
            $answer(503, '');
            try {
                $kf->audit()->list($ctx, 'Note', null);
                check(false, 'must throw');
            } catch (KfException $e) {
                check($e->errorClass() === ErrorClass::TRANSIENT && !($e instanceof AuditForbidden) && !($e instanceof AuditUnauthorized), $e::class);
            }
        } finally {
            $stop();
        }
    });

$test('the token never appears in an error', static function () use ($auditPlatform, $auditVerified): void {
    [$kf, $stop, $answer] = $auditPlatform();
    try {
        $ctx = $auditVerified($kf, 'req-6', AUDIT_PROOF);
        $answer(403, '{"status":403,"class":"permanent"}');
        try {
            $kf->audit()->record($ctx, new AuditRecord('NOTE_CREATED', 'Note', '42'));
            check(false, 'must throw');
        } catch (KfException $e) {
            check(!str_contains($e->getMessage(), AUDIT_TOKEN) && !str_contains($e->getMessage(), AUDIT_PROOF), $e->getMessage());
        }
    } finally {
        $stop();
    }
});

// ---- ContextRouteTest -------------------------------------------------------------------------------------------
// The adapter of the module's own routes: only a context signed for this method and path reaches the handler.

$routeSecret = 'route-secret';
$route = static function (string $path, string $contextPath, bool $signed, ?RequestContext &$seen) use ($routeSecret): HookResult {
    $kf = KfModule::of(null, 'route-mod', null, $routeSecret, null);
    $ts = time();
    $json = json_encode(['v' => 1, 'tenant' => 't-1', 'user' => 'sub-1', 'locale' => 'ro', 'rights' => [], 'method' => 'GET',
        'path' => $contextPath, 'query' => null, 'ts' => $ts, 'requestId' => 'r-1'], JSON_UNESCAPED_SLASHES);
    $context = rtrim(strtr(base64_encode($json), '+/', '-_'), '=');
    $headers = $signed ? ['X-KF-Context' => $context, 'X-KF-Timestamp' => (string) $ts,
        'X-KF-Signature' => Signatures::sign($routeSecret, (string) $ts, $context . '.')] : [];
    return Sapi::route($kf, 'GET', $path, $headers, '', static function (RequestContext $ctx) use (&$seen): HookResult {
        $seen = $ctx;
        return new HookResult(200, json_encode(['tenant' => $ctx->tenant]));
    });
};

$test('a context signed for GET /events reaches the handler, actor user', static function () use ($route): void {
    $seen = null;
    $result = $route('/events', '/events', true, $seen);
    check($result->status === 200, $result->body);
    check($seen->tenant === 't-1', 'tenant');
    check($seen->actor->type === 'user', 'actor');
});

$test('no context: 401 RFC 9457, class permanent, the handler is not called', static function () use ($route): void {
    $seen = null;
    $result = $route('/events', '/events', false, $seen);
    check($result->status === 401, (string) $result->status);
    check(str_starts_with($result->contentType, 'application/problem+json'), $result->contentType);
    check(str_contains($result->body, '"class":"permanent"'), $result->body);
    check($seen === null, 'the handler must not be called');
});

$test('a context signed for another path is refused', static function () use ($route): void {
    $seen = null;
    $result = $route('/events', '/other', true, $seen);
    check($result->status === 401, $result->body);
    check($seen === null, 'the handler must not be called');
});

// ---- DescribeErrorTest ------------------------------------------------------------------------------------------
// KfModule::describe: the one place a registration failure becomes a log line.

$test('describe: an empty message gives way to the cause type, duplicates collapsed', static function (): void {
    $refused = new \RuntimeException('', 0, new \RuntimeException('', 0, new \LogicException('')));
    check(KfModule::describe($refused) === 'RuntimeException (cause: LogicException)', KfModule::describe($refused));
});

$test('describe: cause messages are kept when they exist', static function (): void {
    $e = new \RuntimeException('', 0, new \RuntimeException('Connection refused'));
    check(KfModule::describe($e) === 'RuntimeException (cause: RuntimeException: Connection refused)', KfModule::describe($e));
});

$test('describe: an exception with a message and no cause is unchanged', static function (): void {
    $e = new \RuntimeException('HTTP connect timed out');
    check(KfModule::describe($e) === 'RuntimeException: HTTP connect timed out', KfModule::describe($e));
});

// ---- LocalManifestCheckTest -------------------------------------------------------------------------------------
// registerWithRetry() checks kf-module-conf.json locally before the first request: a violation is a permanent error,
// nothing is sent.

$test('hookable on a GET route stops the module before the first request, naming the route', static function (): void {
    $manifest = tempnam(sys_get_temp_dir(), 'kf-module-conf');
    file_put_contents($manifest, json_encode(['schemaVersion' => 2, 'slug' => 'local-check', 'version' => '1.0.0', 'platform' => '>=4.1',
        'name' => 'module.name', 'i18n' => ['en' => ['module.name' => 'Local check']],
        'capabilities' => ['routes' => [['method' => 'GET', 'path' => '/m/local-check/events', 'right' => 'api.v1.m.local-check.events.get',
            'hookable' => true]]]], JSON_UNESCAPED_SLASHES));
    $state = sys_get_temp_dir() . '/kf-sdk-local-check-' . bin2hex(random_bytes(4));
    // the platform URL points at a closed port: a request would be a transient network error, not this permanent one
    $kf = KfModule::fromEnv(['KF_PLATFORM_URL' => 'http://127.0.0.1:1', 'KF_MODULE_SLUG' => 'local-check', 'KF_MODULE_TOKEN' => 'kfm_local',
        'KF_HOOK_SECRET' => 's', 'KF_MODULE_MANIFEST' => $manifest, 'KF_SDK_STATE_DIR' => $state]);
    try {
        $kf->registerWithRetry(1);
        check(false, 'the registration must be refused');
    } catch (KfException $e) {
        check($e->problem()->status === 0, 'nothing may be sent');
    }
    check($kf->isRegistrationStopped(), 'stopped');
    check($kf->lastRegistrationProblem()->errorClass === ErrorClass::PERMANENT, 'class');
    $error = (string) $kf->lastRegistrationError();
    check(str_contains($error, 'GET /m/local-check/events'), $error);
    check(str_contains($error, 'hookable only on POST, PUT or PATCH'), $error);
    @unlink($manifest);
});

$test('without a manifest file the local check is skipped (warning), the registration proceeds', static function (): void {
    $kf = KfModule::fromEnv(['KF_MODULE_MANIFEST' => 'does-not-exist/kf-module-conf.json',
        'KF_SDK_STATE_DIR' => sys_get_temp_dir() . '/kf-sdk-no-manifest-' . bin2hex(random_bytes(4))]);
    check($kf->manifest() === null, 'no manifest');
    try {
        $kf->registerWithRetry(1); // no platform env: refused as not configured, not as a manifest violation
    } catch (KfException $e) {
        check(str_contains($e->getMessage(), 'KF_PLATFORM_URL'), $e->getMessage());
    }
    check(!$kf->isRegistrationStopped(), 'not stopped');
});

$test('SdkVersion carries the composer version and the manifest schemaVersion 3 (protocol 2.0)', static function (): void {
    check(preg_match('/^[0-9]+[.][0-9]+[.][0-9]+/', SdkVersion::sdkVersion()) === 1, SdkVersion::sdkVersion());
    check(SdkVersion::PROTOCOL_SCHEMA_VERSION === 3, 'schemaVersion');
});

// ---- ManifestDescriptionTest ------------------------------------------------------------------------------------
// The optional top-level description of kf-module-conf.json: the same shape as
// name — under schemaVersion 2 a translation key defined in i18n.en, absent (or null) when the module has none. The
// local check refuses a key that is malformed, blank or not defined in en, like the platform at upload.

$described = static function (?string $value): string {
    return '{"schemaVersion":2,"slug":"desc-mod","version":"1.0.0","platform":">=4.1","name":"module.name"'
        . ($value === null ? '' : ',"description":' . $value) . ','
        . '"i18n":{"en":{"module.name":"Description","module.description":"Adds an author tab"},'
        . '"ro":{"module.description":"Adaugă un tab de autor"}}}';
};

$test('a description key defined in en is read and accepted', static function () use ($described): void {
    $m = Manifest::parse($described('"module.description"'));
    check($m->description === 'module.description', 'description');
    check($m->enText($m->description) === 'Adds an author tab', 'en text');
    check(ManifestCheck::validate($described('"module.description"'))->ok(), 'valid');
});

$test('an absent or null description is legal and reads null', static function () use ($described): void {
    check(ManifestCheck::validate($described(null))->ok(), 'absent');
    check(ManifestCheck::validate($described('null'))->ok(), 'null');
    check(Manifest::parse($described(null))->description === null, 'absent reads null');
    check(Manifest::parse($described('null'))->description === null, 'null reads null');
});

$test('a description that is not a key of en is refused, naming the description', static function () use ($described): void {
    $cases = [
        ['"module.missing"', '"description" "module.missing" is not defined in i18n.en'],
        ['"Adds an author tab"', '"description" "Adds an author tab" is not a translation key'],
        ['""', '"description" is required: a translation key defined in i18n.en'],
    ];
    foreach ($cases as [$value, $message]) {
        $result = ManifestCheck::validate($described($value));
        check(!$result->ok(), $value);
        check(count($result->errors) === 1, $value . ': ' . json_encode($result->errors));
        check(str_contains($result->errors[0], $message), $result->errors[0]);
    }
});

// ---- ManifestRollbackFloorTest ----------------------------------------------------------------------------------
// The optional top-level rollbackFloor of kf-module-conf.json: the oldest version of the
// module that still runs on the data (the module's own database) this version leaves behind. A semver at or below
// version; absent (or null) when the module declares none. The SDK reads it on the typed manifest and the local check
// refuses a value that is not semver or is above version, naming the key.

$floored = static function (string $version, ?string $rollbackFloor): string {
    return '{"schemaVersion":2,"slug":"floor-mod","version":' . json_encode($version)
        . ',"platform":">=4.1","name":"module.name"'
        . ($rollbackFloor === null ? '' : ',"rollbackFloor":' . $rollbackFloor) . ','
        . '"i18n":{"en":{"module.name":"Rollback floor"}}}';
};

$test('a rollbackFloor below version is read and accepted', static function () use ($floored): void {
    $json = $floored('1.2.0', '"1.1.0"');
    $result = ManifestCheck::validate($json);
    check($result->ok(), json_encode($result->errors));
    check($result->warnings === [], json_encode($result->warnings));
    check(Manifest::parse($json)->rollbackFloor === '1.1.0', 'rollbackFloor');
});

$test('a rollbackFloor equal to version is accepted: no older version runs on this data', static function () use ($floored): void {
    $json = $floored('1.2.0', '"1.2.0"');
    check(ManifestCheck::validate($json)->ok(), 'valid');
    check(Manifest::parse($json)->rollbackFloor === '1.2.0', 'rollbackFloor');
});

$test('an absent or null rollbackFloor is legal and reads null', static function () use ($floored): void {
    check(ManifestCheck::validate($floored('1.2.0', null))->ok(), 'absent');
    check(ManifestCheck::validate($floored('1.2.0', 'null'))->ok(), 'null');
    check(Manifest::parse($floored('1.2.0', null))->rollbackFloor === null, 'absent reads null');
    check(Manifest::parse($floored('1.2.0', 'null'))->rollbackFloor === null, 'null reads null');
});

$test('a rollbackFloor above version is refused, naming the key and both versions', static function () use ($floored): void {
    $cases = [
        ['1.2.0', '"1.3.0"', '"rollbackFloor" "1.3.0" is above "version" "1.2.0"'],
        ['1.9.0', '"1.10.0"', '"rollbackFloor" "1.10.0" is above "version" "1.9.0"'],
        ['1.2.0-rc.1', '"1.2.0"', '"rollbackFloor" "1.2.0" is above "version" "1.2.0-rc.1"'],
    ];
    foreach ($cases as [$version, $value, $message]) {
        $result = ManifestCheck::validate($floored($version, $value));
        check(!$result->ok(), $value);
        check(count($result->errors) === 1, $value . ': ' . json_encode($result->errors));
        check(str_contains($result->errors[0], $message), $result->errors[0]);
    }
    // semver order, not text order: 1.10.0 is above 1.9.0, so 1.9.0 is a legal floor of 1.10.0
    check(ManifestCheck::validate($floored('1.10.0', '"1.9.0"'))->ok(), '1.9.0 below 1.10.0');
});

$test('a rollbackFloor that is not a semver 2.0 version is refused, naming the key', static function () use ($floored): void {
    $cases = [
        ['"one"', '"rollbackFloor" must be a semver 2.0 version, e.g. "1.1.0" (got "one")'],
        ['"1.1"', '"rollbackFloor" must be a semver 2.0 version, e.g. "1.1.0" (got "1.1")'],
        ['""', '"rollbackFloor" must be a semver 2.0 version, e.g. "1.1.0" (got "")'],
        ['110', '"rollbackFloor" must be a semver 2.0 version, e.g. "1.1.0" (got 110)'],
    ];
    foreach ($cases as [$value, $message]) {
        $result = ManifestCheck::validate($floored('1.2.0', $value));
        check(!$result->ok(), $value);
        check(count($result->errors) === 1, $value . ': ' . json_encode($result->errors));
        check(str_contains($result->errors[0], $message), $result->errors[0]);
    }
    check(Manifest::parse($floored('1.2.0', '110'))->rollbackFloor === null, 'a number reads null');
});

// ---- ManifestNavTest ---------------------------------------------------------------------------------------------
// The capability nav of kf-module-conf.json (protocol 1.1, additive): the module's menu
// entries, on the staff sidebar, the customer portal menu or the platform menu. The SDK reads them on the typed
// manifest and refuses locally what it can see without the platform's catalogs: the shape, the keys, the url, the
// label key, a right that is not one of the module's own routes, a right on the customer surface, an undeclared flag.
// The groups of a surface and the platform's codes stay the platform's check at upload.

$navManifest = static function (?string $nav): string {
    return '{"schemaVersion":2,"slug":"nav-mod","version":"1.0.0","platform":">=4.1","name":"module.name",'
        . '"featureFlags":[{"key":"beta-menu","label":"flag.beta","default":false}],'
        . '"i18n":{"en":{"module.name":"Nav module","nav.events":"Events","flag.beta":"Beta menu"}},'
        . '"capabilities":{"routes":[{"method":"GET","path":"/m/nav-mod/events","right":"api.v1.m.nav-mod.events.get"}]'
        . ($nav === null ? '' : ',"nav":' . $nav) . '}}';
};
$navEntry = static function (string $surface, string $extra): string {
    return '{"surface":"' . $surface . '","group":"admin-utils","key":"events","label":"nav.events","url":"/modules"' . $extra . '}';
};
$navErrors = static function (?string $nav) use ($navManifest): array {
    return ManifestCheck::validate($navManifest($nav))->errors;
};
$navRefused = static function (string $nav, string $expected) use ($navErrors): void {
    $errors = $navErrors($nav);
    check($errors !== [], $nav);
    $found = false;
    foreach ($errors as $e) $found = $found || str_contains($e, $expected);
    check($found, $expected . ' not in ' . json_encode($errors));
};

$test("a valid staff entry with the right of one of the module's routes is read and accepted", static function () use ($navManifest, $navEntry): void {
    $json = $navManifest('[' . $navEntry('staff', ',"icon":"Boxes","right":"api.v1.m.nav-mod.events.get","order":20,"flag":"beta-menu"') . ']');
    $result = ManifestCheck::validate($json);
    check($result->ok(), json_encode($result->errors));
    $nav = Manifest::parse($json)->capabilities->nav[0];
    check($nav->surface === 'staff', 'surface');
    check($nav->group === 'admin-utils', 'group');
    check($nav->key === 'events', 'key');
    check($nav->label === 'nav.events', 'label');
    check($nav->url === '/modules', 'url');
    check($nav->icon === 'Boxes', 'icon');
    check($nav->right === 'api.v1.m.nav-mod.events.get', 'right');
    check($nav->order === 20, 'order');
    check($nav->flag === 'beta-menu', 'flag');
});

$test("a platform code as right is accepted locally (whether it is declared is the platform's check)", static function () use ($navErrors, $navEntry): void {
    check($navErrors('[' . $navEntry('staff', ',"right":"api.v1.customers.get"') . ']') === [], 'platform code');
});

$test('nav absent reads as [], and the optional keys read as their defaults', static function () use ($navManifest, $navEntry): void {
    check(ManifestCheck::validate($navManifest(null))->ok(), 'absent is valid');
    check(Manifest::parse($navManifest(null))->capabilities->nav === [], 'absent reads []');
    $nav = Manifest::parse($navManifest('[' . $navEntry('platform', '') . ']'))->capabilities->nav[0];
    check($nav->icon === null, 'icon');
    check($nav->right === null, 'right');
    check($nav->flag === null, 'flag');
    check($nav->order === 100, 'order');
    check(in_array('nav', Manifest::CAPABILITY_KEYS, true), 'known capability');
});

$test('an unknown surface is refused, naming the three surfaces', static function () use ($navRefused, $navEntry): void {
    $navRefused('[' . $navEntry('sidebar', '') . ']',
        'capabilities.nav[0].surface must be one of staff, customer, platform (got "sidebar")');
});

$test('the same key twice on one surface is refused; the same key on two surfaces is legal', static function () use ($navRefused, $navErrors, $navEntry): void {
    $navRefused('[' . $navEntry('staff', '') . ',' . $navEntry('staff', '') . ']',
        'capabilities.nav[1].key "events" is declared twice on the surface staff');
    check($navErrors('[' . $navEntry('staff', '') . ',' . $navEntry('platform', '') . ']') === [], 'two surfaces');
});

$test('a url that is not an in-app path is refused: //, .., a scheme, a query, %', static function () use ($navRefused): void {
    foreach (['//evil.example/x', '/a/../setup', 'https://evil.example', '/a?x=1', '/a%2e', 'modules'] as $url) {
        $navRefused('[{"surface":"staff","group":"admin-utils","key":"events","label":"nav.events","url":"' . $url . '"}]',
            'capabilities.nav[0].url must be an in-app path');
    }
});

$test('a label that is not a key of i18n.en is refused', static function () use ($navRefused): void {
    $navRefused('[{"surface":"staff","group":"admin-utils","key":"events","label":"nav.missing","url":"/modules"}]',
        'capabilities.nav[0].label "nav.missing" is not defined in i18n.en');
});

$test('a right on the customer surface is refused: the customer menu is filtered on identity', static function () use ($navRefused, $navEntry): void {
    $navRefused('[' . $navEntry('customer', ',"right":"api.v1.m.nav-mod.events.get"') . ']',
        'capabilities.nav[0].right is not allowed on the customer surface');
});

$test("a module right that is not one of the module's routes is refused", static function () use ($navRefused, $navEntry): void {
    $navRefused('[' . $navEntry('staff', ',"right":"api.v1.m.nav-mod.secret.get"') . ']',
        'capabilities.nav[0].right "api.v1.m.nav-mod.secret.get" is not the right of a route this module declares');
    $navRefused('[' . $navEntry('staff', ',"right":"api.v1.m.other-mod.events.get"') . ']',
        'capabilities.nav[0].right "api.v1.m.other-mod.events.get" is not the right of a route this module declares');
});

$test('an undeclared flag, an order out of range, a bad group or key, an unknown key are refused', static function () use ($navRefused, $navEntry): void {
    $navRefused('[' . $navEntry('staff', ',"flag":"nope"') . ']',
        'capabilities.nav[0].flag "nope" is not a feature flag of this module');
    $navRefused('[' . $navEntry('staff', ',"order":10001') . ']',
        'capabilities.nav[0].order must be an integer from 0 to 10000');
    $navRefused('[' . $navEntry('staff', ',"icon":"<svg>"') . ']', 'capabilities.nav[0].icon must be');
    $navRefused('[{"surface":"staff","group":"Admin Utils","key":"events","label":"nav.events","url":"/modules"}]',
        'capabilities.nav[0].group must match');
    $navRefused('[{"surface":"staff","group":"admin-utils","key":"m.events","label":"nav.events","url":"/modules"}]',
        'capabilities.nav[0].key must match');
    $navRefused('[' . $navEntry('staff', ',"target":"_blank"') . ']',
        'capabilities.nav[0]: "target" is not a key of a nav entry');
    $navRefused('{}', 'capabilities.nav must be an array');
});

// ---- ManifestContract11Test -------------------------------------------------------------------------------------
// The keys added to kf-module-conf.json in protocol 1.1 (additive, no new schemaVersion):
// settings (closed scalars only: the rule "settings carry no data"), routes[].admin, secrets (by reference), provides,
// surfaces[].page, and the grammar of a port id (one optional sub-segment). The SDK reads them on the typed
// manifest and refuses locally what it can see without the platform's catalogs (the published and provided ports, the
// surface types: the platform's check at upload). A 1.0 manifest without the new keys stays
// accepted unchanged.

const C11_BASE_ROUTES = '{"method":"GET","path":"/m/c11/stations","right":"api.v1.m.c11.stations.get"},'
    . '{"method":"GET","path":"/m/c11/admin/config","right":"api.v1.m.c11.admin.config.get","admin":"tenant"},'
    . '{"method":"PUT","path":"/m/c11/admin/keys","right":"api.v1.m.c11.admin.keys.put","admin":"platform"},'
    . '{"method":"PUT","path":"/m/c11/admin/tenant-keys","right":"api.v1.m.c11.admin.tenant-keys.put","admin":"tenant"}';
const C11_I18N = '"i18n":{"en":{"module.name":"Contract 1.1","setting.push":"Push on save","setting.timeout":"Timeout",'
    . '"setting.mode":"Mode","setting.mode.help":"How the module pushes","tab.label":"AAS"}}';
const C11_VALID_SETTINGS = '"settings":['
    . '{"key":"push-on-save","type":"BOOLEAN","default":false,"label":"setting.push"},'
    . '{"key":"timeout-seconds","type":"NUMBER","default":30,"min":5,"max":600,"label":"setting.timeout"},'
    . '{"key":"mode","type":"ENUM","default":"auto","values":["auto","manual"],"label":"setting.mode",'
    . '"description":"setting.mode.help"}]';
const C11_VALID_SECRETS = '"secrets":['
    . '{"name":"r3-client-secret","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put"},'
    . '{"name":"tenant-token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put"}]';
const C11_VALID_PROVIDES = '"provides":[{"port":"factory.stations.v1","path":"/m/c11/stations"}]';
const C11_VALID_SURFACES = '"surfaces":[{"slug":"module.page","kind":"react","label":"tab.label","page":"shells"}]';
const C11_VALID_READS = '"reads":[{"port":"model.v1"},{"port":"file.public-url.v1"}]';

$c11ManifestWith = static function (string $routes, ?string $extra): string {
    return '{"schemaVersion":2,"slug":"c11","version":"1.0.0","platform":">=4.1","name":"module.name",' . C11_I18N . ','
        . '"capabilities":{"routes":[' . $routes . ']' . ($extra === null ? '' : ',' . $extra) . '}}';
};
$c11Manifest = static function (?string $extra) use ($c11ManifestWith): string {
    return $c11ManifestWith(C11_BASE_ROUTES, $extra);
};
$c11Errors = static function (string $json): array {
    return ManifestCheck::validate($json)->errors;
};
$c11Refused = static function (string $json, string $expected) use ($c11Errors): void {
    $errors = $c11Errors($json);
    check($errors !== [], $json);
    $found = false;
    foreach ($errors as $e) $found = $found || str_contains($e, $expected);
    check($found, $expected . ' not in ' . json_encode($errors));
};

$test('a manifest that uses every 1.1 key of correctly is accepted and read', static function () use ($c11Manifest): void {
    $json = $c11Manifest(implode(',', [C11_VALID_SETTINGS, C11_VALID_SECRETS, C11_VALID_PROVIDES, C11_VALID_SURFACES, C11_VALID_READS]));
    $result = ManifestCheck::validate($json);
    check($result->ok(), json_encode($result->errors));
    $caps = Manifest::parse($json)->capabilities;
    check(count($caps->settings) === 3, 'three settings');
    $timeout = $caps->settings[1];
    check($timeout->key === 'timeout-seconds', 'key');
    check($timeout->type === 'NUMBER', 'type');
    check($timeout->defaultValue === 30, 'default');
    check($timeout->min === 5, 'min');
    check($timeout->max === 600, 'max');
    $mode = $caps->settings[2];
    check($mode->values === ['auto', 'manual'], 'values');
    check($mode->description === 'setting.mode.help', 'description');
    check($caps->settings[0]->defaultValue === false, 'boolean default');
    $secret = $caps->secrets[0];
    check($secret->name === 'r3-client-secret' && $secret->scope === 'platform'
        && $secret->writeRight === 'api.v1.m.c11.admin.keys.put', 'secret');
    check($caps->provides[0]->port === 'factory.stations.v1' && $caps->provides[0]->path === '/m/c11/stations', 'provides');
    check($caps->surfaces[0]->page === 'shells', 'page');
    check($caps->routes[0]->admin === null, 'admin absent');
    check($caps->routes[1]->admin === 'tenant', 'admin tenant');
    check($caps->routes[2]->admin === 'platform', 'admin platform');
});

$test('a 1.0 manifest (none of the new keys) is accepted unchanged; the new lists read as []', static function () use ($c11ManifestWith, $c11Errors): void {
    $json = $c11ManifestWith('{"method":"POST","path":"/m/c11/notes","right":"api.v1.m.c11.notes.post","roles":["QC"],"hookable":true}',
        '"surfaces":[{"slug":"models.detail.tabs","kind":"react","label":"tab.label"}]');
    check(ManifestCheck::validate($json)->ok(), json_encode($c11Errors($json)));
    $caps = Manifest::parse($json)->capabilities;
    check($caps->settings === [], 'settings []');
    check($caps->secrets === [], 'secrets []');
    check($caps->provides === [], 'provides []');
    check($caps->surfaces[0]->page === null, 'page null');
    check($caps->routes[0]->admin === null, 'admin null');
});

$test('a STRING setting is refused: settings carry no data', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"settings":[{"key":"base-url","type":"STRING","default":"https://x","label":"setting.push"}]'),
        'settings carry no data: BOOLEAN, NUMBER or ENUM');
});

$test('an ENUM setting without values is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"settings":[{"key":"mode","type":"ENUM","default":"auto","label":"setting.mode"}]'),
        '.values must be a non-empty list');
});

$test('an ENUM default outside its values is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"settings":[{"key":"mode","type":"ENUM","default":"other","values":["auto"],"label":"setting.mode"}]'),
        '.default must be one of the values');
});

$test('a NUMBER default outside min..max is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"settings":[{"key":"timeout","type":"NUMBER","default":1,"min":5,"max":10,"label":"setting.timeout"}]'),
        '.default must be between min and max');
});

$test('a setting key declared twice is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"settings":[{"key":"push","type":"BOOLEAN","default":true,"label":"setting.push"},'
        . '{"key":"push","type":"BOOLEAN","default":false,"label":"setting.push"}]'), '.key "push" is declared twice');
});

$test('a setting with a key outside the closed object (e.g. a value list) is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"settings":[{"key":"push","type":"BOOLEAN","default":true,"label":"setting.push","items":[1]}]'),
        '"items" is not a key of a setting');
});

$test('more than 30 settings are refused', static function () use ($c11Refused, $c11Manifest): void {
    $settings = [];
    for ($i = 0; $i < 31; $i++) {
        $settings[] = '{"key":"s' . $i . 'x","type":"BOOLEAN","default":true,"label":"setting.push"}';
    }
    $c11Refused($c11Manifest('"settings":[' . implode(',', $settings) . ']'), 'at most 30 settings');
});

$test('admin platform with roles is refused', static function () use ($c11Refused, $c11ManifestWith): void {
    $c11Refused($c11ManifestWith('{"method":"PUT","path":"/m/c11/admin/keys","right":"api.v1.m.c11.admin.keys.put","admin":"platform","roles":["QC"]}',
        null), '.admin "platform" declares no roles');
});

$test('admin on a hookable route is refused', static function () use ($c11Refused, $c11ManifestWith): void {
    $c11Refused($c11ManifestWith('{"method":"POST","path":"/m/c11/admin/run","right":"api.v1.m.c11.admin.run.post","admin":"tenant","hookable":true}',
        null), 'admin and hookable exclude each other');
});

$test('an unknown admin value is refused', static function () use ($c11Refused, $c11ManifestWith): void {
    $c11Refused($c11ManifestWith('{"method":"GET","path":"/m/c11/admin/x","right":"api.v1.m.c11.admin.x.get","admin":"super"}', null),
        '.admin must be one of tenant, platform');
});

$test('a secret whose writeRight is not the right of a declared route is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.nowhere.put"}]'),
        '.writeRight "api.v1.m.c11.nowhere.put" is not the right of a route this module declares');
});

$test('a platform secret whose writeRight is the right of a route without admin platform is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"secrets":[{"name":"token","scope":"platform","writeRight":"api.v1.m.c11.admin.tenant-keys.put"}]'),
        'a platform secret is written only through a route with "admin": "platform"');
});

$test('a secret that carries a value is refused (a closed object: secrets travel by reference)', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put","value":"x"}]'),
        '"value" is not a key of a secret');
});

$test('a secret name declared twice and an unknown scope are refused', static function () use ($c11Refused, $c11Manifest): void {
    $json = $c11Manifest('"secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put"},'
        . '{"name":"token","scope":"global","writeRight":"api.v1.m.c11.admin.tenant-keys.put"}]');
    $c11Refused($json, '.name "token" is declared twice');
    $c11Refused($json, '.scope must be one of platform, tenant');
});

// ---- secrets[].external -----------------------------------------------------------

$test('a platform secret with external prefixes is accepted', static function () use ($c11Manifest): void {
    $json = $c11Manifest('"secrets":[{"name":"r3-client-secret","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put",'
        . '"external":["platform/aas/","kv/r3-clients/"]}]');
    $result = ManifestCheck::validate($json);
    check($result->ok(), implode('; ', $result->errors));
    check(Manifest::parse($json)->capabilities->secrets[0]->external === ['platform/aas/', 'kv/r3-clients/'], 'external read');
});

$test('external on a tenant secret, empty, outside the grammar, into kf-modules or twice is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"secrets":[{"name":"token","scope":"tenant","writeRight":"api.v1.m.c11.admin.tenant-keys.put",'
        . '"external":["platform/aas/"]}]'), '.external is allowed only on a platform secret');
    $c11Refused($c11Manifest('"secrets":[{"name":"token","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put","external":[]}]'),
        '.external must be a non-empty array of at most 5 prefixes');
    $json = $c11Manifest('"secrets":[{"name":"token","scope":"platform","writeRight":"api.v1.m.c11.admin.keys.put",'
        . '"external":["platform/","platform/aas","platform/kf-modules/x/","platform/aas/","platform/aas/"]}]');
    $c11Refused($json, '.external[0] must match ^[a-z0-9][a-z0-9_-]{0,63}(/[a-z0-9][a-z0-9_-]{0,63})+/$ (got "platform/")');
    $c11Refused($json, '.external[1] must match');
    $c11Refused($json, '.external[2] "platform/kf-modules/x/" must not reach the modules\' secrets (kf-modules)');
    $c11Refused($json, '.external[4] "platform/aas/" is declared twice');
});

// ---- routes[].timeoutSeconds (SDK 0.7.3) ------------------------------------------

$c11TimeoutRoute = static function (string $value): string {
    return C11_BASE_ROUTES
        . ',{"method":"GET","path":"/m/c11/shells","right":"api.v1.m.c11.shells.get","admin":"tenant","timeoutSeconds":' . $value . '}';
};

$test("timeoutSeconds between 1 and the cap is read on the route; absent or null reads null (the platform's default)", static function () use ($c11ManifestWith, $c11Errors, $c11TimeoutRoute): void {
    $json = $c11ManifestWith($c11TimeoutRoute('60'), null);
    check($c11Errors($json) === [], json_encode($c11Errors($json)));
    $caps = Manifest::parse($json)->capabilities;
    check($caps->routes[4]->timeoutSeconds === 60, 'timeoutSeconds 60');
    check($caps->routes[0]->timeoutSeconds === null, 'absent reads null');
    check(Manifest::MAX_ROUTE_TIMEOUT_SECONDS === 60, 'the cap');
    $nullJson = $c11ManifestWith($c11TimeoutRoute('null'), null);
    check($c11Errors($nullJson) === [], json_encode($c11Errors($nullJson)));
    check(Manifest::parse($nullJson)->capabilities->routes[4]->timeoutSeconds === null, 'null reads null');
    check(ManifestCheck::validate($c11ManifestWith($c11TimeoutRoute('1'), null))->ok(), 'one second');
});

$test('timeoutSeconds over the cap, zero, negative, fractional or not a number is refused, naming the cap', static function () use ($c11ManifestWith, $c11Refused, $c11TimeoutRoute): void {
    $c11Refused($c11ManifestWith($c11TimeoutRoute('61'), null),
        '(GET /m/c11/shells).timeoutSeconds must be a whole number of seconds between 1 and 60 (got 61)');
    foreach (['0', '-1', '1.5', '"15"', 'true'] as $value) {
        $c11Refused($c11ManifestWith($c11TimeoutRoute($value), null), '.timeoutSeconds must be a whole number of seconds between 1 and 60');
    }
});

$test('provides on a path that is not a declared GET route is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"provides":[{"port":"factory.stations.v1","path":"/m/c11/admin/keys"}]'),
        '.path "/m/c11/admin/keys" is not a GET route this module declares');
});

$test('provides with a malformed port id is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"provides":[{"port":"Factory.Stations","path":"/m/c11/stations"}]'), '.port "Factory.Stations" must match');
});

$test('a page with upper case letters is refused', static function () use ($c11Refused, $c11Manifest): void {
    $c11Refused($c11Manifest('"surfaces":[{"slug":"module.page","kind":"react","page":"Shells"}]'), '.page "Shells" must match');
});

$test('the port grammar takes one optional sub-segment, not two', static function () use ($c11Refused, $c11Manifest, $c11Errors): void {
    check($c11Errors($c11Manifest('"reads":[{"port":"file.public-url.v1"}]')) === [], 'sub-segment');
    $c11Refused($c11Manifest('"reads":[{"port":"a.b.c.v1"}]'), '.port "a.b.c.v1" must match');
    $c11Refused($c11Manifest('"reads":[{"port":"model"}]'), '.port "model" must match');
});

// ---- ContextPlatformActorTest -----------------------------------------------------------------------------------
// The actor {type: system, via: platform, hookId: null} (protocol 1.1; sdk-protocol.md §9.2): the platform
// calls a route the module declares in provides as the system, without a user and without a hook. Coherent only with
// user null, hookId null and rights []; a system context never yields a user the module could deduce.

const PLATFORM_ACTOR_SECRET = 'platform-actor-secret';
const PLATFORM_ACTOR_NOW = 1760000000;

$platformVerify = static function (string $user, string $actor, string $rights): RequestContext {
    $json = '{"v":1,"tenant":"t-1","user":' . $user . ',"actor":' . $actor . ',"locale":"ro","rights":' . $rights
        . ',"method":"GET","path":"/stations","query":null,"ts":' . PLATFORM_ACTOR_NOW . ',"requestId":"r-1"}';
    $context = rtrim(strtr(base64_encode($json), '+/', '-_'), '=');
    $signature = Signatures::sign(PLATFORM_ACTOR_SECRET, (string) PLATFORM_ACTOR_NOW, $context . '.');
    return RequestContext::verify($context, (string) PLATFORM_ACTOR_NOW, $signature, '', PLATFORM_ACTOR_SECRET, PLATFORM_ACTOR_NOW);
};
$platformRefused = static function (string $user, string $actor, string $rights, string $reason) use ($platformVerify): void {
    $rejected = null;
    try {
        $platformVerify($user, $actor, $rights);
    } catch (\Kf\Sdk\Rejected $e) {
        $rejected = $e;
    }
    check($rejected !== null, 'expected Rejected');
    check($rejected->problem()->status === 401, 'status 401');
    check($rejected->problem()->errorClass === ErrorClass::PERMANENT, 'permanent');
    check(str_contains((string) $rejected->problem()->detail, $reason), $reason . ' not in ' . $rejected->problem()->detail);
};

$test('system via platform, user null, hookId null, rights [] is accepted; no user is deduced', static function () use ($platformVerify): void {
    $ctx = $platformVerify('null', '{"type":"system","via":"platform","hookId":null}', '[]');
    check($ctx->actor->type === Actor::SYSTEM, 'type');
    check($ctx->actor->via === 'platform', 'via');
    check($ctx->actor->hookId === null, 'hookId');
    check($ctx->user === null, 'user');
    check($ctx->rights === [], 'rights');
    check($ctx->actor == Actor::platform(), 'Actor::platform()');
});

$test('system via platform with a hookId is refused', static function () use ($platformRefused): void {
    $platformRefused('null', '{"type":"system","via":"platform","hookId":"h-1"}', '[]', 'actor system via platform has no hookId');
});

$test('system via platform with a user is refused', static function () use ($platformRefused): void {
    $platformRefused('"sub-1"', '{"type":"system","via":"platform","hookId":null}', '[]', 'actor system has no user');
});

$test('system via platform with rights is refused', static function () use ($platformRefused): void {
    $platformRefused('null', '{"type":"system","via":"platform","hookId":null}', '["api.v1.m.c11.stations.get"]',
        'actor system via platform has no rights');
});

$test('system via an unknown channel is refused', static function () use ($platformRefused): void {
    $platformRefused('null', '{"type":"system","via":"cron","hookId":null}', '[]', 'actor system requires via = hook or platform');
});

// ---- ManifestEditableTest ---------------------------------------------------------------------------------------
// capabilities.fields[].editable: a boolean, false when absent, a
// rule of the host's generic rendering, not of writing. The local check refuses a value that is not a boolean (null
// included). The generic-field rule itself is fixed only in sdk-protocol.md: no fixture.

$editable = static function (?string $value): string {
    $author = '{"entity":"model","name":"author","type":"STRING","table":"models_extra","label":"field.author"'
        . ($value === null ? '' : ',"editable":' . $value) . '}';
    return '{"schemaVersion":2,"slug":"edit-mod","version":"1.0.0","platform":">=4.1","name":"module.name",'
        . '"i18n":{"en":{"module.name":"Editable","field.author":"Author","field.note":"Note"}},'
        . '"capabilities":{"fields":[' . $author . ',{"entity":"model","name":"note","type":"STRING","table":"models_extra","label":"field.note"}]}}';
};

$test('editable true is read; a field without it reads false', static function () use ($editable): void {
    $m = Manifest::parse($editable('true'));
    check($m->capabilities->fields[0]->editable === true, 'editable');
    check($m->capabilities->fields[1]->editable === false, 'absent');
    check(ManifestCheck::validate($editable('true'))->ok(), 'valid');
});

$test('editable false and absent are legal', static function () use ($editable): void {
    check(ManifestCheck::validate($editable('false'))->ok(), 'false');
    check(ManifestCheck::validate($editable(null))->ok(), 'absent');
    check(Manifest::parse($editable(null))->capabilities->fields[0]->editable === false, 'absent reads false');
});

$test('editable that is not a boolean is refused, naming the field (null included)', static function () use ($editable): void {
    foreach (['"yes"', '1', 'null'] as $value) {
        $result = ManifestCheck::validate($editable($value));
        check(!$result->ok(), $value);
        check(count($result->errors) === 1, $value . ': ' . json_encode($result->errors));
        check(str_contains($result->errors[0], 'capabilities.fields[0].editable must be a boolean'), $result->errors[0]);
        check(Manifest::parse($editable($value))->capabilities->fields[0]->editable === false, $value);
    }
});

// ---- RegistrationErrorTest --------------------------------------------------------------------------------------
// Registration errors must name their cause. with the platform unreachable the SDK logged a
// bare error. PHP's stream wrapper names the cause in its message ("Connection refused" / "actively refused it").

$test('a refused connection names its cause, never the token', static function () use (&$lines): void {
    $token = 'kfm_0123456789abcdef-test-only';
    $socket = stream_socket_server('tcp://127.0.0.1:0');
    $name = stream_socket_get_name($socket, false);
    fclose($socket);
    $port = (int) substr($name, strrpos($name, ':') + 1);
    $kf = KfModule::fromEnv(['KF_PLATFORM_URL' => "http://127.0.0.1:$port", 'KF_MODULE_SLUG' => 'sdk-error-test',
        'KF_MODULE_TOKEN' => $token, 'KF_MODULE_MANIFEST' => 'does-not-exist/kf-module-conf.json',
        'KF_SDK_STATE_DIR' => sys_get_temp_dir() . '/kf-sdk-error-test-' . bin2hex(random_bytes(4))]);
    try {
        $kf->registerWithRetry(1);
        check(false, 'the registration must fail');
    } catch (KfException $e) {
        check($e->errorClass() === ErrorClass::TRANSIENT, 'a network error is transient');
    }
    $error = (string) $kf->lastRegistrationError();
    check(str_starts_with($error, 'RuntimeException: '), $error);
    check(stripos($error, 'refused') !== false, "the refusal must be named: $error");
    foreach ([$error, ...$lines] as $line) check(!str_contains($line, $token), 'the module token must never reach an error message');
});

// ---- NotificationsTest --------------------------------------------------------------------------------------------
// Notifications against a platform stub: the request it sends, the local refusals (nothing sent), the retry
// of a transient answer with the SAME idempotency key, and no retry of a permanent one.

const NOTIF_TOKEN = 'kfm_notifications-token';
const NOTIF_TENANT = '00000000-0000-0000-0000-00000000000a';

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

/** @param list<array{status: int, body: string}> $answers */
$notificationsPlatform = static function (array $answers): array {
    $server = sequenced($answers);
    $kf = KfModule::of('http://127.0.0.1:' . $server['port'], 'notif-mod', NOTIF_TOKEN, 'secret', null);
    $kf->notifications()->withRetryDelays([10, 20]);
    $stop = static function () use ($server): void {
        foreach ($server['pipes'] as $p) @fclose($p);
        $status = proc_get_status($server['process']);
        if (PHP_OS_FAMILY === 'Windows' && $status['running']) exec('taskkill /F /T /PID ' . (int) $status['pid'] . ' 2>NUL');
        else proc_terminate($server['process']);
        proc_close($server['process']);
        foreach (scandir($server['dir']) as $f) if ($f !== '.' && $f !== '..') @unlink($server['dir'] . '/' . $f);
        @rmdir($server['dir']);
    };
    return [$kf, $stop, static fn (): array => sequencedRequests($server)];
};

$test('send posts the wire form with the module token', static function () use ($notificationsPlatform): void {
    [$kf, $stop, $seen] = $notificationsPlatform([['status' => 202, 'body' => '{"accepted":2,"duplicates":0}']]);
    try {
        $result = $kf->notifications()->send(NOTIF_TENANT, 'author-changed', ['sub-1'], ['modelName' => 'X']);
        check($result->accepted === 2 && $result->duplicates === 0, 'the result');
        $r = $seen();
        check(count($r) === 1, 'one request');
        check($r[0]->method === 'POST' && $r[0]->path === '/api/v1/platform/modules/notif-mod/notifications', $r[0]->path);
        check($r[0]->headers->Authorization === 'Bearer ' . NOTIF_TOKEN, 'the module token');
        $b = $r[0]->body;
        check($b->tenantId === NOTIF_TENANT && $b->key === 'author-changed' && $b->recipients === ['sub-1'], 'the body');
        check($b->variables->modelName === 'X', 'the variables');
        check(!property_exists($b, 'deepLink') && !property_exists($b, 'channels'), 'no options');
        check(preg_match('/^[0-9a-f-]{36}$/', $b->idempotencyKey) === 1, 'an idempotency key is always sent');
    } finally {
        $stop();
    }
});

$test('the options travel as given', static function () use ($notificationsPlatform): void {
    [$kf, $stop, $seen] = $notificationsPlatform([['status' => 202, 'body' => '{"accepted":1,"duplicates":0}']]);
    try {
        $key = '7a07a07a-0000-4000-8000-000000000207';
        $kf->notifications()->send(NOTIF_TENANT, 'author-changed', ['sub-1'], ['modelName' => 'X'],
            new \Kf\Sdk\NotificationOptions(deepLink: '/models/42', channels: ['in_app'], idempotencyKey: $key));
        $b = $seen()[0]->body;
        check($b->deepLink === '/models/42' && $b->channels === ['in_app'] && $b->idempotencyKey === $key, 'the options');
    } finally {
        $stop();
    }
});

$test('a 422 is permanent and not retried', static function () use ($notificationsPlatform): void {
    [$kf, $stop, $seen] = $notificationsPlatform([['status' => 422, 'body' => '{"type":"https://kf.ro/problems/notification-rejected",'
        . '"status":422,"detail":"The active version of notif-mod does not declare the notification \'x\'.",'
        . '"reason":"unknown-key","class":"permanent"}']]);
    try {
        try {
            $kf->notifications()->send(NOTIF_TENANT, 'x', ['sub-1'], []);
            check(false, 'must fail');
        } catch (\Kf\Sdk\NotificationRejected $e) {
            check($e->errorClass() === ErrorClass::PERMANENT && $e->problem()->status === 422, 'permanent 422');
            check($e->reason() === 'unknown-key', 'the reason');
            check(str_contains($e->getMessage(), 'does not declare the notification'), $e->getMessage());
            check(!str_contains($e->getMessage(), NOTIF_TOKEN), 'no token in the message');
        }
        check(count($seen()) === 1, 'no retry');
    } finally {
        $stop();
    }
});

$test('a 503 is retried with the same idempotency key', static function () use ($notificationsPlatform): void {
    [$kf, $stop, $seen] = $notificationsPlatform([
        ['status' => 503, 'body' => '{"status":503,"detail":"busy","class":"transient"}'],
        ['status' => 202, 'body' => '{"accepted":0,"duplicates":1}'],
    ]);
    try {
        $result = $kf->notifications()->send(NOTIF_TENANT, 'author-changed', ['sub-1'], ['modelName' => 'X']);
        $r = $seen();
        check(count($r) === 2, 'two requests');
        check($r[0]->body->idempotencyKey === $r[1]->body->idempotencyKey, 'the same idempotency key');
        check($r[0] == $r[1], 'the retry sends the same request');
        check($result->duplicates === 1, 'the duplicates');
    } finally {
        $stop();
    }
});

$test('transient until the schedule ends is a KfException', static function () use ($notificationsPlatform): void {
    [$kf, $stop, $seen] = $notificationsPlatform([['status' => 503, 'body' => '{"status":503,"detail":"busy","class":"transient"}']]);
    try {
        try {
            $kf->notifications()->send(NOTIF_TENANT, 'author-changed', ['sub-1'], ['modelName' => 'X']);
            check(false, 'must fail');
        } catch (KfException $e) {
            check($e->errorClass() === ErrorClass::TRANSIENT, 'transient');
        }
        check(count($seen()) === 3, 'the first try and one per step of the schedule');
    } finally {
        $stop();
    }
});

$test('local refusals send nothing', static function () use ($notificationsPlatform): void {
    [$kf, $stop, $seen] = $notificationsPlatform([['status' => 202, 'body' => '{"accepted":1,"duplicates":0}']]);
    try {
        $n = $kf->notifications();
        $many = array_map(static fn (int $i): string => 's' . $i, range(1, 101));
        $calls = [
            static fn () => $n->send(null, 'author-changed', ['s'], []),
            static fn () => $n->send(NOTIF_TENANT, 'm.notif-mod.x', ['s'], []),
            static fn () => $n->send(NOTIF_TENANT, 'Bad', ['s'], []),
            static fn () => $n->send(NOTIF_TENANT, 'ok', [], []),
            static fn () => $n->send(NOTIF_TENANT, 'ok', $many, []),
            static fn () => $n->send(NOTIF_TENANT, 'ok', [' '], []),
            static fn () => $n->send(NOTIF_TENANT, 'ok', ['s'], ['v' => [1]]),
            static fn () => $n->send(NOTIF_TENANT, 'ok', ['s'], [], new \Kf\Sdk\NotificationOptions(deepLink: 'https://evil.example')),
            static fn () => $n->send(NOTIF_TENANT, 'ok', ['s'], [], new \Kf\Sdk\NotificationOptions(channels: ['sms'])),
        ];
        foreach ($calls as $i => $call) {
            try {
                $call();
                check(false, "call $i must fail");
            } catch (KfException $e) {
                check($e->errorClass() === ErrorClass::PERMANENT && $e->problem()->status === 0, "call $i: a local refusal");
            }
        }
        check(count($seen()) === 0, 'a local refusal sends nothing');
    } finally {
        $stop();
    }
});

$test('recipientRoles travel and recipients may be empty', static function () use ($notificationsPlatform): void {
    [$kf, $stop, $seen] = $notificationsPlatform([['status' => 202, 'body' => '{"accepted":2,"duplicates":0}']]);
    try {
        $result = $kf->notifications()->send(NOTIF_TENANT, 'author-changed', [], ['modelName' => 'X'],
            \Kf\Sdk\NotificationOptions::roles('TENANT_ADMIN', 'QC', 'TENANT_ADMIN'));
        check($result->accepted === 2, 'the result');
        $b = $seen()[0]->body;
        check($b->recipients === [], 'recipients is sent, empty: a platform before 1.3 refuses it');
        check($b->recipientRoles === ['TENANT_ADMIN', 'QC'], 'a repeated role counts once');
    } finally {
        $stop();
    }
});

$test('without roles the body has no recipientRoles', static function () use ($notificationsPlatform): void {
    [$kf, $stop, $seen] = $notificationsPlatform([['status' => 202, 'body' => '{"accepted":1,"duplicates":0}']]);
    try {
        $kf->notifications()->send(NOTIF_TENANT, 'author-changed', ['sub-1'], ['modelName' => 'X'],
            new \Kf\Sdk\NotificationOptions(deepLink: '/models/42'));
        check(!property_exists($seen()[0]->body, 'recipientRoles'), 'no recipientRoles');
    } finally {
        $stop();
    }
});

$test('recipientRoles local refusals send nothing', static function () use ($notificationsPlatform): void {
    [$kf, $stop, $seen] = $notificationsPlatform([['status' => 202, 'body' => '{"accepted":1,"duplicates":0}']]);
    try {
        $n = $kf->notifications();
        $eleven = array_map(static fn (int $i): string => 'ROLE_' . $i, range(1, 11));
        $calls = [
            static fn () => $n->send(NOTIF_TENANT, 'ok', [], [], new \Kf\Sdk\NotificationOptions(recipientRoles: [])),
            static fn () => $n->send(NOTIF_TENANT, 'ok', [], [], \Kf\Sdk\NotificationOptions::roles(' ')),
            static fn () => $n->send(NOTIF_TENANT, 'ok', [], [], \Kf\Sdk\NotificationOptions::roles('tenant_admin')),
            static fn () => $n->send(NOTIF_TENANT, 'ok', [], [], new \Kf\Sdk\NotificationOptions(recipientRoles: $eleven)),
            static fn () => $n->send(NOTIF_TENANT, 'ok', null, []),
        ];
        foreach ($calls as $i => $call) {
            try {
                $call();
                check(false, "call $i must fail");
            } catch (KfException $e) {
                check($e->errorClass() === ErrorClass::PERMANENT && $e->problem()->status === 0, "call $i: a local refusal");
            }
        }
        check(count($seen()) === 0, 'a local refusal sends nothing');
    } finally {
        $stop();
    }
});

// ---- ReadsClientTest, SettingsClientTest, SecretsClientTest, ExtensionsClientTest --------------------------
// The clients of protocol 1.1 against a platform stub: the module token, the context and the platform's proof go back
// unchanged; the answers become the SDK's values; 400/401/403/404 are PlatformRefusals with their reason; the local rules
// refuse before any request; a secret's value is in no log, no __toString and no error.

const SERVICE_TENANT = '00000000-0000-0000-0000-00000000000a';
const SECRET_VALUE = 'r3-Pa55-never-logged';

/** A platform stub for `$slug` (`php -S` with a router): answers with answer.json, keeps the last request. */
$servicePlatform = static function (string $slug, string $secret, string $token): array {
    $dir = sys_get_temp_dir() . '/kf-sdk-service-unit-' . bin2hex(random_bytes(4));
    mkdir($dir);
    file_put_contents($dir . '/answer.json', json_encode(['status' => 200, 'body' => '{}']));
    file_put_contents($dir . '/count', '0');
    file_put_contents($dir . '/router.php', <<<'PHP'
        <?php
        $dir = __DIR__;
        $raw = file_get_contents('php://input');
        file_put_contents($dir . '/count', (string) ((int) file_get_contents($dir . '/count') + 1));
        file_put_contents($dir . '/request.json', json_encode([
            'line' => $_SERVER['REQUEST_METHOD'] . ' ' . $_SERVER['REQUEST_URI'],
            'authorization' => $_SERVER['HTTP_AUTHORIZATION'] ?? null, 'context' => $_SERVER['HTTP_X_KF_CONTEXT'] ?? null,
            'proof' => $_SERVER['HTTP_X_KF_CONTEXT_PROOF'] ?? null, 'body' => $raw,
            'idempotencyKey' => $_SERVER['HTTP_IDEMPOTENCY_KEY'] ?? null,
        ], JSON_UNESCAPED_SLASHES));
        $a = json_decode(file_get_contents($dir . '/answer.json'), true);
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
    $deadline = microtime(true) + 10;
    while (($c = @stream_socket_client('tcp://127.0.0.1:' . $port, $errno, $errstr, 0.2)) === false) {
        if (microtime(true) > $deadline) throw new \RuntimeException("php -S did not listen on $port");
        usleep(50_000);
    }
    fclose($c);
    $kf = KfModule::of('http://127.0.0.1:' . $port, $slug, $token, $secret, null);
    $stop = static function () use ($process, $pipes, $dir): void {
        foreach ($pipes as $p) @fclose($p);
        $status = proc_get_status($process);
        if (PHP_OS_FAMILY === 'Windows' && $status['running']) exec('taskkill /F /T /PID ' . (int) $status['pid'] . ' 2>NUL');
        else proc_terminate($process);
        proc_close($process);
        foreach (scandir($dir) as $f) if ($f !== '.' && $f !== '..') @unlink($dir . '/' . $f);
        @rmdir($dir);
    };
    $answer = static fn (int $status, string $body) => file_put_contents($dir . '/answer.json', json_encode(['status' => $status, 'body' => $body]));
    $requests = static fn (): int => (int) file_get_contents($dir . '/count');
    $last = static fn (): array => json_decode((string) file_get_contents($dir . '/request.json'), true);
    $verified = static function (string $requestId, ?string $proof, string $userAndActor = '"user":"sub-1","actor":{"type":"user","via":null,"hookId":null}', string $rights = '[]') use ($kf, $secret): RequestContext {
        $ts = time();
        $json = '{"v":1,"tenant":"' . SERVICE_TENANT . '",' . $userAndActor . ',"locale":"en","rights":' . $rights
            . ',"method":"GET","path":"/x","query":null,"ts":' . $ts . ',"requestId":"' . $requestId . '"}';
        $context = rtrim(strtr(base64_encode($json), '+/', '-_'), '=');
        return $kf->verifyContext($context, (string) $ts, Signatures::sign($secret, (string) $ts, $context . '.'), '', $proof);
    };
    return [$kf, $stop, $answer, $requests, $last, $verified];
};

/** The values 1..n, comma-separated, as a module writes the `ids` filter. */
function idList(int $n): string
{
    return implode(',', range(1, $n));
}

/** The KfException a call throws (a test fails when it throws nothing). */
function refused(callable $call): KfException
{
    try {
        $call();
    } catch (KfException $e) {
        return $e;
    }
    throw new \RuntimeException('must throw a KfException');
}

$test('reads list: GET …/reads/model.v1?filters&page&size with the token, the context and the proof unchanged → ReadPage',
    static function () use ($servicePlatform): void {
        [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('reads-mod', 'reads-secret', 'kfm_reads-token');
        try {
            $proof = str_repeat('a', 64);
            $ctx = $verified('req-1', $proof);
            $answer(200, '{"items":[{"id":"42","name":"Rochie","price":12.50}],"page":0,"size":50,"total":51}');
            $page = $kf->reads()->list($ctx, 'model.v1', ['updatedSince' => '2026-10-01T00:00:00Z'], 0, 50);
            $r = $last();
            check($requests() === 1, 'one request');
            check($r['line'] === 'GET /api/v1/platform/modules/reads-mod/reads/model.v1?updatedSince=2026-10-01T00%3A00%3A00Z&page=0&size=50', $r['line']);
            check($r['authorization'] === 'Bearer kfm_reads-token' && $r['proof'] === $proof, 'token/proof');
            check(str_contains((string) base64_decode(strtr((string) $r['context'], '-_', '+/')), '"requestId":"req-1"'), 'context');
            check($page->page === 0 && $page->size === 50 && $page->total === 51 && $page->hasNext(), 'page');
            check($page->items[0]['name'] === 'Rochie' && $page->items[0]['price'] === 12.5, json_encode($page->items));

            $answer(200, '{"items":[],"page":0,"size":20,"total":0}');
            $empty = $kf->reads()->list($ctx, 'technician.v1', []);
            check($last()['line'] === 'GET /api/v1/platform/modules/reads-mod/reads/technician.v1?page=0&size=20', $last()['line']);
            check($empty->items === [] && !$empty->hasNext(), 'empty page');

            // ids at the limit: 200 values are sent as asked, comma-separated
            $kf->reads()->list($ctx, 'model.v1', ['ids' => idList(ReadsClient::MAX_IDS)]);
            $line = $last()['line'];
            check(str_starts_with($line, 'GET /api/v1/platform/modules/reads-mod/reads/model.v1?ids=1%2C2%2C'), $line);
            check(str_ends_with($line, '%2C200&page=0&size=20') && $requests() === 3, $line);
        } finally {
            $stop();
        }
    });

$test('reads get: the object; include → ?include=a,b; 404 → null; 403/401/400 → PlatformRefusal; 503 → transient',
    static function () use ($servicePlatform): void {
        [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('reads-mod', 'reads-secret', 'kfm_reads-token');
        try {
            $ctx = $verified('req-2', str_repeat('a', 64));
            $answer(200, '{"id":"42","name":"Rochie"}');
            check($kf->reads()->get($ctx, 'model.v1', '42')['name'] === 'Rochie', 'object');
            check($last()['line'] === 'GET /api/v1/platform/modules/reads-mod/reads/model.v1/42', $last()['line']);
            $kf->reads()->get($ctx, 'model-version.v1', '7', ['grading', 'consumption']);
            check($last()['line'] === 'GET /api/v1/platform/modules/reads-mod/reads/model-version.v1/7?include=grading,consumption', $last()['line']);
            $answer(404, '{"type":"https://kf.ro/problems/not-found","status":404,"class":"permanent"}');
            check($kf->reads()->get($ctx, 'model.v1', '9 9') === null, '404 → null');
            check($last()['line'] === 'GET /api/v1/platform/modules/reads-mod/reads/model.v1/9%209', $last()['line']);

            $answer(403, '{"type":"https://kf.ro/problems/insufficient-rights","status":403,"detail":"The user does not hold api.v1.models.get","class":"permanent"}');
            $e = refused(fn () => $kf->reads()->get($ctx, 'model.v1', '42'));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::FORBIDDEN && $e->reason() === 'insufficient-rights', $e::class);
            check(str_contains($e->getMessage(), 'api.v1.models.get') && $e->errorClass() === ErrorClass::PERMANENT, $e->getMessage());
            $answer(401, '{"type":"https://kf.ro/problems/invalid-context","status":401,"class":"permanent"}');
            $e = refused(fn () => $kf->reads()->list($ctx, 'model.v1', []));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::UNAUTHORIZED && $e->reason() === 'invalid-context', 'unauthorized');
            $answer(400, '{"type":"https://kf.ro/problems/validation","status":400,"detail":"unknown filter","class":"permanent","errors":{"colour":"unknown filter"}}');
            $e = refused(fn () => $kf->reads()->list($ctx, 'model.v1', ['colour' => 'red']));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::INVALID && $e->errors() === ['colour' => 'unknown filter'], 'invalid');
            check(str_contains($e->getMessage(), 'colour'), $e->getMessage());
            $answer(503, '');
            $e = refused(fn () => $kf->reads()->get($ctx, 'model.v1', '42'));
            check($e->errorClass() === ErrorClass::TRANSIENT && !($e instanceof PlatformRefusal), 'transient');
            check(!str_contains($e->getMessage(), 'kfm_reads-token'), 'the token in an error');
        } finally {
            $stop();
        }
    });

$test('reads: the local rules and the system context refuse before any request; an undeclared port with a manifest',
    static function () use ($servicePlatform): void {
        [$kf, $stop, $answer, $requests, , $verified] = $servicePlatform('reads-mod', 'reads-secret', 'kfm_reads-token');
        try {
            $ctx = $verified('req-3', str_repeat('a', 64));
            $cases = [
                'port must match' => fn () => $kf->reads()->get($ctx, 'Model', '1'),
                'size must be between 1 and 200' => fn () => $kf->reads()->list($ctx, 'model.v1', [], 0, 201),
                'page must be at least 0' => fn () => $kf->reads()->list($ctx, 'model.v1', [], -1, 20),
                'filter page is reserved' => fn () => $kf->reads()->list($ctx, 'model.v1', ['page' => '2']),
                'include grading,consumption must match' => fn () => $kf->reads()->get($ctx, 'model-version.v1', '7', ['grading,consumption']),
                'id is required' => fn () => $kf->reads()->get($ctx, 'model.v1', ' '),
                'filter ids takes at most 200 values' => fn () => $kf->reads()->list($ctx, 'model.v1', ['ids' => idList(201)]),
            ];
            foreach ($cases as $fragment => $call) {
                $e = refused($call);
                check(str_contains($e->getMessage(), $fragment) && $e->errorClass() === ErrorClass::PERMANENT && $e->problem()->status === 0, $e->getMessage());
            }
            $built = new RequestContext(1, SERVICE_TENANT, 'u', Actor::user(), 'en', [], 'GET', '/x', null, time(), 'never-verified');
            check(str_contains(refused(fn () => $kf->reads()->get($built, 'model.v1', '42'))->getMessage(), 'was not verified by this module'), 'unknown context');
            $noProof = $verified('req-4', null);
            check(str_contains(refused(fn () => $kf->reads()->list($noProof, 'model.v1', []))->getMessage(), 'X-KF-Context-Proof'), 'no proof');

            $system = $verified('req-5', str_repeat('a', 64), '"user":null,"actor":{"type":"system","via":"hook","hookId":"3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b0a"}');
            check(str_contains(refused(fn () => $kf->reads()->list($system, 'model.v1', []))->getMessage(), 'the system context reads only objects'), 'system list');
            check(str_contains(refused(fn () => $kf->reads()->get($system, 'model-version.v1', '7', ['grading']))->getMessage(), 'the system context reads only the base fields'), 'system include');
            check($requests() === 0, 'no request may be sent');
            $answer(200, '{"id":"7"}');
            check($kf->reads()->get($system, 'model-version.v1', '7')['id'] === '7' && $requests() === 1, 'system object');

            $kf->withManifest('{"schemaVersion":2,"slug":"reads-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"Reads"}},"capabilities":{"reads":[{"port":"model.v1"}]}}');
            check(str_contains(refused(fn () => $kf->reads()->get($ctx, 'order.v1', '1'))->getMessage(), 'order.v1 is not declared in capabilities.reads'), 'undeclared port');
            check($requests() === 1, 'no request for the undeclared port');
        } finally {
            $stop();
        }
    });

$test('settings: typed by the manifest; an undeclared key ignored with a WARN; a mismatch takes the default; local refusals',
    static function () use ($servicePlatform, &$lines): void {
        [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('set-mod', 'settings-secret', 'kfm_settings-token');
        try {
            $kf->withManifest('{"schemaVersion":2,"slug":"set-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"Settings","l":"Label"}},'
                . '"capabilities":{"settings":[{"key":"live","type":"BOOLEAN","default":false,"label":"l"},'
                . '{"key":"batch-size","type":"NUMBER","default":50,"label":"l","min":1,"max":500},'
                . '{"key":"push-mode","type":"ENUM","default":"manual","label":"l","values":["manual","auto"]}]}}');
            $ctx = $verified('req-1', str_repeat('b', 64));
            $answer(200, '{"live":true,"batch-size":120.50,"push-mode":"auto"}');
            $lines = [];
            $s = $kf->settings()->effective($ctx);
            check($last()['line'] === 'GET /api/v1/platform/modules/set-mod/settings', $last()['line']);
            check(array_keys($s) === ['live', 'batch-size', 'push-mode'], 'the manifest order');
            check($s['live'] === true && $s['batch-size'] === 120.5 && $s['push-mode'] === 'auto', json_encode($s));
            check($lines === [], implode("\n", $lines));

            $answer(200, '{"live":"yes","batch-size":900,"retired":1}');
            $s = $kf->settings()->effective($ctx);
            check($s['live'] === false && $s['batch-size'] === 50 && $s['push-mode'] === 'manual', json_encode($s));
            $all = implode("\n", $lines);
            check(str_contains($all, 'setting retired is not declared') && str_contains($all, 'setting live does not match its declaration (BOOLEAN)')
                && str_contains($all, 'setting push-mode is missing'), $all);

            $before = $requests();
            $bare = KfModule::of('http://127.0.0.1:1', 'set-mod', 'kfm_settings-token', 'settings-secret', null);
            check(str_contains(refused(fn () => $bare->settings()->effective($ctx))->getMessage(), 'settings need the module manifest'), 'no manifest');
            $noProof = $verified('req-2', null);
            check(str_contains(refused(fn () => $kf->settings()->effective($noProof))->getMessage(), 'X-KF-Context-Proof'), 'no proof');
            check($requests() === $before, 'no request may be sent');

            $answer(403, '{"type":"https://kf.ro/problems/module-not-live","status":403,"detail":"not live","class":"permanent"}');
            $e = refused(fn () => $kf->settings()->effective($ctx));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::FORBIDDEN && $e->reason() === 'module-not-live', 'forbidden');
        } finally {
            $stop();
        }
    });

$test('secrets: write → the reference, resolve → the value; foreign references refused locally; the value in no log, string or error',
    static function () use ($servicePlatform, &$lines): void {
        [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('sec-mod', 'secrets-secret', 'kfm_secrets-token');
        $lines = [];
        try {
            $ctx = $verified('req-1', str_repeat('c', 64), '"user":"sub-1","actor":{"type":"user","via":null,"hookId":null}', '["api.v1.m.sec-mod.admin-r3.post"]');
            $ref = 'vault://platform/kf-modules/sec-mod/' . SERVICE_TENANT . '/r3-client-secret';
            $answer(200, '{"ref":"' . $ref . '"}');
            check($kf->secrets()->write($ctx, 'r3-client-secret', SECRET_VALUE) === $ref, 'ref');
            check($last()['line'] === 'PUT /api/v1/platform/modules/sec-mod/secrets/r3-client-secret', $last()['line']);
            check(json_decode($last()['body'], true) === ['value' => SECRET_VALUE], $last()['body']);

            $answer(200, '{"value":"' . SECRET_VALUE . '"}');
            $secret = $kf->secrets()->resolve($ctx, $ref);
            check($last()['line'] === 'POST /api/v1/platform/modules/sec-mod/secrets/resolve', $last()['line']);
            check(json_decode($last()['body'], true) === ['ref' => $ref], $last()['body']);
            check($secret->value() === SECRET_VALUE, 'value');
            check(!str_contains((string) $secret, SECRET_VALUE) && str_contains((string) $secret, $ref), (string) $secret);
            check(!str_contains(json_encode($secret), SECRET_VALUE) && !str_contains(print_r($secret, true), SECRET_VALUE), 'json/print_r');

            $before = $requests();
            foreach ([
                'vault://platform/kf-modules/other-mod/' . SERVICE_TENANT . '/r3-client-secret',
                'vault://platform/kf-modules/sec-mod/00000000-0000-0000-0000-00000000000b/r3-client-secret',
                'vault://platform/kf-modules/sec-mod/' . SERVICE_TENANT . '/r3-client-secret/../x',
                'https://platform/kf-modules/sec-mod/platform/r3-client-secret',
                'vault:///kf-modules/sec-mod/platform/r3-client-secret',
            ] as $foreign) {
                $e = refused(fn () => $kf->secrets()->resolve($ctx, $foreign));
                check($e->errorClass() === ErrorClass::PERMANENT && $e->problem()->status === 0, $foreign);
            }
            check($requests() === $before, 'no request for a foreign reference');
            check($kf->secrets()->resolve($ctx, 'vault://kv/platform/kf-modules/sec-mod/platform/r3-client-secret')->value() === SECRET_VALUE, 'platform ref');

            $answer(400, '{"type":"https://kf.ro/problems/validation","title":"bad ' . SECRET_VALUE . '","status":400,'
                . '"detail":"value ' . SECRET_VALUE . ' is too long","class":"permanent","errors":{"value":"' . SECRET_VALUE . '"}}');
            $e = refused(fn () => $kf->secrets()->write($ctx, 'r3-client-secret', SECRET_VALUE));
            check($e instanceof PlatformRefusal && !str_contains($e->getMessage(), SECRET_VALUE) && str_contains($e->getMessage(), 'value *** is too long'), $e->getMessage());
            check(!str_contains((string) $e->problem()->title, SECRET_VALUE) && !str_contains(json_encode($e->errors()), SECRET_VALUE), 'title/errors');
            check(!str_contains(refused(fn () => $kf->secrets()->write($ctx, 'R3 secret', SECRET_VALUE))->getMessage(), SECRET_VALUE), 'bad name');
            check(str_contains(refused(fn () => $kf->secrets()->write($ctx, 'r3-client-secret', ''))->getMessage(), 'the value is required'), 'empty');

            $answer(403, '{"type":"https://kf.ro/problems/insufficient-rights","status":403,"class":"permanent"}');
            $e = refused(fn () => $kf->secrets()->write($ctx, 'r3-client-secret', SECRET_VALUE));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::FORBIDDEN && $e->reason() === 'insufficient-rights', 'forbidden');
            $answer(200, '{"ref":"vault://platform/kf-modules/other-mod/platform/x1"}');
            check(str_contains(refused(fn () => $kf->secrets()->write($ctx, 'r3-client-secret', SECRET_VALUE))->getMessage(), 'no reference of this module'), 'foreign answer');

            $kf->withManifest('{"schemaVersion":2,"slug":"sec-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"S"}},'
                . '"capabilities":{"secrets":[{"name":"r3-client-secret","scope":"tenant","writeRight":"api.v1.m.sec-mod.admin-r3.post"}]}}');
            $before = $requests();
            check(str_contains(refused(fn () => $kf->secrets()->write($ctx, 'nc-token', SECRET_VALUE))->getMessage(), 'secret nc-token is not declared in capabilities.secrets'), 'undeclared');
            check($requests() === $before, 'no request for an undeclared secret');
        } finally {
            $stop();
            check(!str_contains(implode("\n", $lines), SECRET_VALUE), 'the value in a log line');
        }
    });

$test('secrets: an existing reference outside the module\'s path is sent only when a secrets[].external prefix of the manifest covers it; otherwise refused locally',
    static function () use ($servicePlatform): void {
        [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('sec-mod', 'secrets-secret', 'kfm_secrets-token');
        try {
            $ctx = $verified('req-6', str_repeat('c', 64), '"user":"sub-1","actor":{"type":"user","via":null,"hookId":null}', '["api.v1.m.sec-mod.admin-r3.post"]');
            $external = 'vault://platform/aas/kf-r3/client-secret';

            $noManifest = refused(fn () => $kf->secrets()->resolve($ctx, $external));
            check($noManifest->problem()->status === 0, 'no manifest');
            check($requests() === 0, 'no request without a manifest');

            $kf->withManifest('{"schemaVersion":2,"slug":"sec-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"S"}},'
                . '"capabilities":{"secrets":[{"name":"r3-client-secret","scope":"platform",'
                . '"writeRight":"api.v1.m.sec-mod.admin-r3.post","external":["platform/aas/"]}]}}');
            foreach (['vault://platform/idp/keycloak/client-secret', 'vault://platform/aas/../idp/keycloak/client-secret',
                'vault://platform/aas/', 'vault://platform/aas//x'] as $ref) {
                $e = refused(fn () => $kf->secrets()->resolve($ctx, $ref));
                check($e->problem()->status === 0 && $e->errorClass() === ErrorClass::PERMANENT, $ref);
            }
            $undeclared = refused(fn () => $kf->secrets()->resolve($ctx, 'vault://platform/idp/keycloak/client-secret'));
            check(str_contains($undeclared->getMessage(), 'is under no prefix declared in capabilities.secrets[].external'), $undeclared->getMessage());
            check($requests() === 0, 'no request for an undeclared reference');

            $answer(200, '{"value":"' . SECRET_VALUE . '"}');
            $secret = $kf->secrets()->resolve($ctx, $external);
            check($last()['line'] === 'POST /api/v1/platform/modules/sec-mod/secrets/resolve', $last()['line']);
            check(json_decode($last()['body'], true) === ['ref' => $external], $last()['body']);
            check($secret->value() === SECRET_VALUE, 'value');
        } finally {
            $stop();
        }
    });

$test('extensions: PATCH with exactly the given keys (null kept); 400 names the field; 404 not-found; the local rules',
    static function () use ($servicePlatform): void {
        [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('ext-mod', 'ext-secret', 'kfm_ext-token');
        try {
            $proof = str_repeat('d', 64);
            $ctx = $verified('req-1', $proof);
            $answer(200, '{"aas_shell":"X","aas_last_push":null}');
            $written = $kf->extensions()->write($ctx, 'model_version', '17', ['aas_shell' => 'X', 'aas_last_push' => null]);
            check($last()['line'] === 'PATCH /api/v1/platform/modules/ext-mod/extensions/model_version/17', $last()['line']);
            check($last()['proof'] === $proof, 'proof');
            $sent = json_decode($last()['body'], true);
            check(array_keys($sent) === ['aas_shell', 'aas_last_push'] && $sent['aas_last_push'] === null, $last()['body']);
            check($written['aas_shell'] === 'X', json_encode($written));

            $answer(400, '{"type":"https://kf.ro/problems/validation","status":400,"detail":"invalid extension values",'
                . '"class":"permanent","errors":{"extensions.ext-mod.aas_shell":"must be text"}}');
            $e = refused(fn () => $kf->extensions()->write($ctx, 'model_version', '17', ['aas_shell' => 12]));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::INVALID
                && $e->errors() === ['extensions.ext-mod.aas_shell' => 'must be text'], 'invalid');
            check(str_contains($e->getMessage(), 'extensions.ext-mod.aas_shell: must be text'), $e->getMessage());
            $answer(404, '{"type":"https://kf.ro/problems/not-found","status":404,"class":"permanent"}');
            $e = refused(fn () => $kf->extensions()->write($ctx, 'order', 'o 9', ['p' => 'A']));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::NOT_FOUND, 'not-found');
            check($last()['line'] === 'PATCH /api/v1/platform/modules/ext-mod/extensions/order/o%209', $last()['line']);

            $before = $requests();
            foreach ([
                'entity must be one of model, order, customer, model_version' => fn () => $kf->extensions()->write($ctx, 'supplier', '1', ['a' => 'b']),
                'id is required' => fn () => $kf->extensions()->write($ctx, 'model', ' ', ['a' => 'b']),
                'at least one value is required' => fn () => $kf->extensions()->write($ctx, 'model', '1', []),
                'field Bad-Name must match' => fn () => $kf->extensions()->write($ctx, 'model', '1', ['Bad-Name' => 'x']),
            ] as $fragment => $call) {
                check(str_contains(refused($call)->getMessage(), $fragment), $fragment);
            }
            $kf->withManifest('{"schemaVersion":2,"slug":"ext-mod","name":"n","version":"1.0.0","i18n":{"en":{"n":"E","f":"F"}},'
                . '"capabilities":{"fields":[{"entity":"model_version","name":"aas_shell","type":"STRING","table":"t","label":"f"}]}}');
            check(str_contains(refused(fn () => $kf->extensions()->write($ctx, 'model', '1', ['aas_shell' => 'x']))->getMessage(),
                'aas_shell is not declared in capabilities.fields for model'), 'undeclared field');
            check($requests() === $before, 'no request may be sent');
        } finally {
            $stop();
        }
    });

$test("protocol 1.3: the platform's own context lists tenant.v1 without ids; any other port, or a hook's context, still not",
    static function () use ($servicePlatform): void {
        [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('reads-mod', 'reads-secret', 'kfm_reads-token');
        try {
            $proof = str_repeat('a', 64);
            $platformCtx = $verified('req-13', $proof, '"user":null,"actor":{"type":"system","via":"platform","hookId":null}');
            $answer(200, '{"items":[{"id":"' . SERVICE_TENANT . '","name":"KF Dev","deletedAt":null}],"page":0,"size":200,"total":1}');

            $page = $kf->reads()->list($platformCtx, 'tenant.v1', [], 0, 200);

            check($requests() === 1, 'one request');
            check($last()['line'] === 'GET /api/v1/platform/modules/reads-mod/reads/tenant.v1?page=0&size=200', $last()['line']);
            check($page->items[0]['name'] === 'KF Dev', 'the page');
            check(array_key_exists('deletedAt', $page->items[0]), 'deletedAt');

            check(str_contains(refused(fn () => $kf->reads()->list($platformCtx, 'model.v1', []))->getMessage(), 'the system context reads only objects'), 'other port');
            $hook = $verified('req-14', $proof, '"user":null,"actor":{"type":"system","via":"hook","hookId":"3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b0a"}');
            check(str_contains(refused(fn () => $kf->reads()->list($hook, 'tenant.v1', []))->getMessage(), 'the system context reads only objects'), 'hook context');
            check($requests() === 1, 'the two refusals are local');
        } finally {
            $stop();
        }
    });

// ---- StorageClientTest, TenantHooksTest (protocol 1.2, SDK 0.8.0) ----------------------------------------

const STORAGE_MANIFEST = '{"schemaVersion": 2, "slug": "fb-mod", "version": "0.1.0", "name": "app.name",'
    . ' "i18n": {"en": {"app.name": "Feedback"}},'
    . ' "capabilities": {"storage": [{"contentTypes": ["image/png", "image/jpeg"], "maxBytes": 5242880}]}}';

$test('storage: uploadUrl → {key, url, method, headers, expiresAt} with the three headers; size ≤ 0 and an undeclared type refused locally; 403 forbidden, 503 transient; downloadUrl → {url, expiresAt}, 404 not-found',
    static function () use ($servicePlatform, &$lines): void {
        $token = 'kfm_storage-token-never-logged';
        $proof = str_repeat('d', 64);
        [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('fb-mod', 'storage-secret', $token);
        $kf->withManifest(STORAGE_MANIFEST);
        $lines = [];
        try {
            $ctx = $verified('req-1', $proof, '"user":"sub-1","actor":{"type":"user","via":null,"hookId":null}', '[]');
            $key = 'kf-modules/fb-mod/' . SERVICE_TENANT . '/5b1f0c1e-1111-4222-8333-444455556666.png';
            $answer(200, '{"key":"' . $key . '","url":"https://s3.local/put?sig=1","method":"PUT",'
                . '"headers":{"Content-Type":"image/png","Content-Length":"1234"},"expiresAt":"2026-10-29T10:10:00Z"}');
            $up = $kf->storage()->uploadUrl($ctx, 'image/png', 1234);
            check($last()['line'] === 'POST /api/v1/platform/modules/fb-mod/storage/upload-url', $last()['line']);
            check(json_decode($last()['body'], true) === ['contentType' => 'image/png', 'size' => 1234], $last()['body']);
            check($last()['authorization'] === 'Bearer ' . $token, 'token');
            check($last()['proof'] === $proof, 'proof');
            check($up->key === $key && $up->url === 'https://s3.local/put?sig=1' && $up->method === 'PUT', 'upload');
            check($up->headers === ['Content-Type' => 'image/png', 'Content-Length' => '1234'], 'headers');
            check($up->expiresAt === '2026-10-29T10:10:00Z', 'expiresAt');

            $before = $requests();
            $zero = refused(fn () => $kf->storage()->uploadUrl($ctx, 'image/png', 0));
            check($zero->errorClass() === ErrorClass::PERMANENT && str_contains($zero->getMessage(), 'size'), 'size 0');
            refused(fn () => $kf->storage()->uploadUrl($ctx, 'image/png', -5));
            $pdf = refused(fn () => $kf->storage()->uploadUrl($ctx, 'application/pdf', 10));
            check(str_contains($pdf->getMessage(), 'application/pdf'), 'undeclared type');
            refused(fn () => $kf->storage()->downloadUrl($ctx, ' '));
            check($requests() === $before, 'nothing sent on a local refusal');

            $answer(403, '{"type":"https://kf.ro/problems/capability-not-declared","title":"capability-not-declared",'
                . '"status":403,"detail":"the module does not declare capabilities.storage","class":"permanent"}');
            $e = refused(fn () => $kf->storage()->uploadUrl($ctx, 'image/png', 10));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::FORBIDDEN, '403 forbidden');
            check($e->reason() === 'capability-not-declared' && $e->errorClass() === ErrorClass::PERMANENT, 'reason/class');
            check(!str_contains($e->getMessage(), $token) && !str_contains($e->getMessage(), $proof), 'token/proof in error');

            $answer(503, '{"type":"https://kf.ro/problems/storage-unavailable","title":"storage-unavailable","status":503}');
            $e = refused(fn () => $kf->storage()->uploadUrl($ctx, 'image/png', 10));
            check($e->errorClass() === ErrorClass::TRANSIENT && $e->problem()->status === 503, '503 transient');

            $answer(200, '{"url":"https://s3.local/get?sig=2","expiresAt":"2026-10-29T11:00:00Z"}');
            $down = $kf->storage()->downloadUrl($ctx, $key);
            check($last()['line'] === 'POST /api/v1/platform/modules/fb-mod/storage/download-url', $last()['line']);
            check(json_decode($last()['body'], true) === ['key' => $key], $last()['body']);
            check($down->url === 'https://s3.local/get?sig=2' && $down->expiresAt === '2026-10-29T11:00:00Z', 'download');

            $answer(404, '{"type":"https://kf.ro/problems/not-found","title":"not-found","status":404,"class":"permanent"}');
            $e = refused(fn () => $kf->storage()->downloadUrl($ctx, $key));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::NOT_FOUND && $e->problem()->status === 404, '404');
        } finally {
            $stop();
        }
        foreach ($lines as $line) check(!str_contains($line, $token) && !str_contains($line, $proof), 'token/proof in a log line');
    });

/** A tenant.after.delete envelope (TenantHooksTest), signed or not, sent to `$kf`. */
$tenantDelete = static function (KfModule $kf, ?string $entity, ?string $id, bool $validSignature): HookResult {
    $tenant = '7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b';
    $body = json_encode([
        'specversion' => '1.0', 'id' => 'd1e2f3a4-b5c6-4d7e-8f90-a1b2c3d4e5f6', 'kfeventid' => '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d',
        'type' => 'kf.tenant.after.delete', 'source' => '/tenant/' . $tenant, 'subject' => $tenant,
        'time' => '2026-10-29T00:55:01.123456Z', 'datacontenttype' => 'application/json', 'kfmodule' => 'fb-mod',
        'kfflags' => new \stdClass(), 'kftenant' => $tenant, 'kflocale' => 'ro', 'kfactor' => null, 'kfaggregatetype' => 'tenant',
        'kfenvelopeversion' => '1',
        'data' => ['entity' => $entity, 'id' => $id, 'before' => $id === null ? null : ['id' => $id], 'after' => null, 'changed' => []],
    ], JSON_UNESCAPED_SLASHES);
    $now = time();
    $sig = $validSignature ? Signatures::sign('tenant-hooks-secret', (string) $now, $body) : str_repeat('0', 64);
    return $kf->handle($body, (string) $now, $sig, $now);
};

$test('tenants: a signed tenant.after.delete → TenantDeleted(tenantId); wrong signature 401 before the handler; entity ≠ tenant or no id → 400 permanent; the same kfeventid twice → called twice; the subscription in the registration body; no handler → ignored',
    static function () use ($tenantDelete): void {
        $tenant = '7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b';
        $received = [];
        $kf = KfModule::of('http://127.0.0.1:1', 'fb-mod', 'kfm_t', 'tenant-hooks-secret', 'http://fb-mod:8080');
        $kf->tenants()->onDeleted(function (\Kf\Sdk\HookEvents\TenantDeleted $d) use (&$received): void {
            $received[] = $d;
        });

        $r = $tenantDelete($kf, 'tenant', $tenant, true);
        check($r->status === 200, 'ack ' . $r->body);
        check(count($received) === 1 && $received[0]->tenantId === $tenant, 'tenantId');
        check($received[0]->eventId === '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d', 'eventId');
        check($received[0]->deliveryId === 'd1e2f3a4-b5c6-4d7e-8f90-a1b2c3d4e5f6', 'deliveryId');
        check($received[0]->envelope->event === HookEvents::TENANT_AFTER_DELETE, 'event');

        check($tenantDelete($kf, 'tenant', $tenant, false)->status === 401, 'wrong signature');
        $wrong = $tenantDelete($kf, 'model', $tenant, true);
        check($wrong->status === 400 && json_decode($wrong->body, true)['class'] === 'permanent', 'wrong entity ' . $wrong->body);
        check($tenantDelete($kf, 'tenant', null, true)->status === 400, 'no id');
        check(count($received) === 1, 'no handler on a refusal');

        $tenantDelete($kf, 'tenant', $tenant, true);
        check(count($received) === 2, 'the same kfeventid calls the handler again');

        check($kf->registrationBody()['hooks'] === [['event' => 'tenant.after.delete']], 'registration');

        $bare = KfModule::of('http://127.0.0.1:1', 'fb-mod', 'kfm_t', 'tenant-hooks-secret', null);
        $ignored = $tenantDelete($bare, 'tenant', $tenant, true);
        check($ignored->status === 200 && str_contains($ignored->body, 'ignored'), 'ignored');
    });

// ---- TenantHooksTest, protocol 1.3 (SDK 0.9.0): the soft-delete and the restore -------------------------

/** A tenant point's envelope (protocol 1.3: data.after = {id} on the soft-delete and the restore), signed, sent to `$kf`. */
$tenantPoint = static function (KfModule $kf, string $point, ?string $entity, ?string $id): HookResult {
    $tenant = '7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b';
    $body = json_encode([
        'specversion' => '1.0', 'id' => 'd1e2f3a4-b5c6-4d7e-8f90-a1b2c3d4e5f6', 'kfeventid' => '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d',
        'type' => 'kf.' . $point, 'source' => '/tenant/' . $tenant, 'subject' => $tenant,
        'time' => '2026-10-29T00:55:01.123456Z', 'datacontenttype' => 'application/json', 'kfmodule' => 'fb-mod',
        'kfflags' => new \stdClass(), 'kftenant' => $tenant, 'kflocale' => 'ro', 'kfactor' => null, 'kfaggregatetype' => 'tenant',
        'kfenvelopeversion' => '1',
        'data' => ['entity' => $entity, 'id' => $id, 'before' => $id === null ? null : ['id' => $id],
            'after' => $id === null ? null : ['id' => $id], 'changed' => []],
    ], JSON_UNESCAPED_SLASHES);
    $now = time();
    return $kf->handle($body, (string) $now, Signatures::sign('tenant-hooks-secret', (string) $now, $body), $now);
};

/** A module that takes the tenant's whole lifecycle; its handlers record into `$state`. */
$lifecycleModule = static function (array &$state): KfModule {
    $kf = KfModule::of('http://127.0.0.1:1', 'fb-mod', 'kfm_t', 'tenant-hooks-secret', 'http://fb-mod:8080');
    $kf->tenants()
        ->onDeleted(function ($d) use (&$state): void { $state['received'][] = $d; })
        ->onSoftDelete(function (\Kf\Sdk\HookEvents\TenantSoftDeleted $e) use (&$state): void { $state['softDeleted'][] = $e; })
        ->onRestore(function (\Kf\Sdk\HookEvents\TenantRestored $e) use (&$state): void { $state['restored'][] = $e; });
    return $kf;
};

$test('protocol 1.3: tenant.after.soft-delete → onSoftDelete receives TenantSoftDeleted(tenantId, time); nothing deleted',
    static function () use ($tenantPoint, $lifecycleModule): void {
        $state = ['received' => [], 'softDeleted' => [], 'restored' => []];
        $kf = $lifecycleModule($state);
        $r = $tenantPoint($kf, 'tenant.after.soft-delete', 'tenant', '7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b');
        check($r->status === 200, 'ack ' . $r->body);
        check(count($state['softDeleted']) === 1, 'one soft-delete');
        $e = $state['softDeleted'][0];
        check($e->tenantId === '7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b', 'tenantId');
        check($e->time === '2026-10-29T00:55:01.123456Z', 'time');
        check($e->eventId === '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d', 'eventId');
        check($e->envelope->event === HookEvents::TENANT_AFTER_SOFT_DELETE, 'event');
        check($state['received'] === [] && $state['restored'] === [], 'a soft-delete is not the purge');
    });

$test('protocol 1.3: tenant.after.restore → onRestore receives TenantRestored(tenantId, time)',
    static function () use ($tenantPoint, $lifecycleModule): void {
        $state = ['received' => [], 'softDeleted' => [], 'restored' => []];
        $kf = $lifecycleModule($state);
        $r = $tenantPoint($kf, 'tenant.after.restore', 'tenant', '7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b');
        check($r->status === 200, 'ack ' . $r->body);
        check(count($state['restored']) === 1 && $state['restored'][0]->tenantId === '7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b', 'tenantId');
        check($state['restored'][0]->envelope->event === HookEvents::TENANT_AFTER_RESTORE, 'event');
        check($state['softDeleted'] === [], 'no soft-delete');
    });

$test('protocol 1.3: the soft-delete and restore envelopes are checked like tenant.after.delete (entity tenant, an id)',
    static function () use ($tenantPoint, $lifecycleModule): void {
        $state = ['received' => [], 'softDeleted' => [], 'restored' => []];
        $kf = $lifecycleModule($state);
        $wrong = $tenantPoint($kf, 'tenant.after.soft-delete', 'model', '7f3c0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b');
        $noId = $tenantPoint($kf, 'tenant.after.restore', 'tenant', null);
        check($wrong->status === 400 && str_contains($wrong->body, 'tenant.after.soft-delete'), 'wrong entity ' . $wrong->body);
        check(json_decode($wrong->body, true)['class'] === 'permanent', 'permanent');
        check($noId->status === 400, 'no id');
        check($state['softDeleted'] === [] && $state['restored'] === [], 'no handler on a refusal');
    });

$test('protocol 1.3: the registration subscribes to each point that has a handler, in the order delete, soft-delete, restore',
    static function () use ($lifecycleModule): void {
        $state = ['received' => [], 'softDeleted' => [], 'restored' => []];
        $kf = $lifecycleModule($state);
        check($kf->registrationBody()['hooks'] === [['event' => 'tenant.after.delete'], ['event' => 'tenant.after.soft-delete'],
            ['event' => 'tenant.after.restore']], 'registration');
    });

// ---- TenantDataExportTest (protocol 1.3 §15.8, SDK 0.9.0) ----------------------------------------------
// The bundle a module answers on GET /m/{slug}/admin/export is the one its admin/import takes back unchanged. The fixture
// is kf-feedback's own import fixture, read where the Java test reads it (one copy): its contentSha256 was computed by
// another implementation (Node) and is accepted by kf-feedback's TenantDataImport.

const TDE_TENANT = '00000000-0000-0000-0000-000000000001';
const TDE_SHA = 'a55243f587fc62451c3a3df811101b2d0e9a2f5089eb38c9ac2488309a658bec';

$tdeFixture = static fn (): \stdClass => Json::decode((string) file_get_contents(__DIR__
    . '/../../../java/kf-module-sdk/src/test/resources/ro/kf/sdk/tenant-data/tenant-data-sample.json'));

$test("the hash of kf-feedback's import fixture is the one it carries (the importers' canonical form)",
    static function () use ($tdeFixture): void {
        $bundle = $tdeFixture();
        check($bundle->contentSha256 === TDE_SHA, 'the fixture');
        check(\Kf\Sdk\TenantDataExport::contentSha256($bundle) === TDE_SHA, \Kf\Sdk\TenantDataExport::contentSha256($bundle));
    });

$test('a bundle built from the same content has every common field and the same hash, whatever exportedAt and source',
    static function () use ($tdeFixture): void {
        $f = $tdeFixture();
        $bundle = \Kf\Sdk\TenantDataExport::bundle('kf-feedback/tenant-data/v1', TDE_TENANT, 1, 1, $f->counts,
            ['tickets' => $f->tickets, 'warnings' => $f->warnings],
            exportedAt: new \DateTimeImmutable('2026-10-30T18:00:00Z'));
        check(array_keys($bundle) === ['schema', 'tenantId', 'exportedAt', 'part', 'parts', 'tickets', 'warnings', 'counts',
            'contentSha256'], implode(',', array_keys($bundle)));
        check($bundle['schema'] === 'kf-feedback/tenant-data/v1' && $bundle['tenantId'] === TDE_TENANT, 'schema, tenant');
        check($bundle['exportedAt'] === '2026-10-30T18:00:00Z', $bundle['exportedAt']);
        check($bundle['part'] === 1 && $bundle['parts'] === 1, 'part');
        check($bundle['contentSha256'] === TDE_SHA, 'exportedAt, source and warnings are not hashed');
        check(\Kf\Sdk\TenantDataExport::contentSha256($bundle) === TDE_SHA, 'recomputed');
    });

$test('a changed ticket changes the hash: the importer would refuse it (bundle-altered)', static function () use ($tdeFixture): void {
    $bundle = $tdeFixture();
    $bundle->tickets[0]->message = 'changed';
    check(\Kf\Sdk\TenantDataExport::contentSha256($bundle) !== TDE_SHA, 'changed');
});

$test('the canonical form: keys sorted at every level, compact, arrays kept in order, null kept', static function (): void {
    $value = ['b' => 1, 'a' => ['d' => true, 'c' => [3, 'x', ['z' => 1]]], 'n' => null, 's' => 'ă/"'];
    $got = \Kf\Sdk\TenantDataExport::canonicalJson($value);
    check($got === '{"a":{"c":[3,"x",{"z":1}],"d":true},"b":1,"n":null,"s":"ă/\""}', $got);
});

$test('part and parts: 1 ≤ part ≤ parts; the schema, the tenant and the counts are required', static function (): void {
    $counts = ['tickets' => 0];
    $content = ['tickets' => []];
    $schema = 'kf-feedback/tenant-data/v1';
    $bad = [
        static fn () => \Kf\Sdk\TenantDataExport::bundle($schema, TDE_TENANT, 0, 1, $counts, $content),
        static fn () => \Kf\Sdk\TenantDataExport::bundle($schema, TDE_TENANT, 3, 2, $counts, $content),
        static fn () => \Kf\Sdk\TenantDataExport::bundle($schema, TDE_TENANT, 1, 0, $counts, $content),
        static fn () => \Kf\Sdk\TenantDataExport::bundle(null, TDE_TENANT, 1, 1, $counts, $content),
        static fn () => \Kf\Sdk\TenantDataExport::bundle('feedback/v1', TDE_TENANT, 1, 1, $counts, $content),
        static fn () => \Kf\Sdk\TenantDataExport::bundle($schema, null, 1, 1, $counts, $content),
        static fn () => \Kf\Sdk\TenantDataExport::bundle($schema, TDE_TENANT, 1, 1, null, $content),
    ];
    foreach ($bad as $i => $call) {
        try {
            $call();
            check(false, "case $i must fail");
        } catch (\InvalidArgumentException) {
        }
    }
    $second = \Kf\Sdk\TenantDataExport::bundle($schema, TDE_TENANT, 2, 2, $counts, $content);
    check($second['part'] === 2 && trim($second['exportedAt']) !== '', 'second part');
});

$test('the content cannot carry a common key: it would overwrite the envelope', static function (): void {
    foreach (\Kf\Sdk\TenantDataExport::COMMON_KEYS as $key) {
        try {
            \Kf\Sdk\TenantDataExport::bundle('kf-qc/tenant-data/v1', TDE_TENANT, 1, 1, [], [$key => 'x']);
            check(false, "$key must be refused");
        } catch (\InvalidArgumentException $e) {
            check(str_contains($e->getMessage(), $key), $e->getMessage());
        }
    }
});

// ---- VersionExtensionTest (SDK 0.7.4) ------------------------------------------
// A version's values in the model's envelope (protocol 1.1, sdk-protocol.md §5.1): a
// module's write on model_version is an update of the version's model, with
// versions.{versionId}.extensions.{slug}.{field} in changed and the values under before/after.versions -- read through
// ModelSnapshot::versionExtension and the changed version paths.

/** The data of the example of §5.1, with a second field and a second version. */
const VX_DATA = <<<'JSON'
{ "entity": "model", "id": "42",
  "before": { "id": 42, "name": "Rochie Ana", "materialList": [],
              "extensions": { "kf-aas-r3": { "aas_shell": "model-shell" } },
              "versions": { "17": { "extensions": { "kf-aas-r3": { "aas_shell": null, "aas_pushed": false } } },
                            "18": { "extensions": { "kf-aas-r3": { "aas_shell": "old" } } } } },
  "after":  { "id": 42, "name": "Rochie Ana", "materialList": [],
              "extensions": { "kf-aas-r3": { "aas_shell": "model-shell" } },
              "versions": { "17": { "extensions": { "kf-aas-r3": { "aas_shell": "TechnicalPack-17", "aas_pushed": true } } },
                            "18": { "extensions": { "kf-aas-r3": { "aas_shell": "new" } } } } },
  "changed": [ "versions.17.extensions.kf-aas-r3.aas_shell", "versions.17.extensions.kf-aas-r3.aas_pushed",
               "versions.18.extensions.kf-aas-r3.aas_shell" ] }
JSON;

$vxEnvelope = static fn (): \Kf\Sdk\HookEvents\Envelope => \Kf\Sdk\HookEvents\Envelope::of((object) ['type' => 'kf.model.before.update',
    'id' => 'd-1', 'kfeventid' => 'e-1', 'kftenant' => 't-1', 'kfenvelopeversion' => '1', 'kfmodule' => 'conformance']);
$vf = static fn (string $versionId, string $slug, string $field): \Kf\Sdk\HookEvents\VersionField
    => new \Kf\Sdk\HookEvents\VersionField($versionId, $slug, $field);

$test("versionExtension reads versions.{id}.extensions.{slug}.{field} on before and after; the model's own extensions stay as stored", function (): void {
    $data = Json::decode(VX_DATA);
    $before = \Kf\Sdk\ModelSnapshot::fromMap($data->before);
    $after = \Kf\Sdk\ModelSnapshot::fromMap($data->after);

    check($before->versionExtension('17', 'kf-aas-r3', 'aas_shell') === null, 'before 17 aas_shell');
    check($after->versionExtension('17', 'kf-aas-r3', 'aas_shell') === 'TechnicalPack-17', 'after 17 aas_shell');
    check($before->versionExtension('17', 'kf-aas-r3', 'aas_pushed') === false, 'before 17 aas_pushed');
    check($after->versionExtension('17', 'kf-aas-r3', 'aas_pushed') === true, 'after 17 aas_pushed');
    check($before->versionExtension('18', 'kf-aas-r3', 'aas_shell') === 'old', 'before 18');
    check($after->versionExtension('18', 'kf-aas-r3', 'aas_shell') === 'new', 'after 18');
    check($after->extension('kf-aas-r3', 'aas_shell') === 'model-shell', 'the model value');
    check(array_map('strval', array_keys($after->versionExtensions)) === ['17', '18'], 'the versions');
});

$test('versionExtension is null for a version, a module or a field the snapshot does not carry, and an envelope without versions reads empty', function (): void {
    $after = \Kf\Sdk\ModelSnapshot::fromMap(Json::decode(VX_DATA)->after);
    check($after->versionExtension('19', 'kf-aas-r3', 'aas_shell') === null, 'version');
    check($after->versionExtension('17', 'other-module', 'aas_shell') === null, 'module');
    check($after->versionExtension('17', 'kf-aas-r3', 'missing') === null, 'field');

    $plain = \Kf\Sdk\ModelSnapshot::fromMap(Json::decode('{ "after": { "id": 42, "name": "Rochie", "materialList": [], "extensions": {} } }')->after);
    check($plain->versionExtensions === [], 'no versions');
    check($plain->versionExtension('17', 'kf-aas-r3', 'aas_shell') === null, 'no versions: null');

    $odd = \Kf\Sdk\ModelSnapshot::fromMap(Json::decode('{ "after": { "versions": { "17": "not-an-object", "18": { "extensions": "not-an-object" },
        "19": { "extensions": { "kf-aas-r3": "not-an-object" } } } } }')->after);
    check($odd->versionExtensions === [], 'shapes that are not objects are skipped: ' . Json::encode($odd->versionExtensions));
});

$test('VersionField.parse reads versions.{versionId}.extensions.{slug}.{field} and nothing else; path() writes it back', function () use ($vf): void {
    $f = \Kf\Sdk\HookEvents\VersionField::parse('versions.17.extensions.kf-aas-r3.aas_shell');
    check($f == $vf('17', 'kf-aas-r3', 'aas_shell'), 'parsed');
    check($f->path() === 'versions.17.extensions.kf-aas-r3.aas_shell', 'path');

    foreach (['name', 'materialList', 'extensions.kf-aas-r3.aas_shell',
        'versions.17.extensions.kf-aas-r3', 'versions.17.extensions.kf-aas-r3.aas_shell.more',
        'versions..extensions.kf-aas-r3.aas_shell', 'versions.17.other.kf-aas-r3.aas_shell',
        'versions.17.extensions..aas_shell', 'versions.17.extensions.kf-aas-r3.', 'colors.17.extensions.a.b', ''] as $notOne) {
        check(\Kf\Sdk\HookEvents\VersionField::parse($notOne) === null, $notOne);
    }
    check(\Kf\Sdk\HookEvents\VersionField::parse(null) === null, 'null');
});

$test("a before hook sees the changed version paths and the versions they touch, in order, without the model's own paths", function () use ($vxEnvelope, $vf): void {
    $ctx = new \Kf\Sdk\BeforeContext('conformance', \Kf\Sdk\HookEvents::BEFORE_UPDATE, $vxEnvelope(), Json::decode(VX_DATA));

    check($ctx->changedVersionFields() == [$vf('17', 'kf-aas-r3', 'aas_shell'), $vf('17', 'kf-aas-r3', 'aas_pushed'),
        $vf('18', 'kf-aas-r3', 'aas_shell')], 'changedVersionFields');
    check($ctx->changedVersionIds() === ['17', '18'], 'changedVersionIds');
    check($ctx->after->versionExtension('17', 'kf-aas-r3', 'aas_shell') === 'TechnicalPack-17', 'after');

    $model = new \Kf\Sdk\BeforeContext('conformance', \Kf\Sdk\HookEvents::BEFORE_UPDATE, $vxEnvelope(),
        Json::decode('{ "id": "42", "changed": ["name", "extensions.conformance.author"] }'));
    check($model->changedVersionFields() === [], 'model: no version fields');
    check($model->changedVersionIds() === [], 'model: no versions');
});

$test('an after event sees the same, and a field change names its version path (null for a model path)', function () use ($vxEnvelope, $vf): void {
    $data = Json::decode(VX_DATA);
    $after = \Kf\Sdk\HookEvents\AfterEvent::of(\Kf\Sdk\HookEvents::AFTER_UPDATE, $vxEnvelope(), $data);
    check($after->changedVersionIds() === ['17', '18'], 'changedVersionIds');
    check(count($after->changedVersionFields()) === 3, 'changedVersionFields');
    check($after->after->versionExtension('18', 'kf-aas-r3', 'aas_shell') === 'new', 'after');

    $data->field = 'versions.18.extensions.kf-aas-r3.aas_shell';
    $change = \Kf\Sdk\HookEvents\FieldChange::of($vxEnvelope(), $data);
    check($change->versionField() == $vf('18', 'kf-aas-r3', 'aas_shell'), 'versionField');

    $data->field = 'name';
    check(\Kf\Sdk\HookEvents\FieldChange::of($vxEnvelope(), $data)->versionField() === null, 'model path');
});

// ---- run ----------------------------------------------------------------------------------------------------------

// ---- ManifestContract12Test (protocol 1.2, SDK 0.8.0) ------------------------------------------------
// The keys protocol 1.2 adds to kf-module-conf.json (additive, no new schemaVersion):
// routes[].audience ("member"), routes[].portal (only on audience member), the top-level activation ("tenant" by
// default, "platform"), capabilities.storage (at most one element {contentTypes, maxBytes}) and the hook point
// tenant.after.delete. Refused locally with the texts the platform uses at upload; a 1.1 manifest is unchanged.

$c12I18n = <<<'JSON'
"i18n":{"en":{"module.name":"Feedback","nav.feedback":"Feedback","widget.label":"Feedback",
             "page.label":"Feedback triage"},"ro":{"module.name":"Feedback"}}
JSON;
$c12Routes = <<<'JSON'
{"method":"POST","path":"/m/kf-feedback/tickets","right":"api.v1.m.kf-feedback.tickets.post","audience":"member","portal":true},
            {"method":"POST","path":"/m/kf-feedback/screenshots","right":"api.v1.m.kf-feedback.screenshots.post","audience":"member","portal":true},
            {"method":"GET","path":"/m/kf-feedback/admin/tickets","right":"api.v1.m.kf-feedback.admin.tickets.get","admin":"platform"},
            {"method":"PATCH","path":"/m/kf-feedback/admin/tickets/{id}/resolve","right":"api.v1.m.kf-feedback.admin.tickets.patch","admin":"platform"},
            {"method":"PATCH","path":"/m/kf-feedback/admin/tickets/{id}/reopen","right":"api.v1.m.kf-feedback.admin.tickets.patch","admin":"platform"},
            {"method":"GET","path":"/m/kf-feedback/admin/tickets/{id}/screenshot-url","right":"api.v1.m.kf-feedback.admin.screenshots.get","admin":"platform"},
            {"method":"GET","path":"/m/kf-feedback/admin/github","right":"api.v1.m.kf-feedback.admin.github.get","admin":"platform"},
            {"method":"PUT","path":"/m/kf-feedback/admin/github","right":"api.v1.m.kf-feedback.admin.github.put","admin":"platform"},
            {"method":"POST","path":"/m/kf-feedback/admin/tickets/{id}/github","right":"api.v1.m.kf-feedback.admin.tickets.post","admin":"platform","timeoutSeconds":30},
            {"method":"POST","path":"/m/kf-feedback/admin/import","right":"api.v1.m.kf-feedback.admin.import.post","admin":"platform","timeoutSeconds":60}
JSON;
$c12Caps = <<<'JSON'
"hooks":[{"point":"tenant.after.delete"}],
            "storage":[{"contentTypes":["image/jpeg","image/png"],"maxBytes":5242880}],
            "reads":[{"port":"tenant.v1"}],
            "audit":[{"access":"write"}],
            "secrets":[{"name":"github-token","scope":"platform","writeRight":"api.v1.m.kf-feedback.admin.github.put"}],
            "surfaces":[{"slug":"app.overlay","kind":"react","label":"widget.label"},
                        {"slug":"setup.sections","kind":"react","label":"page.label","page":"feedback"}],
            "nav":[{"surface":"platform","group":"platform-main","key":"feedback","label":"nav.feedback",
                    "url":"/setup/m/kf-feedback/feedback","icon":"MessageSquare","order":15}]
JSON;
$c12Feedback = '{"schemaVersion":2,"slug":"kf-feedback","name":"module.name","version":"0.1.0","rollbackFloor":"0.1.0",'
    . '"platform":">=4.1","activation":"platform","author":"Katty Fashion",' . $c12I18n
    . ',"capabilities":{"routes":[' . $c12Routes . '],' . $c12Caps . '}}';
$c12Manifest = static function (?string $root, string $routes, ?string $extra) use ($c12I18n): string {
    return '{"schemaVersion":2,"slug":"c12","version":"1.0.0","platform":">=4.1","name":"module.name"'
        . ($root === null ? '' : ',' . $root) . ',' . $c12I18n . ',"capabilities":{"routes":[' . $routes . ']'
        . ($extra === null ? '' : ',' . $extra) . '}}';
};
$c12Route = static fn (?string $extra): string => '{"method":"POST","path":"/m/c12/tickets","right":"api.v1.m.c12.tickets.post"'
    . ($extra === null ? '' : ',' . $extra) . '}';
$c12Storage = static fn (string $elements): string => '"storage":[' . $elements . ']';
$c12Accepted = static function (string $json): void {
    $result = ManifestCheck::validate($json);
    check($result->ok(), json_encode($result->errors));
};
$c12Refused = static function (string $json, string $expected): void {
    $errors = ManifestCheck::validate($json)->errors;
    check($errors !== [], $json);
    $found = false;
    foreach ($errors as $e) $found = $found || str_contains($e, $expected);
    check($found, $expected . ' not in ' . json_encode($errors));
};

$test('the kf-feedback manifest (every 1.2 key) is accepted and read typed', static function () use ($c12Feedback, $c12Accepted): void {
    $c12Accepted($c12Feedback);
    $m = Manifest::parse($c12Feedback);
    check($m->activation === \Kf\Sdk\Manifest\Activation::PLATFORM, 'activation');
    check($m->activation->wire() === 'platform', 'wire');
    $tickets = $m->capabilities->routes[0];
    check($tickets->audience === Manifest::ROUTE_AUDIENCE_MEMBER && $tickets->portal === true && $tickets->roles === [], 'tickets');
    $triage = $m->capabilities->routes[2];
    check($triage->audience === null && $triage->portal === false && $triage->admin === 'platform', 'triage');
    check(count($m->capabilities->storage) === 1, 'storage');
    check($m->capabilities->storage[0]->contentTypes === ['image/jpeg', 'image/png'], 'contentTypes');
    check($m->capabilities->storage[0]->maxBytes === 5242880, 'maxBytes');
    check($m->capabilities->hooks[0]->point === \Kf\Sdk\HookEvents::TENANT_AFTER_DELETE, 'point');
    check(\Kf\Sdk\HookEvents::TENANT_AFTER_DELETE === 'tenant.after.delete', 'constant');
    check(in_array('storage', Manifest::CAPABILITY_KEYS, true), 'CAPABILITY_KEYS');
});

$test('without the 1.2 keys: activation reads tenant, audience null, portal false, storage []', static function () use ($c12Manifest, $c12Route, $c12Accepted): void {
    $json = $c12Manifest(null, $c12Route(null), null);
    $c12Accepted($json);
    $m = Manifest::parse($json);
    check($m->activation === \Kf\Sdk\Manifest\Activation::TENANT, 'activation');
    check($m->capabilities->routes[0]->audience === null && $m->capabilities->routes[0]->portal === false, 'route');
    check($m->capabilities->storage === [], 'storage');
});

$test('the 1.1 example of sdk-protocol.md §3 (its texts defined) is accepted unchanged under SDK 0.8.0', static function () use ($c12Accepted): void {
    $json = <<<'JSON'
{"schemaVersion":2,"slug":"api-test","name":"module.name","description":"module.description",
                 "version":"0.5.0","rollbackFloor":"0.4.0","platform":">=4.1","author":"Katty Fashion",
                 "featureFlags":[{"key":"author-tab","label":"flag.author-tab.label","description":"flag.author-tab.desc","default":false}],
                 "requires":[{"slug":"other","versionRange":">=1.0.0"}],
                 "i18n":{"en":{"module.name":"API Test","module.description":"Notes and an author tab on models",
                   "field.priority":"Priority","flag.author-tab.label":"Author tab","flag.author-tab.desc":"Shows the author",
                   "tab.label":"Author","page.label":"Shells","n.subject":"Author changed","n.body":"{modelName}",
                   "nav.notes":"Notes","setting.push":"Push on save","setting.timeout":"Timeout","setting.mode":"Mode"},
                  "ro":{"module.name":"Test API"}},
                 "capabilities":{
                  "fields":[{"entity":"order","name":"priority_code","type":"STRING","table":"orders_extra","label":"field.priority","editable":true}],
                  "hooks":[{"point":"model.before.update","onFailure":"block"}],
                  "routes":[{"method":"POST","path":"/m/api-test/notes","right":"api.v1.m.api-test.notes.post","roles":["QC"],"hookable":true},
                            {"method":"GET","path":"/m/api-test/stations","right":"api.v1.m.api-test.stations.get"},
                            {"method":"PUT","path":"/m/api-test/admin/r3-keys","right":"api.v1.m.api-test.admin.r3-keys.put","admin":"platform"},
                            {"method":"GET","path":"/m/api-test/admin/shells","right":"api.v1.m.api-test.admin.shells.get","admin":"tenant"}],
                  "surfaces":[{"slug":"models.detail.tabs","kind":"react","label":"tab.label"},
                              {"slug":"module.pages","kind":"react","label":"page.label","page":"shells"}],
                  "notifications":[{"key":"author-changed","variables":["modelName"],"channels":["in_app"],"subject":"n.subject","body":"n.body"}],
                  "reads":[{"port":"model.v1"},{"port":"file.public-url.v1"}],
                  "audit":[{"access":"write"},{"access":"read-own"}],
                  "nav":[{"surface":"staff","group":"admin-utils","key":"notes","label":"nav.notes","url":"/modules","icon":"Boxes","right":"api.v1.m.api-test.notes.post","order":20,"flag":"author-tab"}],
                  "settings":[{"key":"push-on-save","type":"BOOLEAN","default":false,"label":"setting.push"},
                              {"key":"timeout-seconds","type":"NUMBER","default":30,"min":5,"max":600,"label":"setting.timeout"},
                              {"key":"mode","type":"ENUM","default":"auto","values":["auto","manual"],"label":"setting.mode"}],
                  "secrets":[{"name":"r3-client-secret","scope":"platform","writeRight":"api.v1.m.api-test.admin.r3-keys.put"}],
                  "provides":[{"port":"factory.stations.v1","path":"/m/api-test/stations"}]}}
JSON;
    $c12Accepted($json);
    $m = Manifest::parse($json);
    check($m->activation === \Kf\Sdk\Manifest\Activation::TENANT, 'activation');
    check($m->capabilities->storage === [], 'storage');
    foreach ($m->capabilities->routes as $r) check($r->audience === null && !$r->portal, 'route');
});

$test('audience member without roles, admin or hookable is accepted (an empty roles list too)', static function () use ($c12Manifest, $c12Route, $c12Accepted): void {
    $c12Accepted($c12Manifest(null, $c12Route('"audience":"member","roles":[],"hookable":false'), null));
    $c12Accepted($c12Manifest(null, $c12Route('"audience":null'), null));
});

$test('an audience other than member is refused, naming the value', static function () use ($c12Manifest, $c12Route, $c12Refused): void {
    $c12Refused($c12Manifest(null, $c12Route('"audience":"everyone"'), null), '.audience must be "member" (got "everyone")');
    $c12Refused($c12Manifest(null, $c12Route('"audience":true'), null), '.audience must be "member" (got true)');
});

$test('audience member with roles, admin or hookable is refused', static function () use ($c12Manifest, $c12Route, $c12Refused): void {
    $rule = ': audience member excludes roles, admin and hookable (any member of the tenant calls it)';
    foreach (['"roles":["QC"]', '"admin":"tenant"', '"admin":"platform"', '"hookable":true'] as $extra) {
        $c12Refused($c12Manifest(null, $c12Route('"audience":"member",' . $extra), null), $rule);
    }
});

$test('portal false is accepted on any route; portal true only on audience member', static function () use ($c12Manifest, $c12Route, $c12Accepted, $c12Refused): void {
    $c12Accepted($c12Manifest(null, $c12Route('"portal":false'), null));
    $c12Accepted($c12Manifest(null, $c12Route('"audience":"member","portal":true'), null));
    $c12Refused($c12Manifest(null, $c12Route('"portal":true'), null), '.portal must be a boolean, and only on audience member (got true');
});

$test('a portal that is not a boolean is refused (null too)', static function () use ($c12Manifest, $c12Route, $c12Refused): void {
    $c12Refused($c12Manifest(null, $c12Route('"audience":"member","portal":"yes"'), null),
        '.portal must be a boolean, and only on audience member (got "yes")');
    $c12Refused($c12Manifest(null, $c12Route('"audience":"member","portal":null'), null),
        '.portal must be a boolean, and only on audience member (got null)');
});

$test('activation tenant, platform and null are accepted; null reads tenant', static function () use ($c12Manifest, $c12Route, $c12Accepted): void {
    $c12Accepted($c12Manifest('"activation":"tenant"', $c12Route(null), null));
    $c12Accepted($c12Manifest('"activation":"platform"', $c12Route(null), null));
    $json = $c12Manifest('"activation":null', $c12Route(null), null);
    $c12Accepted($json);
    check(Manifest::parse($json)->activation === \Kf\Sdk\Manifest\Activation::TENANT, 'null reads tenant');
});

$test('another activation is refused, naming the value', static function () use ($c12Manifest, $c12Route, $c12Refused): void {
    $c12Refused($c12Manifest('"activation":"global"', $c12Route(null), null),
        'kf-module-conf.json: activation must be "tenant" or "platform" (got "global")');
    $c12Refused($c12Manifest('"activation":true', $c12Route(null), null), 'activation must be "tenant" or "platform" (got true)');
});

$test('one storage element with 1 to 10 types and 1 to 10485760 bytes is accepted', static function () use ($c12Manifest, $c12Route, $c12Storage, $c12Accepted): void {
    $c12Accepted($c12Manifest(null, $c12Route(null), $c12Storage('{"contentTypes":["application/pdf"],"maxBytes":1}')));
    $c12Accepted($c12Manifest(null, $c12Route(null), $c12Storage('{"contentTypes":["image/jpeg","image/png","image/webp","image/gif",'
        . '"image/avif","image/svg+xml","application/pdf","text/csv","application/vnd.ms-excel","application/json"],"maxBytes":10485760}')));
    $c12Accepted($c12Manifest(null, $c12Route(null), '"storage":[]'));
});

$test('more than one storage element is refused', static function () use ($c12Manifest, $c12Route, $c12Storage, $c12Refused): void {
    $one = '{"contentTypes":["image/png"],"maxBytes":100}';
    $c12Refused($c12Manifest(null, $c12Route(null), $c12Storage("$one,$one")), 'kf-module-conf.json: capabilities.storage holds at most one element');
});

$test('contentTypes empty, with a wildcard, duplicated, over 10 or not type/subtype is refused', static function () use ($c12Manifest, $c12Route, $c12Storage, $c12Refused): void {
    $rule = 'capabilities.storage[0].contentTypes must be 1 to 10 distinct type/subtype values, without wildcards';
    foreach (['[]', '["image/*"]', '["*/*"]', '["image/png","image/png"]', '["image"]', '["Image/PNG"]', '[1]', '"image/png"',
        '["a/a","a/b","a/c","a/d","a/e","a/f","a/g","a/h","a/i","a/j","a/k"]'] as $types) {
        $c12Refused($c12Manifest(null, $c12Route(null), $c12Storage('{"contentTypes":' . $types . ',"maxBytes":100}')), $rule);
    }
    $c12Refused($c12Manifest(null, $c12Route(null), $c12Storage('{"maxBytes":100}')), $rule);
});

$test('maxBytes 0, negative, fractional, over 10485760, text or absent is refused, naming the value', static function () use ($c12Manifest, $c12Route, $c12Storage, $c12Refused): void {
    $rule = 'capabilities.storage[0].maxBytes must be a whole number of bytes between 1 and 10485760 (got ';
    foreach (['0', '-1', '5.5', '10485761', '"100"', 'null'] as $bytes) {
        $c12Refused($c12Manifest(null, $c12Route(null), $c12Storage('{"contentTypes":["image/png"],"maxBytes":' . $bytes . '}')), $rule . $bytes . ')');
    }
    $c12Refused($c12Manifest(null, $c12Route(null), $c12Storage('{"contentTypes":["image/png"]}')), $rule . 'null)');
});

$test('a storage element is a closed object', static function () use ($c12Manifest, $c12Route, $c12Storage, $c12Refused): void {
    $c12Refused($c12Manifest(null, $c12Route(null), $c12Storage('{"contentTypes":["image/png"],"maxBytes":100,"bucket":"x"}')),
        'capabilities.storage[0]: "bucket" is not a key of a storage element (known: contentTypes, maxBytes)');
    $c12Refused($c12Manifest(null, $c12Route(null), $c12Storage('"image/png"')), 'capabilities.storage[0] must be an object');
});

$test('the hook point tenant.after.delete is declared; onFailure is refused on it (an after point)', static function () use ($c12Manifest, $c12Route, $c12Accepted, $c12Refused): void {
    $c12Accepted($c12Manifest(null, $c12Route(null), '"hooks":[{"point":"' . \Kf\Sdk\HookEvents::TENANT_AFTER_DELETE . '"}]'));
    $c12Refused($c12Manifest(null, $c12Route(null), '"hooks":[{"point":"tenant.after.delete","onFailure":"block"}]'),
        '.onFailure only on before points');
});

// ---- ContextPersonTest (protocol 1.2) ------------------------------------------------------------------
// person in the signed context (sdk-protocol.md §9.2): the last key, only on audience member
// routes, {"email": <text|null>, "displayName": <text|null>}; absent elsewhere and on a system context; never a ground
// for authorization (the identity stays user); a context without it is read as before.

const PERSON_SECRET = 'person-secret';
const PERSON_NOW = 1760000000;
const PERSON_USER = '{"type":"user","via":null,"hookId":null}';
const PERSON_HOOK = '{"type":"system","via":"hook","hookId":"h-1"}';

$personVerify = static function (string $user, string $actor, ?string $person): RequestContext {
    $json = '{"v":1,"tenant":"t-1","user":' . $user . ',"actor":' . $actor . ',"locale":"ro","rights":[],"method":"POST",'
        . '"path":"/tickets","query":null,"ts":' . PERSON_NOW . ',"requestId":"r-1"' . ($person === null ? '' : ',"person":' . $person) . '}';
    $context = rtrim(strtr(base64_encode($json), '+/', '-_'), '=');
    $signature = Signatures::sign(PERSON_SECRET, (string) PERSON_NOW, $context . '.');
    return RequestContext::verify($context, (string) PERSON_NOW, $signature, '', PERSON_SECRET, PERSON_NOW);
};
$personRefused = static function (string $user, string $actor, string $person, string $reason) use ($personVerify): void {
    $rejected = null;
    try {
        $personVerify($user, $actor, $person);
    } catch (\Kf\Sdk\Rejected $e) {
        $rejected = $e;
    }
    check($rejected !== null, 'expected Rejected');
    check($rejected->problem()->status === 401, 'status 401');
    check($rejected->problem()->type === 'https://kf.ro/problems/invalid-context', 'type');
    check($rejected->problem()->errorClass === ErrorClass::PERMANENT, 'permanent');
    check(str_contains((string) $rejected->problem()->detail, $reason), $reason . ' not in ' . $rejected->problem()->detail);
};

$test('a member route context carries person: e-mail and display name are read', static function () use ($personVerify): void {
    $ctx = $personVerify('"sub-1"', PERSON_USER, '{"email":"ana@kf.ro","displayName":"Ana Pop"}');
    check($ctx->person == new \Kf\Sdk\Person('ana@kf.ro', 'Ana Pop'), 'person');
    check($ctx->user === 'sub-1', 'user');
});

$test('person with null fields is accepted; a missing field reads null', static function () use ($personVerify): void {
    check($personVerify('"sub-1"', PERSON_USER, '{"email":null,"displayName":"Ana Pop"}')->person == new \Kf\Sdk\Person(null, 'Ana Pop'), 'email null');
    check($personVerify('"sub-1"', PERSON_USER, '{"email":"ana@kf.ro"}')->person == new \Kf\Sdk\Person('ana@kf.ro', null), 'displayName missing');
});

$test('a context without person is accepted and reads person null (every route but member, a platform before 1.2)', static function () use ($personVerify): void {
    check($personVerify('"sub-1"', PERSON_USER, null)->person === null, 'absent');
    check($personVerify('"sub-1"', PERSON_USER, 'null')->person === null, 'null');
});

$test('person that is not an object, or with a field that is not text, is refused', static function () use ($personRefused): void {
    $personRefused('"sub-1"', PERSON_USER, '"ana@kf.ro"', 'context person must be an object');
    $personRefused('"sub-1"', PERSON_USER, '{"email":42,"displayName":"Ana"}', 'context person.email must be text or null');
    $personRefused('"sub-1"', PERSON_USER, '{"email":"a@kf.ro","displayName":[]}', 'context person.displayName must be text or null');
});

$test('a system context with person is refused', static function () use ($personRefused): void {
    $personRefused('null', PERSON_HOOK, '{"email":"ana@kf.ro","displayName":"Ana Pop"}', 'a system context carries no person');
});

$test('the constructor with the eleven components of SDK 0.7.x still builds a context, without person', static function (): void {
    $ctx = new RequestContext(1, 't', 'u', Actor::user(), 'en', [], 'POST', '/n', null, PERSON_NOW, 'r');
    check($ctx->person === null, 'person');
});

// ---- ManifestSlotsTest (protocol 2.0, schemaVersion 3, SDK 0.10.0) ----------------------------------
// The module slots capabilities.slots (sdk-protocol.md §7.12, called "§7.11" by the plans).
// Schema 3 is a strict superset of 2; slots is refused under 1–2; every rule of the contract has an accepted case and a
// refused one.

$slotsManifest = static function (int $schemaVersion, ?string $slots): string {
    return '{"schemaVersion":' . $schemaVersion . ',"slug":"kf-3d","version":"1.0.0","platform":">=4.1","name":"module.name",'
        . '"i18n":{"en":{"module.name":"Slots","slot.layers":"Viewport layers"}}'
        . "\n " . ($slots === null ? '' : ',"capabilities":{"slots":[' . $slots . ']}') . "}\n";
};
const SLOT_LAYERS = '{"key":"viewport.layers.v1","kind":"B","type":"layer","visibility":"public",'
    . '"label":"slot.layers","layerKinds":["pin","highlight"]}';
const SLOT_INSPECTOR = '{"key":"studio.inspector.sections.v1","kind":"A","type":"extension","visibility":"internal",'
    . '"publicMembers":["model","version"],"actions":["select-piece"]}';
const SLOT_EVENTS = '{"key":"viewport.events.v1","kind":"D","type":"events","visibility":"public",'
    . '"events":["piece-selected"]}';
const SLOT_MATERIALS = '{"key":"materials.v1","kind":"R","type":"resource","visibility":"public",'
    . '"dataSchema":"m.kf-3d.material.v1"}';
$slotsErrors = static fn (string $json): array => ManifestCheck::validate($json)->errors;
$slotsRefused = static function (string $json, string $fragment) use ($slotsErrors): void {
    $errors = $slotsErrors($json);
    $found = false;
    foreach ($errors as $e) $found = $found || str_contains($e, $fragment);
    check($found, "an error must contain <$fragment>: " . json_encode($errors));
};

$test('schema 3 is a strict superset of 2: a valid schema 2 manifest is valid with only the number changed', static function () use ($slotsManifest, $slotsErrors): void {
    check($slotsErrors($slotsManifest(2, null)) === [], json_encode($slotsErrors($slotsManifest(2, null))));
    check($slotsErrors($slotsManifest(3, null)) === [], json_encode($slotsErrors($slotsManifest(3, null))));
});

$test('schemaVersion 4 is refused locally, naming the interval 1..3', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(4, null), 'supported: 1..3');
});

$test('every kind of slot, used correctly, is accepted under schema 3', static function () use ($slotsManifest, $slotsErrors): void {
    $json = $slotsManifest(3, implode(',', [SLOT_LAYERS, SLOT_INSPECTOR, SLOT_EVENTS, SLOT_MATERIALS]));
    check($slotsErrors($json) === [], json_encode($slotsErrors($json)));
});

$test('capabilities.slots under schema 2 is refused: it requires schemaVersion 3', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(2, SLOT_LAYERS), 'requires "schemaVersion": 3');
});

$test('a key without its major as last segment is refused', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, str_replace('viewport.layers.v1', 'viewport.layers', SLOT_LAYERS)), 'capabilities.slots[0].key');
});

$test('a key declared twice is refused', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, SLOT_LAYERS . ',' . SLOT_LAYERS), 'is declared twice');
});

$test("a type that is not one of the kind's is refused", static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, str_replace('"type":"layer"', '"type":"tab"', SLOT_LAYERS)), 'capabilities.slots[0].type');
});

$test('visibility is mandatory, without a default', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, str_replace('"visibility":"public",', '', SLOT_LAYERS)), 'capabilities.slots[0].visibility');
});

$test('a slot of kind B without layerKinds is refused', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, str_replace(',"layerKinds":["pin","highlight"]', '', SLOT_LAYERS)),
        'capabilities.slots[0].layerKinds');
});

$test('a slot of kind R without dataSchema is refused (data only)', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, str_replace(',"dataSchema":"m.kf-3d.material.v1"', '', SLOT_MATERIALS)),
        'capabilities.slots[0].dataSchema');
});

$test('a key outside the closed object of a slot is refused', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, str_replace('"kind":"B"', '"kind":"B","code":"x.js"', SLOT_LAYERS)),
        '"code" is not a key of a slot');
});

$test('a key of another kind is refused (layerKinds only on kind B)', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, str_replace('"actions"', '"layerKinds":["pin"],"actions"', SLOT_INSPECTOR)), 'only on kind B');
});

$test('events on a commands slot and a dataSchema of another owner are refused', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, str_replace('"type":"events"', '"type":"commands"', SLOT_EVENTS)), 'capabilities.slots[0].commands');
    $slotsRefused($slotsManifest(3, str_replace('m.kf-3d.material.v1', 'm.other.material.v1', SLOT_MATERIALS)),
        'capabilities.slots[0].dataSchema');
});

$test('a label that is not a key of i18n.en is refused', static function () use ($slotsManifest, $slotsRefused): void {
    $slotsRefused($slotsManifest(3, str_replace('slot.layers', 'slot.missing', SLOT_LAYERS)), 'capabilities.slots[0].label');
});

// ---- ManifestRequiresTest (protocol 2.0, SDK 0.10.0) -------------------------------------------------
// requires and featureFlags[].requiresModules checked locally, with the platform's messages
// (ModulePackValidator.validateRequires, ModuleManifestValidator): the shape only -- whether the required module
// exists, a cycle or stay the platform's.

$reqManifest = static function (int $schemaVersion, string $requires, ?string $flags): string {
    return '{"schemaVersion":' . $schemaVersion . ',"slug":"req-mod","version":"1.0.0","platform":">=4.1","name":"module.name",'
        . "\n " . '"i18n":{"en":{"module.name":"Requires","flag.l":"Flag"}},'
        . "\n " . '"requires":[' . $requires . '],"featureFlags":[' . ($flags ?? '') . "]}\n";
};
const REQ_BASE = '{"slug":"base-mod","versionRange":">=1.0.0 <2.0.0"}';
const REQ_OPTIONAL = '{"slug":"extra-mod","versionRange":">=0.5.0","optional":true}';

$test('two modules, one optional, under schema 3: valid', static function () use ($reqManifest, $slotsErrors): void {
    $json = $reqManifest(3, REQ_BASE . ',' . REQ_OPTIONAL, null);
    check($slotsErrors($json) === [], json_encode($slotsErrors($json)));
});

$test('a slug that is not one, the module itself, a duplicate: refused', static function () use ($reqManifest, $slotsRefused): void {
    $slotsRefused($reqManifest(2, '{"slug":"Bad Slug","versionRange":">=1.0.0"}', null), '"requires" slug must match');
    $slotsRefused($reqManifest(2, '{"slug":"req-mod","versionRange":">=1.0.0"}', null), 'a module cannot require itself');
    $slotsRefused($reqManifest(2, REQ_BASE . ',' . REQ_BASE, null), 'duplicate "requires" entry for "base-mod"');
});

$test('a range the platform cannot parse, or that no version can satisfy: refused with the expected form', static function () use ($reqManifest, $slotsRefused): void {
    $slotsRefused($reqManifest(2, '{"slug":"base-mod","versionRange":"~1.2"}', null), 'could not be parsed');
    $slotsRefused($reqManifest(2, '{"slug":"base-mod","versionRange":">=2.0.0 <1.0.0"}', null), 'version range is impossible');
    $slotsRefused($reqManifest(2, '{"slug":"base-mod","versionRange":""}', null), 'needs a non-empty "versionRange"');
});

$test('optional under schema 2 is refused; under 3 it must be a boolean', static function () use ($reqManifest, $slotsRefused): void {
    $slotsRefused($reqManifest(2, REQ_OPTIONAL, null), '"requires[0].optional" requires "schemaVersion": 3');
    $slotsRefused($reqManifest(3, '{"slug":"base-mod","versionRange":">=1.0.0","optional":"yes"}', null),
        '"requires[0].optional" must be a boolean');
});

$test('requiresModules: valid when declared in requires under schema 3, refused otherwise', static function () use ($reqManifest, $slotsErrors, $slotsRefused): void {
    $flag = '{"key":"needs","label":"flag.l","requiresModules":["extra-mod"]}';
    $json = $reqManifest(3, REQ_BASE . ',' . REQ_OPTIONAL, $flag);
    check($slotsErrors($json) === [], json_encode($slotsErrors($json)));
    $slotsRefused($reqManifest(3, REQ_BASE, $flag), '"featureFlags[0].requiresModules": "extra-mod" is not in requires');
    $slotsRefused($reqManifest(2, REQ_BASE, str_replace('extra-mod', 'base-mod', $flag)),
        '"featureFlags[0].requiresModules" requires "schemaVersion": 3');
});

// ---- ManifestPeersTest (protocol 2.0, SDK 0.10.0) ---------------------------------------------------
// The declarations between modules of sdk-protocol.md §3.2, §15.9, §6.3 -- peerReads, commands, events (the owner) and
// peers (the caller) -- read by Manifest and checked by ManifestCheck with the platform's rules and messages.

const PEERS_ROUTES = '"routes": [ { "method": "GET", "path": "/m/kf-qc/peer/checks", "right": "api.v1.m.kf-qc.peer.checks.get" },'
    . ' { "method": "POST", "path": "/m/kf-qc/peer/checks", "right": "api.v1.m.kf-qc.peer.checks.post" } ]';
const PEERS_REQUIRES_QC = '[ { "slug": "kf-qc", "versionRange": ">=1.0.0" } ]';

$peersOwner = static fn (int $schemaVersion, string $capabilities): string => '{ "schemaVersion": ' . $schemaVersion
    . ', "slug": "kf-qc", "name": "module.name", "version": "1.0.0", "platform": ">=4.1", "i18n": { "en": { "module.name": "Quality" } },'
    . ' "capabilities": { ' . PEERS_ROUTES . ', ' . $capabilities . ' } }';
$peersCaller = static fn (string $requires, string $peers): string => '{ "schemaVersion": 3, "slug": "kf-3d", "name": "module.name",'
    . ' "version": "1.0.0", "platform": ">=4.1", "i18n": { "en": { "module.name": "3D" } }, "requires": ' . $requires . ','
    . ' "capabilities": { "peers": ' . $peers . ' } }';
$peersErrors = static fn (string $json): array => ManifestCheck::validate($json)->errors;
$peersRefused = static function (string $json, string $fragment) use ($peersErrors): void {
    $errors = $peersErrors($json);
    foreach ($errors as $e) if (str_contains($e, $fragment)) return;
    throw new \RuntimeException("an error must contain <$fragment>: " . json_encode($errors));
};

$test('peers: an owner and a caller are valid and read as declared', static function () use ($peersOwner, $peersCaller, $peersErrors): void {
    $json = $peersOwner(3, '"peerReads": [ { "port": "m.kf-qc.checks.v1", "path": "/m/kf-qc/peer/checks", "visibility": "public" } ],'
        . ' "commands": [ { "name": "open-check", "path": "/m/kf-qc/peer/checks", "method": "POST", "visibility": "internal" } ],'
        . ' "events": [ { "name": "check-closed", "visibility": "public" } ]');
    check($peersErrors($json) === [], json_encode($peersErrors($json)));
    $manifest = Manifest::parse($json);
    check($manifest->capabilities->peerReads[0]->port === 'm.kf-qc.checks.v1', 'peerReads');
    check($manifest->capabilities->commands[0]->visibility === 'internal', 'commands');
    check($manifest->capabilities->events[0]->name === 'check-closed', 'events');
    $use = $peersCaller(PEERS_REQUIRES_QC, '[ { "owner": "kf-qc", "reads": ["m.kf-qc.checks.v1"], "commands": ["open-check"] } ]');
    check($peersErrors($use) === [], json_encode($peersErrors($use)));
    check(Manifest::parse($use)->capabilities->peers[0]->commands === ['open-check'], 'peers');
});

$test('peers: under schema 2 every key is refused', static function () use ($peersOwner, $peersRefused): void {
    $peersRefused($peersOwner(2, '"events": [ { "name": "check-closed", "visibility": "public" } ]'),
        'capabilities.events requires "schemaVersion": 3 (this manifest is schemaVersion 2)');
});

$test('peers: the grammars, the routes, the duplicates and the visibility', static function () use ($peersOwner, $peersRefused): void {
    $peersRefused($peersOwner(3, '"peerReads": [ { "port": "m.kf-3d.checks.v1", "path": "/m/kf-qc/peer/checks", "visibility": "public" } ]'),
        'capabilities.peerReads[0].port "m.kf-3d.checks.v1" must be m.kf-qc.{name}.v{N}');
    $peersRefused($peersOwner(3, '"peerReads": [ { "port": "m.kf-qc.checks.v1", "path": "/m/kf-qc/nope", "visibility": "public" } ]'),
        'is not a GET route this module declares');
    $peersRefused($peersOwner(3, '"commands": [ { "name": "open-check", "path": "/m/kf-qc/peer/checks", "method": "PUT", "visibility": "public" } ]'),
        'is not a PUT route this module declares');
    $peersRefused($peersOwner(3, '"commands": [ { "name": "x", "path": "/m/kf-qc/peer/checks", "method": "PATCH", "visibility": "public" } ]'),
        'capabilities.commands[0].method "PATCH" must be one of POST, PUT, DELETE');
    $peersRefused($peersOwner(3, '"events": [ { "name": "a-b", "visibility": "public" }, { "name": "a-b", "visibility": "public" } ]'),
        'capabilities.events[1].name "a-b" is declared twice');
    $peersRefused($peersOwner(3, '"events": [ { "name": "a-b" } ]'), 'capabilities.events[0].visibility null must be one of internal, public');
    $peersRefused($peersOwner(3, '"events": [ { "name": "a-b", "visibility": "public", "path": "/x" } ]'), '"path" is not a key of an event');
});

$test('peers: a peer must be required, not itself, once, and name something', static function () use ($peersCaller, $peersRefused, $peersErrors): void {
    $peersRefused($peersCaller('[]', '[ { "owner": "kf-qc", "commands": ["open-check"] } ]'), 'capabilities.peers[0].owner "kf-qc" is not in requires');
    $peersRefused($peersCaller(PEERS_REQUIRES_QC, '[ { "owner": "kf-3d", "commands": ["x-y"] } ]'), 'is this module itself');
    $peersRefused($peersCaller(PEERS_REQUIRES_QC, '[ { "owner": "kf-qc", "commands": ["a-b"] }, { "owner": "kf-qc", "commands": ["c-d"] } ]'),
        'capabilities.peers[1].owner "kf-qc" is declared twice');
    $peersRefused($peersCaller(PEERS_REQUIRES_QC, '[ { "owner": "kf-qc" } ]'), 'must name at least one of reads, commands');
    $peersRefused($peersCaller(PEERS_REQUIRES_QC, '[ { "owner": "kf-qc", "reads": ["m.kf-3d.x.v1"] } ]'), 'capabilities.peers[0].reads[0]');
    $optional = '[ { "slug": "kf-qc", "versionRange": ">=1.0.0", "optional": true } ]';
    check($peersErrors($peersCaller($optional, '[ { "owner": "kf-qc", "reads": ["m.kf-qc.checks.v1"] } ]')) === [], 'optional');
});

// ---- ContextViaModuleTest (protocol 2.0: actor.via = module:{slug}) ---------------

$viaVerify = static function (string $user, string $actor, string $rights): RequestContext {
    $now = 1760000000;
    $json = '{"v":1,"tenant":"t-1","user":' . $user . ',"actor":' . $actor . ',"locale":"ro","rights":' . $rights
        . ',"method":"GET","path":"/stations","query":null,"ts":' . $now . ',"requestId":"r-1"}';
    $context = rtrim(strtr(base64_encode($json), '+/', '-_'), '=');
    return RequestContext::verify($context, (string) $now, Signatures::sign('via-module-secret', (string) $now, $context . '.'), '',
        'via-module-secret', $now);
};
$viaRefused = static function (string $user, string $actor, string $rights, string $reason) use ($viaVerify): void {
    try {
        $viaVerify($user, $actor, $rights);
    } catch (\Kf\Sdk\Rejected $e) {
        check($e->problem()->status === 401 && $e->problem()->errorClass === ErrorClass::PERMANENT, 'a 401 permanent');
        check(str_contains((string) $e->problem()->detail, $reason), "$reason not in " . $e->problem()->detail);
        return;
    }
    throw new \RuntimeException('expected Rejected');
};

$test('via module: a user via a module is accepted, with the user kept', static function () use ($viaVerify): void {
    $ctx = $viaVerify('"oidc-sub-1"', '{"type":"user","via":"module:kf-3d","hookId":null}', '["api.v1.m.kf-qc.checks.get"]');
    check($ctx->actor->type === Actor::USER && $ctx->actor->via === 'module:kf-3d', 'actor');
    check($ctx->actor->viaModule() === 'kf-3d' && $ctx->user === 'oidc-sub-1', 'viaModule / user');
});

$test('via module: the system via a module is accepted without user or rights', static function () use ($viaVerify): void {
    $ctx = $viaVerify('null', '{"type":"system","via":"module:kf-3d","hookId":null}', '[]');
    check($ctx->actor->type === Actor::SYSTEM && $ctx->actor->viaModule() === 'kf-3d' && $ctx->user === null, 'system via module');
});

$test('via module: a bad slug, a user on the system, rights on the system or a hookId are refused', static function () use ($viaRefused): void {
    $viaRefused('"oidc-sub-1"', '{"type":"user","via":"module:Bad","hookId":null}', '[]', 'is not module:{slug}');
    $viaRefused('"oidc-sub-1"', '{"type":"user","via":"module:","hookId":null}', '[]', 'is not module:{slug}');
    $viaRefused('"oidc-sub-1"', '{"type":"system","via":"module:kf-3d","hookId":null}', '[]', 'actor system has no user');
    $viaRefused('null', '{"type":"system","via":"module:kf-3d","hookId":null}', '["x"]', 'has no rights');
    $viaRefused('"oidc-sub-1"', '{"type":"user","via":"module:kf-3d","hookId":"h-1"}', '[]', 'has no hookId');
});

$test('via module: another via stays refused, and the old actors have no calling module', static function () use ($viaRefused): void {
    $viaRefused('"oidc-sub-1"', '{"type":"user","via":"hook","hookId":null}', '[]', 'actor user has no via and no hookId');
    check(Actor::user()->viaModule() === null && Actor::platform()->viaModule() === null, 'no calling module');
});

// ---- PeersClientTest, EventsClientTest (protocol 2.0 §15.9, §6.3) ------------------------------
// PeersClient and EventsClient against a platform stub: the token, the context and its proof go back unchanged; a
// command carries Idempotency-Key, refused locally when missing or invalid; the owner's answer passes unchanged; 409,
// 422, 502, 503 are PlatformRefusals too; an after delivery's context serves a background job within ±300 s; a module
// event reaches the subscribed handler with the delivery's context.

const PEERS_PROOF = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';

$deliveryHeader = static function (int $ts, string $requestId): string {
    $json = '{"v":1,"tenant":"' . SERVICE_TENANT . '","user":null,"actor":{"type":"system","via":"hook","hookId":"' . $requestId
        . '"},"locale":"en","rights":[],"method":"POST","path":"/_kf/hooks","query":null,"ts":' . $ts . ',"requestId":"' . $requestId . '"}';
    return rtrim(strtr(base64_encode($json), '+/', '-_'), '=');
};
$problem = static fn (int $status, string $reason, string $class): string =>
    '{"type":"https://kf.ro/problems/' . $reason . '","status":' . $status . ',"detail":"x","class":"' . $class . '"}';

$test('peers read: GET …/peers/kf-stock/reads/{port}?query with the token, the context and the proof → the owner\'s answer',
    static function () use ($servicePlatform): void {
        [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('caller', 'peers-secret', 'kfm_peers-token');
        try {
            $ctx = $verified('req-1', PEERS_PROOF);
            $answer(200, '{"sku":"KF-42","level":7.50}');
            $a = $kf->peers()->read($ctx, 'kf-stock', 'm.kf-stock.level.v1', ['sku' => 'KF 42']);
            $r = $last();
            check($requests() === 1, 'one request');
            check($r['line'] === 'GET /api/v1/platform/modules/caller/peers/kf-stock/reads/m.kf-stock.level.v1?sku=KF+42', $r['line']);
            check($r['authorization'] === 'Bearer kfm_peers-token' && $r['proof'] === PEERS_PROOF && $r['context'] !== null, 'token/proof/context');
            check($a->status === 200 && $a->object()['sku'] === 'KF-42' && $a->object()['level'] == 7.5, 'answer');
        } finally {
            $stop();
        }
    });

$test('peers read: 503 module-unavailable transient; 403 / 404 / 422 / 502 refusals with the reason', static function () use ($servicePlatform, $problem): void {
    [$kf, $stop, $answer, , , $verified] = $servicePlatform('caller', 'peers-secret', 'kfm_peers-token');
    try {
        $ctx = $verified('req-2', PEERS_PROOF);
        $answer(503, $problem(503, 'module-unavailable', 'transient'));
        $e = refused(static fn () => $kf->peers()->read($ctx, 'kf-stock', 'm.kf-stock.level.v1', []));
        check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::UNAVAILABLE, 'unavailable');
        check($e->reason() === \Kf\Sdk\PeersClient::MODULE_UNAVAILABLE && $e->errorClass() === ErrorClass::TRANSIENT, 'transient');
        check(!str_contains($e->getMessage(), PEERS_PROOF), 'no proof');
        foreach ([[403, 'peer-not-required', PlatformRefusalKind::FORBIDDEN], [404, 'not-configured', PlatformRefusalKind::NOT_FOUND],
            [422, 'module-refused', PlatformRefusalKind::REFUSED], [502, 'invalid-answer', PlatformRefusalKind::INVALID_ANSWER]] as [$s, $reason, $kind]) {
            $answer($s, $problem($s, $reason, 'permanent'));
            $e = refused(static fn () => $kf->peers()->read($ctx, 'kf-stock', 'm.kf-stock.level.v1', []));
            check($e instanceof PlatformRefusal && $e->reason() === $reason && $e->kind() === $kind, "refusal $reason");
            check($e->errorClass() === ErrorClass::PERMANENT, "permanent $reason");
        }
    } finally {
        $stop();
    }
});

$test('peers: an owner or a port outside the grammar, a missing or invalid key, an undeclared peer: refused locally',
    static function () use ($servicePlatform): void {
        [$kf, $stop, , $requests, , $verified] = $servicePlatform('caller', 'peers-secret', 'kfm_peers-token');
        try {
            $ctx = $verified('req-4', PEERS_PROOF);
            check(refused(static fn () => $kf->peers()->read($ctx, 'Kf_Stock', 'm.kf-stock.level.v1'))->problem()->status === 0, 'owner');
            check(str_contains(refused(static fn () => $kf->peers()->read($ctx, 'kf-stock', 'm.other.level.v1'))->getMessage(),
                'm.kf-stock.{name}.v{N}'), 'port');
            foreach ([null, 'short', 'key with spaces'] as $key) {
                $e = refused(static fn () => $kf->peers()->command($ctx, 'kf-stock', 'reserve', $key, []));
                check($e->problem()->status === 0 && str_contains($e->getMessage(), 'Idempotency-Key'), 'key ' . json_encode($key));
            }
            check(preg_match('/' . \Kf\Sdk\PeersClient::IDEMPOTENCY_KEY . '/D', \Kf\Sdk\PeersClient::newIdempotencyKey()) === 1, 'new key');
            $kf->withManifest('{"schemaVersion":3,"slug":"caller","requires":[{"slug":"kf-stock","versionRange":">=1.0.0"}],'
                . '"capabilities":{"peers":[{"owner":"kf-stock","reads":["m.kf-stock.level.v1"],"commands":["reserve"]}]}}');
            check(str_contains(refused(static fn () => $kf->peers()->read($ctx, 'kf-stock', 'm.kf-stock.other.v1'))->getMessage(),
                'capabilities.peers'), 'undeclared read');
            check(str_contains(refused(static fn () => $kf->peers()->command($ctx, 'kf-stock', 'release', 'key-0001-abcdef'))->getMessage(),
                'capabilities.peers'), 'undeclared command');
            check($requests() === 0, 'nothing sent');
        } finally {
            $stop();
        }
    });

$test('peers command: POST …/commands/{name} with the JSON body; 201 passes; 409 conflict permanent, in-progress transient',
    static function () use ($servicePlatform, $problem): void {
        [$kf, $stop, $answer, , $last, $verified] = $servicePlatform('caller', 'peers-secret', 'kfm_peers-token');
        try {
            $ctx = $verified('req-5', PEERS_PROOF);
            $answer(201, '{"reservationId":"r-1"}');
            $a = $kf->peers()->command($ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', ['qty' => 3]);
            $r = $last();
            check($r['line'] === 'POST /api/v1/platform/modules/caller/peers/kf-stock/commands/reserve', $r['line']);
            check($r['idempotencyKey'] === 'key-0001-abcdef' && $r['body'] === '{"qty":3}', 'key/body ' . json_encode($r));
            check($a->status === 201 && $a->object()['reservationId'] === 'r-1', 'answer');
            $answer(409, $problem(409, 'idempotency-conflict', 'permanent'));
            $e = refused(static fn () => $kf->peers()->command($ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', ['qty' => 4]));
            check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::CONFLICT && $e->errorClass() === ErrorClass::PERMANENT, 'conflict');
            $answer(409, $problem(409, 'idempotency-in-progress', 'transient'));
            check(refused(static fn () => $kf->peers()->command($ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', ['qty' => 4]))
                ->errorClass() === ErrorClass::TRANSIENT, 'in progress');
        } finally {
            $stop();
        }
    });

$test(': a delivery\'s context serves a background job within ±300 s, refused locally after', static function () use ($servicePlatform, $deliveryHeader): void {
    [$kf, $stop, , $requests, $last] = $servicePlatform('caller', 'peers-secret', 'kfm_peers-token');
    try {
        $header = $deliveryHeader(time(), '11111111-2222-3333-4444-555555555555');
        $fresh = \Kf\Sdk\HookEvents\DeliveryContext::of($header, PEERS_PROOF);
        $kf->peers()->read($fresh, 'kf-stock', 'm.kf-stock.level.v1');
        check($last()['context'] === $header && $last()['proof'] === PEERS_PROOF, 'sent unchanged');
        check($fresh->tenant() === SERVICE_TENANT && $fresh->context->actor->type === Actor::SYSTEM, 'tenant / actor');
        check(!str_contains((string) $fresh, PEERS_PROOF) && !str_contains(json_encode($fresh), PEERS_PROOF), 'no proof printed');
        $old = \Kf\Sdk\HookEvents\DeliveryContext::of($deliveryHeader(time() - 301, 'x-old'), PEERS_PROOF);
        check(str_contains(refused(static fn () => $kf->peers()->command($old, 'kf-stock', 'reserve', 'key-0001-abcdef'))->getMessage(), '300 s'), 'window');
        check($requests() === 1, 'one request');
        try {
            \Kf\Sdk\HookEvents\DeliveryContext::of($header, null);
            throw new \RuntimeException('no proof must be refused');
        } catch (\InvalidArgumentException) {
        }
    } finally {
        $stop();
    }
});

$test('events emit: POST …/events {name, data} → {eventId, type}; local refusals; 422 event-not-declared', static function () use ($servicePlatform, $problem): void {
    [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('kf-stock', 'events-secret', 'kfm_events-token');
    try {
        $ctx = $verified('req-1', PEERS_PROOF);
        $answer(202, '{"eventId":"e-1","type":"m.kf-stock.after.level-changed"}');
        $emitted = $kf->events()->emit($ctx, 'level-changed', ['sku' => 'KF-42']);
        check($last()['line'] === 'POST /api/v1/platform/modules/kf-stock/events', $last()['line']);
        check($last()['body'] === '{"name":"level-changed","data":{"sku":"KF-42"}}', $last()['body']);
        check($emitted->eventId === 'e-1' && $emitted->type === 'm.kf-stock.after.level-changed', 'emitted');
        $answer(422, $problem(422, 'event-not-declared', 'permanent'));
        $e = refused(static fn () => $kf->events()->emit($ctx, 'level-changed', []));
        check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::REFUSED && $e->reason() === 'event-not-declared', 'refused');
        $count = $requests();
        $kf->withManifest('{"schemaVersion":3,"slug":"kf-stock","capabilities":{"events":[{"name":"level-changed","visibility":"public"}]}}');
        check(str_contains(refused(static fn () => $kf->events()->emit($ctx, 'other', []))->getMessage(), 'capabilities.events'), 'undeclared');
        refused(static fn () => $kf->events()->emit($ctx, 'Bad_Name', []));
        refused(static fn () => $kf->events()->emit($ctx, 'level-changed', null));
        check(str_contains(refused(static fn () => $kf->events()->emit($ctx, 'level-changed', ['x' => str_repeat('y', \Kf\Sdk\EventsClient::MAX_DATA_BYTES)]))
            ->getMessage(), '65536'), 'too big');
        check($requests() === $count, 'nothing sent');
    } finally {
        $stop();
    }
});

$test('events on: a hook m.{owner}.after.{name}; the delivery reaches the handler with its context', static function () use ($deliveryHeader): void {
    $subscriber = KfModule::of('http://127.0.0.1:1', 'kf-orders', 'kfm_events-token', 'events-secret', 'http://127.0.0.1:9');
    $seen = null;
    $subscriber->events()->on('kf-stock', 'level-changed', static function ($ev) use (&$seen): void {
        $seen = $ev;
    });
    check($subscriber->registrationBody()['hooks'] === [['event' => 'm.kf-stock.after.level-changed']], 'registration');
    $now = time();
    $header = $deliveryHeader($now, 'd-1');
    $ce = ['specversion' => '1.0', 'id' => 'd-1', 'kfeventid' => 'e-1', 'type' => 'kf.m.kf-stock.after.level-changed',
        'source' => '/module-event/kf-stock', 'subject' => 'kf-stock', 'kftenant' => SERVICE_TENANT, 'kflocale' => 'en',
        'kfaggregatetype' => 'module-event', 'kfenvelopeversion' => '1', 'data' => ['sku' => 'KF-42']];
    $raw = json_encode($ce);
    $result = $subscriber->handle($raw, (string) $now, Signatures::sign('events-secret', (string) $now, $raw), $now, $header, PEERS_PROOF);
    check($result->status === 200, (string) $result->body);
    check($seen->owner === 'kf-stock' && $seen->name === 'level-changed' && $seen->eventId() === 'e-1', 'event');
    check($seen->data === ['sku' => 'KF-42'], 'data');
    $d = $seen->deliveryContext();
    check($d !== null && $d->contextHeader === $header && $d->proof === PEERS_PROOF && $d->tenant() === SERVICE_TENANT, 'delivery context');
    $subscriber->handle($raw, (string) $now, Signatures::sign('events-secret', (string) $now, $raw), $now);
    check($seen->deliveryContext() === null, 'a platform before 2.0');
    $other = json_encode(['type' => 'kf.m.kf-stock.after.other'] + $ce);
    $ignored = $subscriber->handle($other, (string) $now, Signatures::sign('events-secret', (string) $now, $other), $now);
    check(str_contains((string) $ignored->body, '"ignored"'), (string) $ignored->body);
});


// ---- SlotContributionsTest (protocol 2.0 §7.12) ---------------------------------------
// The contributions of a module to the slots of other modules: the kinds without code (Surface::layer, Surface::data),
// the local check of the contributor (ManifestCheck). The warnings of the registration (registrationWarnings())
// are driven in Java through FakePlatform (Java test code, not derived); here they are covered by the conformance area
// `slots` (tests/conformance.php).

const SLOT_CONTRIB_LAYERS = 'm.kf-3d.viewport.layers.v1';

$tests['SlotContributionsTest: layer and data are sent without code'] = static function (): void {
    $kf = KfModule::of('http://127.0.0.1:1', 'slot-contrib', 'kfm_x', 'secret', null);
    $kf->ui()->contribute(SLOT_CONTRIB_LAYERS, \Kf\Sdk\Surface::layer('pin', '/m/slot-contrib/api/pins')->key('pins'))
        ->contribute('m.kf-3d.materials.v1', \Kf\Sdk\Surface::data(['name' => 'wool'])->key('wool'));
    $surfaces = json_decode(json_encode($kf->registrationBody()['surfaces']), true);
    check($surfaces[0] === ['slug' => SLOT_CONTRIB_LAYERS, 'kind' => 'layer', 'layer' => 'pin',
        'source' => '/m/slot-contrib/api/pins', 'key' => 'pins'], json_encode($surfaces[0]));
    check($surfaces[1] === ['slug' => 'm.kf-3d.materials.v1', 'kind' => 'data', 'data' => ['name' => 'wool'],
        'key' => 'wool'], json_encode($surfaces[1]));
    check(!array_key_exists('entry', $surfaces[0]), 'no entry on a layer');
};

$tests['SlotContributionsTest: data too large or missing is refused locally'] = static function (): void {
    $refused = static function (callable $f): ?string {
        try {
            $f();
        } catch (\InvalidArgumentException $e) {
            return $e->getMessage();
        }
        return null;
    };
    $big = $refused(static fn () => \Kf\Sdk\Surface::data(['blob' => str_repeat('x', \Kf\Sdk\Surface::MAX_DATA_BYTES)]));
    check($big !== null && str_contains($big, 'at most 65536 bytes'), (string) $big);
    check($refused(static fn () => \Kf\Sdk\Surface::data(null)) !== null, 'null data');
    check($refused(static fn () => \Kf\Sdk\Surface::layer('Pin', '/m/x/api/pins')) !== null, 'layer kind');
    check($refused(static fn () => \Kf\Sdk\Surface::layer('pin', 'https://elsewhere/pins')) !== null, 'source');
};

$tests['SlotContributionsTest: the owner must be required, mandatory or optional'] = static function (): void {
    $base = static fn (string $requires): string => '{"schemaVersion":3,"slug":"slot-contrib","name":"module.name",'
        . '"version":"1.0.0","platform":">=4.1","i18n":{"en":{"module.name":"Slot contributor"}},' . $requires
        . '"capabilities":{"surfaces":[{"slug":"m.kf-3d.viewport.layers.v1","kind":"layer"}]}}';
    $missing = ManifestCheck::validate($base(''));
    check(!$missing->ok(), 'refused');
    check(count(array_filter($missing->errors, static fn ($e) => str_contains($e, 'the owner "kf-3d" is not in requires'))) > 0,
        implode('; ', $missing->errors));
    $optional = ManifestCheck::validate($base('"requires":[{"slug":"kf-3d","versionRange":">=1.0.0","optional":true}],'));
    check($optional->ok(), implode('; ', $optional->errors));
    $onHost = ManifestCheck::validate(str_replace('m.kf-3d.viewport.layers.v1', 'models.detail.tabs', $base('')));
    check(count(array_filter($onHost->errors, static fn ($e) => str_contains($e, 'is only for a slot of a module'))) > 0,
        implode('; ', $onHost->errors));
};

// ---- ManifestOrderKeysTest, FlowRulesTest, FlowEventsTest, OrderServicesTest (protocol 2.0 §16) ------
// The keys of the order flow checked locally with the platform's texts (only the grammar); the projection
// order.flow-rule.v1 and the rule's answer; the events workflow.flow-* and the order hooks ($kf->orders());
// the client of order.services.v1 against a platform stub.

$orderManifest = static fn (int $schema, string $caps): string => '{"schemaVersion":' . $schema . ',"slug":"kf-step","name":"module.name",'
    . '"version":"1.0.0","platform":">=4.1","i18n":{"en":{"module.name":"Step","rule.blocked":"Not ready"}},"capabilities":{'
    . '"routes":[{"method":"GET","path":"/m/kf-step/rules/ready","right":"api.v1.m.kf-step.rules.ready.get"},'
    . '{"method":"POST","path":"/m/kf-step/items","right":"api.v1.m.kf-step.items.post"}],' . $caps . '}}';
$orderRefused = static function (string $json, string $fragment): void {
    $e = ManifestCheck::validate($json)->errors;
    check(count(array_filter($e, static fn ($x) => str_contains($x, $fragment))) > 0, "an error must contain <$fragment>: " . implode('; ', $e));
};
const ORDER_FLOW_RULE = '"flowRules":[{"key":"item-ready","path":"/m/kf-step/rules/ready","projection":"order.flow-rule.v1"}]';
const ORDER_SERVICES = '"services":[{"port":"order.services.v1"}]';
const ORDER_STEP = '"surfaces":[{"slug":"orderflow.step","kind":"react","key":"item-check"}]';

$tests['ManifestOrderKeysTest: the order keys are valid under schema 3 and read as declared'] = static function () use ($orderManifest): void {
    $json = $orderManifest(3, ORDER_FLOW_RULE . ',' . ORDER_SERVICES . ',' . ORDER_STEP . ',"hooks":[{"point":"workflow.flow-step-entered"}]');
    $r = ManifestCheck::validate($json);
    check($r->ok(), implode('; ', $r->errors));
    $m = Manifest::parse($json);
    $rule = $m->capabilities->flowRules[0];
    check($rule->key === 'item-ready' && $rule->path === '/m/kf-step/rules/ready' && $rule->timeoutSeconds === null, 'rule');
    check($rule->effectiveTimeoutSeconds() === 2 && $rule->projection === 'order.flow-rule.v1', 'default 2 s');
    check($m->capabilities->services[0]->port === 'order.services.v1', 'services');
    check($m->capabilities->surfaces[0]->key === 'item-check', 'step key');
    check($m->capabilities->hooks[0]->point === 'workflow.flow-step-entered', 'hook');
};

$tests['ManifestOrderKeysTest: flowRules and services under schema 2 are refused, each named'] = static function () use ($orderManifest, $orderRefused): void {
    $json = $orderManifest(2, ORDER_FLOW_RULE . ',' . ORDER_SERVICES);
    $orderRefused($json, 'capabilities.flowRules requires "schemaVersion": 3 (this manifest is schemaVersion 2)');
    $orderRefused($json, 'capabilities.services requires "schemaVersion": 3 (this manifest is schemaVersion 2)');
};

$tests['ManifestOrderKeysTest: a flow rule is a closed object with its grammar'] = static function () use ($orderManifest, $orderRefused): void {
    $rule = static fn (string $extra) => $orderManifest(3, '"flowRules":[' . $extra . ']');
    $orderRefused($rule('{"key":"item-ready","path":"/m/kf-step/rules/missing","projection":"order.flow-rule.v1"}'),
        'capabilities.flowRules[0].path "/m/kf-step/rules/missing" is not a GET route this module declares');
    $orderRefused($rule('{"key":"item-ready","path":"/m/kf-step/items","projection":"order.flow-rule.v1"}'), 'is not a GET route this module declares');
    $orderRefused($rule('{"key":"item-ready","path":"/m/kf-step/rules/ready","timeoutSeconds":61,"projection":"order.flow-rule.v1"}'),
        'capabilities.flowRules[0].timeoutSeconds 61 must be a whole number from 1 to 60');
    $orderRefused($rule('{"key":"item-ready","path":"/m/kf-step/rules/ready","timeoutSeconds":0,"projection":"order.flow-rule.v1"}'),
        'timeoutSeconds 0 must be a whole number from 1 to 60');
    $orderRefused($rule('{"key":"item-ready","path":"/m/kf-step/rules/ready","projection":"order.flow-rule.v2"}'),
        'capabilities.flowRules[0].projection "order.flow-rule.v2" must be "order.flow-rule.v1"');
    $orderRefused($rule('{"key":"Item","path":"/m/kf-step/rules/ready","projection":"order.flow-rule.v1"}'),
        'capabilities.flowRules[0].key "Item" must match ^[a-z][a-z0-9-]{1,40}$');
    $orderRefused($rule('{"key":"a-rule","path":"/m/kf-step/rules/ready","projection":"order.flow-rule.v1"},'
        . '{"key":"a-rule","path":"/m/kf-step/rules/ready","projection":"order.flow-rule.v1"}'),
        'capabilities.flowRules[1].key "a-rule" is declared twice');
    $orderRefused($rule('{"key":"a-rule","path":"/m/kf-step/rules/ready","projection":"order.flow-rule.v1","evaluate":"x"}'),
        '"evaluate" is not a key of a flow rule');
    $ok = $rule('{"key":"item-ready","path":"/m/kf-step/rules/ready","timeoutSeconds":10,"projection":"order.flow-rule.v1"}');
    check(ManifestCheck::validate($ok)->ok(), implode('; ', ManifestCheck::validate($ok)->errors));
    check(Manifest::parse($ok)->capabilities->flowRules[0]->effectiveTimeoutSeconds() === 10, 'timeout 10');
};

$tests['ManifestOrderKeysTest: a service names only order.services.v1, once'] = static function () use ($orderManifest, $orderRefused): void {
    $orderRefused($orderManifest(3, '"services":[{"port":"order.costs.v1"}]'),
        'capabilities.services[0].port "order.costs.v1" must be "order.services.v1"');
    $orderRefused($orderManifest(3, '"services":[{"port":"order.services.v1"},{"port":"order.services.v1"}]'),
        'capabilities.services[1].port "order.services.v1" is declared twice');
    $orderRefused($orderManifest(3, '"services":[{"port":"order.services.v1","write":true}]'), '"write" is not a key of a service');
};

$tests['ManifestOrderKeysTest: the step surface needs the key of its step; a flow event takes no onFailure'] = static function () use ($orderManifest, $orderRefused): void {
    $orderRefused($orderManifest(3, '"surfaces":[{"slug":"orderflow.step","kind":"react"}]'),
        'capabilities.surfaces[0] on "orderflow.step" needs a "key": the key of the step it renders');
    $orderRefused($orderManifest(3, '"surfaces":[{"slug":"orderflow.step","kind":"react","key":"Bad Key"}]'),
        'capabilities.surfaces[0].key "Bad Key" must match');
    $orderRefused($orderManifest(3, '"hooks":[{"point":"workflow.flow-step-completed","onFailure":"allow"}]'),
        'capabilities.hooks[0].onFailure is not allowed on the flow event "workflow.flow-step-completed": it runs after the step and cannot block it');
};

$tests['ManifestOrderKeysTest: the order hooks are ordinary points, the registration names a declared step'] =
    static function () use ($orderManifest, $orderRefused): void {
        $hooks = $orderManifest(2, '"hooks":[{"point":"order.before.update","onFailure":"block"},{"point":"order.field.changed"}]');
        check(ManifestCheck::validate($hooks)->ok(), implode('; ', ManifestCheck::validate($hooks)->errors));
        // capabilities.flows is a capability of protocol 2.1 (ManifestFlowsTest), no longer unknown.
        check(ManifestCheck::validate($orderManifest(3, '"flows":[]'))->ok(), implode('; ', ManifestCheck::validate($orderManifest(3, '"flows":[]'))->errors));
        $json = $orderManifest(3, ORDER_STEP);
        $declared = ['slug' => 'orderflow.step', 'kind' => 'react', 'key' => 'item-check'];
        check(ManifestCheck::check($json, ['hooks' => [], 'fields' => [], 'surfaces' => [$declared]], [])->ok(), 'declared step');
        $e = ManifestCheck::check($json, ['hooks' => [], 'fields' => [], 'surfaces' => [
            ['slug' => 'orderflow.step', 'kind' => 'react', 'key' => 'another-step'], ['slug' => 'orderflow.step', 'kind' => 'react'],
        ]], [])->errors;
        check(count(array_filter($e, static fn ($x) => str_contains($x, 'the step "another-step" of "orderflow.step" is not declared'))) > 0, implode('; ', $e));
        check(count(array_filter($e, static fn ($x) => str_contains($x, 'registration: surfaces[1] on "orderflow.step" needs a "key"'))) > 0, implode('; ', $e));
    };

const FLOW_FULL = 'projection=order.flow-rule.v1&rule.key=item-ready&order.id=812&order.status=CONFIRMED'
    . '&flow.key=g3-production&flow.version=4&instance.id=31&step.key=cutting&step.to=sewing&order.number=PO+2026%2F7'
    . '&order.internal=false&order.customerId=55&step.behavesLike=IN_PRODUCTION&order.stages=production'
    . '&order.stages=sampling&line.id=4401&line.modelId=90&line.modelVersionId=901&line.quantity=120';

$flowRefused = static function (callable $f): ?string {
    try {
        $f();
    } catch (\InvalidArgumentException $e) {
        return $e->getMessage();
    }
    return null;
};

$tests['FlowRulesTest: a full v1 query gives every field'] = static function (): void {
    $p = \Kf\Sdk\FlowRules\Projection::fromQuery('?' . FLOW_FULL);
    check($p->ruleKey === 'item-ready' && $p->orderId === '812' && $p->orderStatus === 'CONFIRMED', 'order');
    check($p->flowKey === 'g3-production' && $p->flowVersion === '4' && $p->instanceId === '31', 'flow');
    check($p->stepKey === 'cutting' && $p->stepTo === 'sewing' && $p->orderNumber === 'PO 2026/7', 'step ' . $p->orderNumber);
    check($p->orderInternal === false && $p->orderCustomerId === '55' && $p->stepBehavesLike === 'IN_PRODUCTION', 'internal');
    check($p->orderStages === ['production', 'sampling'] && $p->hasStage('sampling') && !$p->hasStage('development'), 'stages');
    check($p->onLine() && $p->lineId === '4401' && $p->lineModelId === '90' && $p->lineModelVersionId === '901' && $p->lineQuantity === '120', 'line');
};

$tests['FlowRulesTest: an internal order without line; unknown parameters ignored'] = static function (): void {
    $p = \Kf\Sdk\FlowRules\Projection::fromQuery('projection=order.flow-rule.v1&rule.key=r&order.id=9'
        . '&order.status=COMPLETED&flow.key=f&flow.version=1&instance.id=2&step.key=a&step.to=b&order.internal=true');
    check($p->orderInternal === true && $p->orderCustomerId === null && $p->orderStatus === 'COMPLETED', 'internal');
    check($p->orderStages === [] && !$p->onLine(), 'no stage, no line');
    check(\Kf\Sdk\FlowRules\Projection::fromQuery(FLOW_FULL . '&order.priority=high&flow.extra=1')->ruleKey === 'item-ready', 'ignored');
};

$tests['FlowRulesTest: refusals — unknown or missing projection, missing fields, repeated, bad boolean, oversized'] = static function () use ($flowRefused): void {
    $v2 = $flowRefused(static fn () => \Kf\Sdk\FlowRules\Projection::fromQuery(str_replace('order.flow-rule.v1', 'order.flow-rule.v2', FLOW_FULL)));
    check($v2 !== null && str_contains($v2, '"order.flow-rule.v2" is not known'), (string) $v2);
    $none = $flowRefused(static fn () => \Kf\Sdk\FlowRules\Projection::fromQuery(str_replace('projection=order.flow-rule.v1&', '', FLOW_FULL)));
    check($none !== null && str_contains($none, 'the projection is missing'), (string) $none);
    $missing = $flowRefused(static fn () => \Kf\Sdk\FlowRules\Projection::fromQuery(str_replace(['&step.to=sewing', '&instance.id=31'], '', FLOW_FULL)));
    check($missing !== null && str_contains($missing, 'missing instance.id, step.to'), (string) $missing);
    check($flowRefused(static fn () => \Kf\Sdk\FlowRules\Projection::fromQuery(FLOW_FULL . '&step.to=packing')) !== null, 'repeated');
    check($flowRefused(static fn () => \Kf\Sdk\FlowRules\Projection::fromQuery(str_replace('order.internal=false', 'order.internal=yes', FLOW_FULL))) !== null, 'boolean');
    check($flowRefused(static fn () => \Kf\Sdk\FlowRules\Projection::fromQuery(FLOW_FULL . '&pad=' . str_repeat('x', \Kf\Sdk\FlowRules::MAX_QUERY_BYTES))) !== null, 'oversized');
};

$tests['FlowRulesTest: the query round-trips in the engine\'s order'] = static function (): void {
    $p = \Kf\Sdk\FlowRules\Projection::fromQuery(FLOW_FULL);
    $q = $p->toQuery();
    check(str_starts_with($q, 'projection=order.flow-rule.v1&rule.key=item-ready&order.id=812'), $q);
    check(str_contains($q, 'order.stages=production&order.stages=sampling'), $q);
    check(\Kf\Sdk\FlowRules\Projection::fromQuery($q) == $p, 'round trip');
    $params = ['projection' => ['order.flow-rule.v1']];
    foreach (['rule.key', 'order.id', 'order.status', 'flow.key', 'flow.version', 'instance.id', 'step.key', 'step.to'] as $n) $params[$n] = ['x'];
    check(\Kf\Sdk\FlowRules\Projection::fromParams($params)->stepTo === 'x', 'fromParams');
    check(\Kf\Sdk\FlowRules::PROJECTION === 'order.flow-rule.v1' && \Kf\Sdk\FlowRules::STAGES === ['development', 'sampling', 'production'], 'constants');
};

$tests['FlowRulesTest: the decisions; a block without key or with too many params is refused locally'] = static function () use ($flowRefused): void {
    check(\Kf\Sdk\FlowRules\Decision::pass()->toJson() === ['decision' => 'pass'] && \Kf\Sdk\FlowRules\Decision::pass()->passes(), 'pass');
    $block = Json::encode(\Kf\Sdk\FlowRules\Decision::block('rule.blocked', ['item' => 'A-1'])->toJson());
    check($block === '{"decision":"block","key":"rule.blocked","params":{"item":"A-1"}}', $block);
    check(\Kf\Sdk\FlowRules\Decision::block('rule.blocked')->toJson() === ['decision' => 'block', 'key' => 'rule.blocked'], 'no params');
    check(!\Kf\Sdk\FlowRules\Decision::block('rule.blocked')->passes(), 'blocks');
    check($flowRefused(static fn () => \Kf\Sdk\FlowRules\Decision::block(null)) !== null, 'null key');
    check($flowRefused(static fn () => \Kf\Sdk\FlowRules\Decision::block(' ')) !== null, 'blank key');
    check($flowRefused(static fn () => \Kf\Sdk\FlowRules\Decision::block('Not A Key')) !== null, 'bad key');
    $many = [];
    for ($i = 0; $i < 21; $i++) $many["p$i"] = $i;
    $m = $flowRefused(static fn () => \Kf\Sdk\FlowRules\Decision::block('rule.blocked', $many));
    check($m !== null && str_contains($m, 'at most 20 params'), (string) $m);
    unset($many['p20']);
    check(count((array) \Kf\Sdk\FlowRules\Decision::block('rule.blocked', $many)->toJson()['params']) === 20, '20 params');
};

const FLOW_SECRET = 'flow-events-secret';
const FLOW_TENANT = '00000000-0000-0000-0000-0000000000f1';

$flowEnvelope = static fn (string $point, string $eventId, array $data, string $aggregateType): array => [
    'specversion' => '1.0', 'id' => "delivery-$eventId", 'kfeventid' => $eventId, 'type' => "kf.$point",
    'source' => "/$aggregateType/812", 'subject' => '812', 'time' => '2026-11-09T10:00:00Z',
    'datacontenttype' => 'application/json', 'kfmodule' => 'kf-step', 'kfflags' => new \stdClass(), 'kftenant' => FLOW_TENANT,
    'kflocale' => 'en', 'kfactor' => null, 'kfaggregatetype' => $aggregateType, 'kfenvelopeversion' => '1', 'data' => $data,
];
$flowData = static function (?string $stepKey): array {
    $d = ['tenantId' => FLOW_TENANT, 'orderId' => 812, 'instanceId' => 31, 'flowKey' => 'g3-production', 'flowVersion' => 4];
    if ($stepKey !== null) $d['stepKey'] = $stepKey;
    $d['iteration'] = 1;
    return $d;
};
$flowSend = static function (KfModule $kf, array $envelope): HookResult {
    $body = Json::encode($envelope);
    $ts = (string) time();
    return $kf->handle($body, $ts, Signatures::sign(FLOW_SECRET, $ts, $body), (int) $ts);
};
$flowModule = static fn (): KfModule => KfModule::of('http://127.0.0.1:1', 'kf-step', 'kfm_t', FLOW_SECRET, 'http://kf-step:8080');

$tests['FlowEventsTest: each of the four types is parsed; stepKey optional only on the instance completion'] = static function () use ($flowEnvelope, $flowData, $flowRefused): void {
    foreach (\Kf\Sdk\FlowEvents::TYPES as $type) {
        $ce = $flowEnvelope($type, "e-$type", $flowData('cutting'), 'flow-instance');
        $ev = \Kf\Sdk\FlowEvents::parse(\Kf\Sdk\HookEvents\Envelope::of(Json::decode(Json::encode($ce))), $ce['data']);
        check($ev->type === $type && $ev->orderId === '812' && $ev->instanceId === '31', "ids $type");
        check($ev->flowKey === 'g3-production' && $ev->flowVersion === 4 && $ev->stepKey === 'cutting', "flow $type");
        check($ev->eventId() === "e-$type" && $ev->tenant() === FLOW_TENANT && $ev->data['iteration'] === 1, "envelope $type");
    }
    $completed = $flowEnvelope(\Kf\Sdk\FlowEvents::INSTANCE_COMPLETED, 'e1', $flowData(null), 'flow-instance');
    check(\Kf\Sdk\FlowEvents::parse(\Kf\Sdk\HookEvents\Envelope::of(Json::decode(Json::encode($completed))), $completed['data'])->stepKey === null, 'no step');
    $entered = $flowEnvelope(\Kf\Sdk\FlowEvents::STEP_ENTERED, 'e2', $flowData(null), 'flow-instance');
    $m = $flowRefused(static fn () => \Kf\Sdk\FlowEvents::parse(\Kf\Sdk\HookEvents\Envelope::of(Json::decode(Json::encode($entered))), $entered['data']));
    check($m !== null && str_contains($m, 'needs data.stepKey'), (string) $m);
    $noOrder = $flowData('a');
    unset($noOrder['orderId']);
    $ce = $flowEnvelope(\Kf\Sdk\FlowEvents::STEP_COMPLETED, 'e3', $noOrder, 'flow-instance');
    check($flowRefused(static fn () => \Kf\Sdk\FlowEvents::parse(\Kf\Sdk\HookEvents\Envelope::of(Json::decode(Json::encode($ce))), $noOrder)) !== null, 'no order');
    $other = $flowEnvelope('workflow.flow-started', 'e4', $flowData('a'), 'flow-instance');
    check($flowRefused(static fn () => \Kf\Sdk\FlowEvents::parse(\Kf\Sdk\HookEvents\Envelope::of(Json::decode(Json::encode($other))), $flowData('a'))) !== null, 'other type');
};

$tests['FlowEventsTest: a subscribed event reaches its handler and is registered without onFailure'] = static function () use ($flowEnvelope, $flowData, $flowSend, $flowModule, $flowRefused): void {
    $kf = $flowModule();
    $seen = [];
    $kf->flows()->on(\Kf\Sdk\FlowEvents::STEP_ENTERED, function ($e) use (&$seen): void { $seen[] = $e; })
        ->on(\Kf\Sdk\FlowEvents::LINE_PROMOTED, function ($e) use (&$seen): void { $seen[] = $e; });
    check($flowRefused(static fn () => $kf->flows()->on('order.after.update', static fn () => null)) !== null, 'not a flow event');
    $r = $flowSend($kf, $flowEnvelope(\Kf\Sdk\FlowEvents::STEP_ENTERED, 'e-1', $flowData('cutting'), 'flow-instance'));
    check($r->status === 200 && count($seen) === 1 && $seen[0]->stepKey === 'cutting', $r->body);
    $n = $flowSend($kf, $flowEnvelope(\Kf\Sdk\FlowEvents::STEP_COMPLETED, 'e-2', $flowData('cutting'), 'flow-instance'));
    check($n->status === 200 && str_contains($n->body, 'ignored'), $n->body);
    $bad = $flowSend($kf, $flowEnvelope(\Kf\Sdk\FlowEvents::STEP_ENTERED, 'e-3', ['orderId' => 1], 'flow-instance'));
    check($bad->status === 400 && count($seen) === 1, $bad->body);
    $hooks = $kf->registrationBody()['hooks'];
    check(in_array(['event' => \Kf\Sdk\FlowEvents::STEP_ENTERED], $hooks, true) && in_array(['event' => \Kf\Sdk\FlowEvents::LINE_PROMOTED], $hooks, true),
        Json::encode($hooks));
};

$tests['FlowEventsTest: order.field.changed on status reaches the order watch; an order patch carries only its extensions'] = static function () use ($flowEnvelope, $flowSend, $flowModule): void {
    $kf = $flowModule();
    $changes = [];
    $modelChanges = [];
    $kf->orders()->onFieldChange(HookEvents::ORDER_PATH_STATUS, function ($c) use (&$changes): void { $changes[] = $c; });
    $kf->models()->onFieldChange([], function ($c) use (&$modelChanges): void { $modelChanges[] = $c; });
    $r = $flowSend($kf, $flowEnvelope(HookEvents::ORDER_FIELD_CHANGED, 'e-9', ['entity' => 'order', 'id' => '812', 'field' => 'status',
        'oldValue' => HookEvents::ORDER_STATUS_SHIPPED, 'newValue' => HookEvents::ORDER_STATUS_COMPLETED], 'order'));
    check($r->status === 200 && count($changes) === 1 && $changes[0]->newValue === 'COMPLETED' && $modelChanges === [], $r->body);
    $fc = array_values(array_filter($kf->registrationBody()['hooks'], static fn ($h) => $h['event'] === HookEvents::ORDER_FIELD_CHANGED));
    check($fc !== [] && $fc[0]['fields'] === ['status'], 'fields');
    check(in_array('COMPLETED', HookEvents::ORDER_STATUSES, true) && count(HookEvents::ORDER_POINTS) === 7, 'constants');

    $kf2 = $flowModule();
    $kf2->orders()->beforeUpdate(static fn ($ctx) => $ctx->patch()->extension('checked', true))
        ->beforeCreate(static fn ($ctx) => $ctx->patch()->set('status', 'CONFIRMED'))
        ->beforeDelete(static fn ($ctx) => $ctx->reject('order.locked'));
    $data = ['entity' => 'order', 'id' => '812', 'before' => ['status' => 'DRAFT'], 'after' => ['status' => 'CONFIRMED'], 'changed' => ['status']];
    $ok = $flowSend($kf2, $flowEnvelope(HookEvents::ORDER_BEFORE_UPDATE, 'b-1', $data, 'order'));
    check($ok->status === 200 && str_contains($ok->body, '"kf-step":{"checked":true}'), $ok->body);
    $core = $flowSend($kf2, $flowEnvelope(HookEvents::ORDER_BEFORE_CREATE, 'b-2', $data, 'order'));
    check($core->status === 500 && str_contains($core->body, 'permanent') && str_contains($core->body, 'status'), $core->body);
    $veto = $flowSend($kf2, $flowEnvelope(HookEvents::ORDER_BEFORE_DELETE, 'b-3', $data, 'order'));
    check($veto->status === 200 && str_contains($veto->body, '"reject"') && str_contains($veto->body, 'order.locked'), $veto->body);
    check($kf2->orders()->entity() === 'order' && $kf2->models()->entity() === 'model', 'entities');
};

const SERVICES_PROOF = 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd';
const SERVICES_MANIFEST = '{"schemaVersion":3,"slug":"kf-step","name":"module.name","version":"1.0.0","platform":">=4.1",'
    . '"i18n":{"en":{"module.name":"Step"}},"capabilities":{"services":[{"port":"order.services.v1"}]}}';

$tests['OrderServicesTest: upsert and delete with the context and its proof; local refusals; typed refusals'] = static function () use ($servicePlatform): void {
    [$kf, $stop, $answer, $requests, $last, $verified] = $servicePlatform('kf-step', 'services-secret', 'kfm_services-token');
    try {
        $kf->withManifest(SERVICES_MANIFEST);
        $ctx = $verified('r-1', SERVICES_PROOF);
        $answer(204, '');
        $kf->orderServices()->upsert($ctx, new \Kf\Sdk\OrderServices\Line('812', '4401', 'Embroidery', '12.50', 'EUR', 'embroidery:4401'));
        $r = $last();
        check($r['line'] === 'POST /api/v1/platform/modules/kf-step/services/order.services.v1', $r['line']);
        check($r['proof'] === SERVICES_PROOF, 'proof');
        $sent = json_decode($r['body'], true);
        check($sent['orderId'] === '812' && $sent['entryId'] === '4401' && $sent['name'] === 'Embroidery' && $sent['externalRef'] === 'embroidery:4401', $r['body']);
        check($sent['cost']['amount'] == 12.5 && $sent['cost']['currency'] === 'EUR' && !array_key_exists('source', $sent), $r['body']);

        $kf->orderServices()->delete($ctx, '812', 'embroidery:4401');
        check($last()['line'] === 'DELETE /api/v1/platform/modules/kf-step/services/order.services.v1/embroidery:4401?orderId=812', $last()['line']);

        $before = $requests();
        $line = static fn ($amount, $currency, $ref) => new \Kf\Sdk\OrderServices\Line('812', '4401', 'Embroidery', $amount, $currency, $ref);
        $e = refused(static fn () => $kf->orderServices()->upsert($ctx, $line('-1', 'EUR', 'x:1')));
        check(str_contains($e->getMessage(), 'cost.amount must be a number >= 0'), $e->getMessage());
        $e = refused(static fn () => $kf->orderServices()->upsert($ctx, $line('1', 'eur', 'x:1')));
        check(str_contains($e->getMessage(), 'cost.currency must be an ISO 4217 code'), $e->getMessage());
        $e = refused(static fn () => $kf->orderServices()->upsert($ctx, $line('1', 'EUR', 'has space')));
        check(str_contains($e->getMessage(), 'externalRef must match'), $e->getMessage());
        refused(static fn () => $kf->orderServices()->upsert($ctx, new \Kf\Sdk\OrderServices\Line('812', '4401', str_repeat('x', 201), 1, 'EUR', 'x:1')));
        refused(static fn () => $kf->orderServices()->delete($ctx, ' ', 'x:1'));
        $kf->withManifest(str_replace('"services":[{"port":"order.services.v1"}]', '"routes":[]', SERVICES_MANIFEST));
        $e = refused(static fn () => $kf->orderServices()->upsert($ctx, $line('1', 'EUR', 'x:1')));
        check(str_contains($e->getMessage(), 'not declared in capabilities.services'), $e->getMessage());
        check($requests() === $before, 'nothing sent');

        $kf->withManifest(SERVICES_MANIFEST);
        $answer(422, '{"type":"https://kf.ro/problems/not-delivered","title":"Not delivered by this platform","status":422,'
            . '"detail":"order.services.v1 is part of protocol 2.0 but this platform does not deliver it yet","class":"permanent"}');
        $e = refused(static fn () => $kf->orderServices()->upsert($ctx, $line('1', 'EUR', 'x:1')));
        check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::REFUSED && $e->reason() === 'not-delivered', 'not-delivered');
        check($e->errorClass() === ErrorClass::PERMANENT, 'permanent');
        $answer(501, '{"type":"https://kf.ro/problems/no-provider","title":"No provider","status":501,"class":"permanent"}');
        $e = refused(static fn () => $kf->orderServices()->delete($ctx, '812', 'x:1'));
        check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::UNAVAILABLE && $e->reason() === 'no-provider', 'no-provider');
        $answer(400, '{"type":"https://kf.ro/problems/validation","status":400,"class":"permanent","errors":{"entryId":"is required"}}');
        $e = refused(static fn () => $kf->orderServices()->upsert($ctx, $line('1', 'EUR', 'x:1')));
        check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::INVALID && $e->errors()['entryId'] === 'is required', 'invalid');
    } finally {
        $stop();
    }
};

// ---- ManifestFlowsTest (protocol 2.1 §17) ------------------------------------------------------------
// capabilities.flows, the module's baseline flows, checked locally with the platform's rules and messages
// (ModuleManifestValidator / ModulePackValidator) -- only the grammar and, on a pack, the files.

$flowsManifest = static fn (int $schema, string $flows): string => '{"schemaVersion":' . $schema . ',"slug":"kf-3d","name":"module.name",'
    . '"version":"1.0.0","platform":">=4.1","i18n":{"en":{"module.name":"3D","flow.twin":"Digital twin sample"}},'
    . '"capabilities":{"flows":[' . $flows . ']}}';
$flowsRefused = static function (array $errors, string $fragment): void {
    check(count(array_filter($errors, static fn ($x) => str_contains($x, $fragment))) > 0, "an error must contain <$fragment>: " . implode('; ', $errors));
};
const FLOWS_TWIN = '{"key":"kf-3d-digital-twin","file":"flows/kf-3d-digital-twin.bpmn","name":"flow.twin"}';

$tests['ManifestFlowsTest: a module flow is valid under schema 3 and read as declared; absent reads empty'] = static function () use ($flowsManifest): void {
    $json = $flowsManifest(3, FLOWS_TWIN);
    check(ManifestCheck::validate($json)->ok(), implode('; ', ManifestCheck::validate($json)->errors));
    $flow = Manifest::parse($json)->capabilities->flows[0];
    check($flow->key === 'kf-3d-digital-twin' && $flow->file === 'flows/kf-3d-digital-twin.bpmn' && $flow->name === 'flow.twin', 'typed');
    check(in_array('flows', Manifest::CAPABILITY_KEYS, true), 'flows is a capability of protocol 2.1');
    $none = str_replace('"flows":[]', '"routes":[]', $flowsManifest(3, ''));
    check(Manifest::parse($none)->capabilities->flows === [], 'absent = []');
};

$tests['ManifestFlowsTest: flows under schema 2 are refused named; the key has its grammar and is unique'] = static function () use ($flowsManifest, $flowsRefused): void {
    $v = static fn (string $json): array => ManifestCheck::validate($json)->errors;
    $flowsRefused($v($flowsManifest(2, FLOWS_TWIN)), 'capabilities.flows requires "schemaVersion": 3 (this manifest is schemaVersion 2)');
    $flowsRefused($v($flowsManifest(3, '{"key":"Twin","file":"flows/twin.bpmn","name":"flow.twin"}')),
        'capabilities.flows[0].key "Twin" must match ^[a-z][a-z0-9-]{2,62}$');
    $flowsRefused($v($flowsManifest(3, '{"key":"ab","file":"flows/ab.bpmn","name":"flow.twin"}')), 'capabilities.flows[0].key "ab" must match');
    $flowsRefused($v($flowsManifest(3, FLOWS_TWIN . ',{"key":"kf-3d-digital-twin","file":"flows/other.bpmn","name":"flow.twin"}')),
        'capabilities.flows[1].key "kf-3d-digital-twin" is declared twice');
};

$tests['ManifestFlowsTest: the file is a bpmn directly under flows/, without .., unique'] = static function () use ($flowsManifest, $flowsRefused): void {
    $v = static fn (string $json): array => ManifestCheck::validate($json)->errors;
    $flowsRefused($v($flowsManifest(3, '{"key":"kf-3d-a","file":"flows/../x.bpmn","name":"flow.twin"}')),
        'capabilities.flows[0].file "flows/../x.bpmn" must not contain ".."');
    $flowsRefused($v($flowsManifest(3, '{"key":"kf-3d-a","file":"bpmn/a.bpmn","name":"flow.twin"}')),
        'capabilities.flows[0].file "bpmn/a.bpmn" must be a .bpmn file directly under flows/');
    $flowsRefused($v($flowsManifest(3, '{"key":"kf-3d-a","file":"flows/sub/a.bpmn","name":"flow.twin"}')), 'must be a .bpmn file directly under flows/');
    $flowsRefused($v($flowsManifest(3, '{"key":"kf-3d-a","file":"flows/a.xml","name":"flow.twin"}')),
        'capabilities.flows[0].file "flows/a.xml" must be a .bpmn file directly under flows/');
    $flowsRefused($v($flowsManifest(3, '{"key":"kf-3d-a","name":"flow.twin"}')),
        'capabilities.flows[0].file null must be a .bpmn file directly under flows/');
    $flowsRefused($v($flowsManifest(3, FLOWS_TWIN . ',{"key":"kf-3d-other","file":"flows/kf-3d-digital-twin.bpmn","name":"flow.twin"}')),
        'capabilities.flows[1].file "flows/kf-3d-digital-twin.bpmn" is declared twice');
};

$tests['ManifestFlowsTest: the name is a key of i18n.en, the object is closed, at most twenty flows'] = static function () use ($flowsManifest, $flowsRefused): void {
    $v = static fn (string $json): array => ManifestCheck::validate($json)->errors;
    $flowsRefused($v($flowsManifest(3, '{"key":"kf-3d-a","file":"flows/a.bpmn","name":"flow.missing"}')),
        'capabilities.flows[0].name "flow.missing" is not defined in i18n.en');
    $flowsRefused($v($flowsManifest(3, '{"key":"kf-3d-a","file":"flows/a.bpmn"}')),
        'capabilities.flows[0].name is required: a translation key defined in i18n.en');
    $flowsRefused($v($flowsManifest(3, '{"key":"kf-3d-a","file":"flows/a.bpmn","name":"flow.twin","bpmn":"<x/>"}')),
        'capabilities.flows[0]: "bpmn" is not a key of a module flow (known: key, file, name)');
    $many = [];
    for ($i = 0; $i < 21; $i++) $many[] = '{"key":"kf-3d-f' . $i . '","file":"flows/f' . $i . '.bpmn","name":"flow.twin"}';
    $flowsRefused($v($flowsManifest(3, implode(',', $many))), 'capabilities.flows holds at most 20 flows (got 21)');
};

$tests['ManifestFlowsTest: checkPack finds a missing or too large file and reports a bad file once'] = static function () use ($flowsManifest, $flowsRefused): void {
    $json = $flowsManifest(3, FLOWS_TWIN);
    check(ManifestCheck::checkPack($json, ['flows/kf-3d-digital-twin.bpmn' => 1024])->ok(), 'present');
    check(ManifestCheck::checkPack($json, ['flows/kf-3d-digital-twin.bpmn' => 262144])->ok(), '256 KiB is the cap, inclusive');
    $flowsRefused(ManifestCheck::checkPack($json, [])->errors, 'capabilities.flows[0].file "flows/kf-3d-digital-twin.bpmn" is not in the pack');
    $flowsRefused(ManifestCheck::checkPack($json, null)->errors, 'is not in the pack');
    $flowsRefused(ManifestCheck::checkPack($json, ['flows/kf-3d-digital-twin.bpmn' => 262145])->errors,
        'capabilities.flows[0].file "flows/kf-3d-digital-twin.bpmn" is larger than 262144 bytes (got 262145)');
    $e = ManifestCheck::checkPack($flowsManifest(3, '{"key":"kf-3d-a","file":"flows/../a.bpmn","name":"flow.twin"}'), [])->errors;
    check(count($e) === 1, implode('; ', $e));
    $flowsRefused($e, 'must not contain ".."');
};

// ---- the gap closure..40, derived from the Java tests ---------------------------------------------

$gapErrors = static fn (string $json): array => ManifestCheck::validate($json)->errors;
$gapRefused = static function (array $errors, string $fragment): void {
    foreach ($errors as $e) {
        if (str_contains($e, $fragment)) return;
    }
    throw new \RuntimeException("no error contains [$fragment]: " . implode(' | ', $errors));
};

$tests['ManifestPeersTest: a flow rule path with a {param} segment is refused with the platform\'s text'] = static function () use ($gapErrors, $gapRefused): void {
    $json = '{"schemaVersion":3,"slug":"kf-step","name":"module.name","version":"1.0.0","platform":">=4.1",'
        . '"i18n":{"en":{"module.name":"Step"}},'
        . '"capabilities":{"routes":[{"method":"GET","path":"/m/kf-step/rules/{id}","right":"api.v1.m.kf-step.rules.id.get"}],'
        . '"flowRules":[{"key":"item-ready","path":"/m/kf-step/rules/{id}","projection":"order.flow-rule.v1"}]}}';
    $gapRefused($gapErrors($json), 'kf-module-conf.json: capabilities.flowRules[0].path "/m/kf-step/rules/{id}" must be a route with '
        . 'literal segments only (no {param} segment)');
};

$hooker = static fn (int $schemaVersion, string $requires, string $hook): string =>
    '{"schemaVersion":' . $schemaVersion . ',"slug":"kf-3d","name":"module.name","version":"1.0.0","platform":">=4.1",'
    . '"i18n":{"en":{"module.name":"3D"}},"requires":' . $requires . ',"capabilities":{"hooks":[' . $hook . ']}}';
const GAP_REQUIRES_STOCK = '[{"slug":"kf-stock","versionRange":">=1.0.0"}]';

$tests['ManifestPeersTest: an event of a module as a hook is checked like the platform does'] = static function () use ($gapErrors, $gapRefused, $hooker): void {
    $point = '{"point":"m.kf-stock.after.level-changed"}';
    check($gapErrors($hooker(3, GAP_REQUIRES_STOCK, $point)) === [], implode('; ', $gapErrors($hooker(3, GAP_REQUIRES_STOCK, $point))));
    $gapRefused($gapErrors($hooker(2, GAP_REQUIRES_STOCK, $point)), 'capabilities.hooks[0].point "m.kf-stock.after.level-changed": '
        . 'an event of a module requires "schemaVersion": 3');
    $gapRefused($gapErrors($hooker(3, GAP_REQUIRES_STOCK, '{"point":"m.kf-stock.after"}')),
        'capabilities.hooks[0].point "m.kf-stock.after" is not an event of a module (m.{owner}.after.{name})');
    $gapRefused($gapErrors($hooker(3, '[]', $point)), 'capabilities.hooks[0].point "m.kf-stock.after.level-changed" is an event of '
        . 'kf-stock, which this manifest does not require (requires, mandatory or optional)');
    $blocking = '{"point":"m.kf-stock.after.level-changed","onFailure":"block"}';
    $gapRefused($gapErrors($hooker(3, GAP_REQUIRES_STOCK, $blocking)),
        'capabilities.hooks[0].onFailure is not allowed on an event of a module: it runs after the fact');
    foreach ($gapErrors($hooker(3, GAP_REQUIRES_STOCK, $blocking)) as $e) {
        check(!str_contains($e, 'only on before points'), $e);
    }
    $gapRefused($gapErrors($hooker(3, GAP_REQUIRES_STOCK, "$point,$point")),
        'capabilities.hooks[1].point "m.kf-stock.after.level-changed" is declared twice');
};

$tests['ManifestPeersTest: a peer path with a {param} segment is refused with the platform\'s text'] = static function () use ($gapErrors, $gapRefused): void {
    $routes = '"routes":[{"method":"GET","path":"/m/kf-qc/peer/checks/{id}","right":"api.v1.m.kf-qc.peer.checks.id.get"},'
        . '{"method":"POST","path":"/m/kf-qc/peer/checks/{id}","right":"api.v1.m.kf-qc.peer.checks.id.post"}]';
    $json = static fn (string $caps): string => '{"schemaVersion":3,"slug":"kf-qc","name":"module.name","version":"1.0.0","platform":">=4.1",'
        . '"i18n":{"en":{"module.name":"Quality"}},"capabilities":{' . $routes . ',' . $caps . '}}';
    $gapRefused($gapErrors($json('"peerReads":[{"port":"m.kf-qc.checks.v1","path":"/m/kf-qc/peer/checks/{id}","visibility":"public"}]')),
        'kf-module-conf.json: capabilities.peerReads[0].path "/m/kf-qc/peer/checks/{id}" must be a route with literal '
        . 'segments only (no {param} segment)');
    $gapRefused($gapErrors($json('"commands":[{"name":"open-check","path":"/m/kf-qc/peer/checks/{id}","method":"POST","visibility":"public"}]')),
        'kf-module-conf.json: capabilities.commands[0].path "/m/kf-qc/peer/checks/{id}" must be a route with literal '
        . 'segments only (no {param} segment)');
};

$tests['OrderServicesTest: deleting the line of another module is a conflict refusal'] = static function () use ($servicePlatform): void {
    [$kf, $stop, $answer, , , $verified] = $servicePlatform('kf-step', 'services-secret', 'kfm_services-token');
    try {
        $kf->withManifest(SERVICES_MANIFEST);
        $ctx = $verified('r-8', SERVICES_PROOF);
        $answer(409, '{"type":"https://kf.ro/problems/order-service-line-owned-by-module","status":409,'
            . '"reason":"order-service-line-owned-by-module","class":"permanent"}');
        $e = refused(static fn () => $kf->orderServices()->delete($ctx, '812', 'x:1'));
        check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::CONFLICT, 'conflict');
        check($e->reason() === \Kf\Sdk\OrderServices::LINE_OWNED_BY_MODULE, (string) $e->reason());
        check($e->errorClass() === ErrorClass::PERMANENT, 'permanent');
    } finally {
        $stop();
    }
};

$tests['PeersClientTest: command: an empty 2xx is an answer without a body'] = static function () use ($servicePlatform): void {
    [$kf, $stop, $answer, , , $verified] = $servicePlatform('caller', 'peers-secret', 'kfm_peers-token');
    try {
        $ctx = $verified('req-8', PEERS_PROOF);
        $answer(204, '');
        $a = $kf->peers()->command($ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', ['qty' => 3]);
        check($a->status === 204 && $a->body === null, 'no body');
        $e = refused(static fn () => $a->object());
        check($e->errorClass() === ErrorClass::PERMANENT, 'object() on null is permanent');
    } finally {
        $stop();
    }
};

$tests['PeersClientTest: command: 409 idempotency-answer-not-stored is a permanent conflict refusal'] = static function () use ($servicePlatform, $problem): void {
    [$kf, $stop, $answer, , , $verified] = $servicePlatform('caller', 'peers-secret', 'kfm_peers-token');
    try {
        $ctx = $verified('req-9', PEERS_PROOF);
        $answer(409, $problem(409, 'idempotency-answer-not-stored', 'permanent'));
        $e = refused(static fn () => $kf->peers()->command($ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', ['qty' => 3]));
        check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::CONFLICT, 'conflict');
        check($e->reason() === \Kf\Sdk\PeersClient::IDEMPOTENCY_ANSWER_NOT_STORED, (string) $e->reason());
        check($e->errorClass() === ErrorClass::PERMANENT, 'permanent');
    } finally {
        $stop();
    }
};

$tests['PeersClientTest: command: 502 invalid-answer permanent is an INVALID_ANSWER refusal'] = static function () use ($servicePlatform, $problem): void {
    [$kf, $stop, $answer, , , $verified] = $servicePlatform('caller', 'peers-secret', 'kfm_peers-token');
    try {
        $ctx = $verified('req-10', PEERS_PROOF);
        $answer(502, $problem(502, 'invalid-answer', 'permanent'));
        $e = refused(static fn () => $kf->peers()->command($ctx, 'kf-stock', 'reserve', 'key-0001-abcdef', ['qty' => 3]));
        check($e instanceof PlatformRefusal && $e->kind() === PlatformRefusalKind::INVALID_ANSWER, 'invalid-answer');
        check($e->reason() === \Kf\Sdk\PeersClient::INVALID_ANSWER, (string) $e->reason());
        check($e->errorClass() === ErrorClass::PERMANENT, 'permanent');
    } finally {
        $stop();
    }
};

$tests['SlotContributionsTest: a data contribution with a null value registers'] = static function (): void {
    $kf = KfModule::of('http://127.0.0.1:1', 'slot-contrib', 'kfm_x', 'secret', null);
    $kf->ui()->contribute('m.kf-3d.materials.v1', \Kf\Sdk\Surface::data(['finish' => null, 'a' => 1])->key('wool'));
    $json = json_encode($kf->registrationBody()['surfaces'][0], JSON_UNESCAPED_SLASHES);
    check(str_contains($json, '"data":{"finish":null,"a":1}'), $json);
};

$failed = 0;
foreach ($tests as $name => $body) {
    try {
        $body();
        echo "ok   $name\n";
    } catch (\Throwable $e) {
        $failed++;
        echo "FAIL $name: " . $e::class . ': ' . $e->getMessage() . "\n";
    }
}
echo sprintf("\n%d tests, %d passed, %d failed\n", count($tests), count($tests) - $failed, $failed);
exit($failed === 0 ? 0 : 1);
