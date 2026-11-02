// Metadata-only helpers shared by node and device controls.

export const unique = values => [...new Set(values)];

export function dependencyClosure(ids, nodes) {
  const byId = new Map(nodes.map(node => [node.id, node]));
  const seen = new Set();
  const visit = id => {
    if (seen.has(id)) return;
    seen.add(id);
    for (const dependency of byId.get(id)?.dependencyIds || []) visit(dependency);
  };
  ids.forEach(visit);
  return seen;
}

export function rootsWithout(roots, removedIds, nodes) {
  const removed = new Set(removedIds);
  return roots.filter(root => ![...dependencyClosure([root], nodes)].some(id => removed.has(id)));
}

export function authorizationKey(device) {
  if (device.controllerMode === 'revoked' || device.status === 'revoked') return 'Authorization revoked';
  if (!device.controllerEnabled) return 'Not managed';
  return 'Authorized';
}

export function executionKey(device, now = Date.now() / 1000) {
  if (!canManage(device)) return authorizationKey(device) === 'Authorization revoked'
    ? 'Stop unconfirmed — client update required' : device.registered ? 'Controller not supported' : 'Client update required';
  if (device.controllerMode === 'legacy' || !device.controllerMode && !device.controllerEnabled) return 'Not managed';
  if (device.ackGeneration !== device.authGeneration) return 'Awaiting device confirmation';
  if (device.ackStatus === 'running' && (device.leaseExpired || !Number.isFinite(device.leaseNotAfter) || device.leaseNotAfter <= now)) return 'Lease expired — stop unconfirmed';
  return ({
    received: 'Received', installed: 'Installed', running: 'Client confirmed running',
    stopped: 'Client confirmed stopped', failed: 'Application failed',
  })[device.ackStatus] || 'Awaiting device confirmation';
}

export function canManage(device) {
  return device.registered === true && device.protocol === 1 && device.startupLease === true && device.runtimeLease === true
    && device.capabilitiesReported !== false && device.status !== 'unsupported';
}

export function nodeAvailable(node, nodes) {
  if (!node || node.deleted) return false;
  const byId = new Map(nodes.map(item => [item.id, item]));
  return [...dependencyClosure([node.id], nodes)].every(id => byId.has(id) && !byId.get(id).deleted);
}

export function combinedImpact(impacts) {
  const deviceMap = new Map();
  for (const impact of impacts) for (const device of impact.devices || []) {
    const previous = deviceMap.get(device.id);
    const current = !previous || device.authGeneration >= previous.authGeneration ? device : previous;
    deviceMap.set(device.id, {...current,
      affectedRootNodeIds: unique([...(previous?.affectedRootNodeIds || []), ...(device.affectedRootNodeIds || [])])});
  }
  return {dependentNodeIds: unique(impacts.flatMap(item => item.dependentNodeIds || [])), devices: [...deviceMap.values()]};
}
