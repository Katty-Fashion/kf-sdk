// Stand-ins for the host's @kf/ui primitives (smoke test only).
// Each renders its children, so the smoke test sees the text inside a Card.
const component = (name) => {
  const c = ({ children, ...props }) => ({ type: name, props, children: children || [] });
  c.displayName = name;
  return c;
};
export const Badge = component('Badge');
export const Button = component('Button');
export const Card = component('Card');
export const CardContent = component('CardContent');
export const CardDescription = component('CardDescription');
export const CardHeader = component('CardHeader');
export const CardTitle = component('CardTitle');
