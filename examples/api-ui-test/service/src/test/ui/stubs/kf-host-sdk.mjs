// Stand-ins for the host's @kf/host-sdk (smoke test only).
export const apiVersion = 3;
export const calls = [];
export function http(path) {
  calls.push(path);
  return Promise.resolve({ module: 'api-ui-test' });
}
export function moduleUrl(module, path) {
  return '/api/proxy/m/' + module + '/' + (path || '');
}
export function locale() {
  return 'en';
}
