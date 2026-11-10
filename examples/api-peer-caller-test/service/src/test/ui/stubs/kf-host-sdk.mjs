// Stand-ins for the host's @kf/host-sdk (smoke test only).
export const apiVersion = 5;
export const calls = [];
export function http(path) {
  calls.push(path);
  return Promise.resolve({ item: { id: 'item-1', name: 'Button A' } });
}
export function moduleUrl(module, path) {
  return '/api/proxy/m/' + module + '/' + (path || '');
}
export function locale() {
  return 'en';
}
