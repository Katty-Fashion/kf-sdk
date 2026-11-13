// kf-sdk-derived-from: sdk/java@bf5512745ef55345ddad6bc20d570bd44dfc99f7 v4.1.0
// Derived by /kf-sdk-derive from ro/kf/sdk/DescribeErrorTest.java. Do not edit by hand.
//
// KfModule.describe: the one place a registration failure becomes a log line.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { KfModule } from '../dist/index.js';

/** An error of a given name (the TS twin of a Java exception class), with an optional message and cause. */
function error(name, message = '', cause) {
  const e = cause === undefined ? new Error(message) : new Error(message, { cause });
  e.name = name;
  return e;
}

test('describe: an empty message gives way to the cause name, duplicates collapsed', () => {
  // the shape of a refused connection: the reason sits two causes down
  const refused = error('ConnectException', '', error('ConnectException', '', error('ClosedChannelException')));
  assert.equal(KfModule.describe(refused), 'ConnectException (cause: ClosedChannelException)');
});

test('describe: an unresolvable host is named as such', () => {
  assert.equal(KfModule.describe(error('ConnectException', '', error('UnresolvedAddressException'))),
    'ConnectException (cause: UnresolvedAddressException)');
});

test('describe: cause messages are kept when they exist', () => {
  assert.equal(KfModule.describe(error('ConnectException', '', error('ConnectException', 'Connection refused'))),
    'ConnectException (cause: ConnectException: Connection refused)');
});

test('describe: an error with a message and no cause is unchanged', () => {
  assert.equal(KfModule.describe(error('HttpConnectTimeoutException', 'HTTP connect timed out')),
    'HttpConnectTimeoutException: HTTP connect timed out');
});

test('describe: a cyclic cause chain terminates', () => {
  const a = error('Exception', 'a');
  const b = error('Exception', 'b', a);
  a.cause = b;
  assert.equal(KfModule.describe(a), 'Exception: a (cause: Exception: b)');
});
