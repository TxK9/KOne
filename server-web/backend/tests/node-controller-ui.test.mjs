import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {dependencyClosure, rootsWithout, authorizationKey, executionKey, canManage, nodeAvailable, combinedImpact} from '../web/js/controller-model.js';

const graph = [
  {id: 'relay', dependencyIds: [], deleted: false},
  {id: 'exit', dependencyIds: ['relay'], deleted: false},
  {id: 'second-hop', dependencyIds: ['exit'], deleted: false},
  {id: 'independent', dependencyIds: [], deleted: false},
];
const capable = {registered: true, protocol: 1, startupLease: true, runtimeLease: true, capabilitiesReported: true};

test('withdrawing a relay withdraws its dependent exits but preserves unrelated roots', () => {
  assert.deepEqual(rootsWithout(['exit', 'second-hop', 'independent'], ['relay'], graph), ['independent']);
  assert.deepEqual(rootsWithout(['exit', 'independent'], ['exit'], graph), ['independent']);
  assert.deepEqual(rootsWithout(['independent'], ['missing'], graph), ['independent']);
});

test('dependency closure terminates on cycles and shared dependencies', () => {
  assert.deepEqual([...dependencyClosure(['exit', 'second-hop'], graph)].sort(), ['exit', 'relay', 'second-hop']);
  assert.deepEqual([...dependencyClosure(['a'], [{id: 'a', dependencyIds: ['b']}, {id: 'b', dependencyIds: ['a']}])].sort(), ['a', 'b']);
});

test('a deleted or missing relay makes an otherwise active exit unassignable', () => {
  assert.equal(nodeAvailable(graph[1], graph), true);
  assert.equal(nodeAvailable(graph[1], graph.map(node => node.id === 'relay' ? {...node, deleted: true} : node)), false);
  assert.equal(nodeAvailable(graph[1], graph.filter(node => node.id !== 'relay')), false);
  assert.equal(nodeAvailable({...graph[1], deleted: true}, graph), false);
});

test('revoked authorization is separate from stale execution acknowledgments', () => {
  const device = {...capable, controllerEnabled: true, controllerMode: 'revoked', status: 'revoked', authGeneration: 8, ackGeneration: 7, ackStatus: 'stopped'};
  assert.equal(authorizationKey(device), 'Authorization revoked');
  assert.equal(executionKey(device), 'Awaiting device confirmation');
  assert.equal(executionKey({...device, ackGeneration: 8}), 'Client confirmed stopped');
});

test('saved and received configurations are not presented as running', () => {
  const device = {...capable, controllerEnabled: true, controllerMode: 'enabled', authGeneration: 3, ackGeneration: 3, leaseNotAfter: Date.now() / 1000 + 60};
  assert.equal(executionKey({...device, ackStatus: 'received'}), 'Received');
  assert.equal(executionKey({...device, ackStatus: 'installed'}), 'Installed');
  assert.equal(executionKey({...device, ackStatus: 'running'}), 'Client confirmed running');
  assert.equal(executionKey({...device, ackStatus: 'failed'}), 'Application failed');
  assert.equal(executionKey(device), 'Awaiting device confirmation');
});

test('unregistered or unsupported clients cannot be enabled through assignment controls', () => {
  assert.equal(canManage({registered: false, status: 'legacy'}), false);
  assert.equal(canManage({registered: true, status: 'unsupported'}), false);
  assert.equal(canManage({...capable, status: 'revoked'}), true);
  assert.equal(canManage({...capable, runtimeLease: false}), false);
  assert.equal(executionKey({registered: false, controllerMode: 'revoked'}), 'Stop unconfirmed — client update required');
  assert.equal(authorizationKey({controllerMode: 'legacy'}), 'Not managed');
});

test('a historical running ACK cannot claim execution after the authorization lease expires', () => {
  const device = {...capable, controllerMode: 'enabled', controllerEnabled: true, authGeneration: 3, ackGeneration: 3, ackStatus: 'running', leaseNotAfter: 100};
  assert.equal(executionKey(device, 99), 'Client confirmed running');
  assert.equal(executionKey(device, 100), 'Lease expired — stop unconfirmed');
  assert.equal(executionKey({...device, leaseExpired: true}, 99), 'Lease expired — stop unconfirmed');
  assert.equal(executionKey({...device, ackStatus: 'stopped'}, 101), 'Client confirmed stopped');
});

test('impact previews combine roots once and retain the newest device generation', () => {
  const impact = combinedImpact([
    {dependentNodeIds: ['exit'], devices: [{id: 'd', authGeneration: 9, affectedRootNodeIds: ['exit']}]},
    {dependentNodeIds: ['exit', 'second-hop'], devices: [{id: 'd', authGeneration: 8, affectedRootNodeIds: ['second-hop']}]},
  ]);
  assert.equal(impact.devices.length, 1);
  assert.equal(impact.devices[0].authGeneration, 9);
  assert.deepEqual(impact.devices[0].affectedRootNodeIds, ['exit', 'second-hop']);
  assert.deepEqual(impact.dependentNodeIds, ['exit', 'second-hop']);
});

for (const locale of ['en', 'zh-CN']) test(locale + ': node controls and execution states are localized', async () => {
  globalThis.localStorage = {getItem: () => locale};
  const i = await import('../web/js/i18n.js?controller=' + locale);
  const messages = ['Node center', 'Assign nodes', 'Withdraw nodes', 'Delete nodes', 'Restore nodes', 'Authorization revoked', 'Awaiting device confirmation', 'Client confirmed running', 'Client confirmed stopped', 'Client update required', 'Missing dependency', 'Save access'];
  for (const key of messages) {
    assert.ok(Object.hasOwn(i.EN, key), key);
    assert.notEqual(i.t(key), i.t('系统消息不可用'), key);
    assert.equal(i.errorText(i.t(key)), i.t(key), key);
    if (locale === 'en') assert.ok(!/[\u3400-\u9fff]/u.test(i.t(key)), key);
    else assert.ok(/[\u3400-\u9fff]/u.test(i.t(key)), key);
  }
  const code = await readFile(new URL('../web/js/pages/nodes.js', import.meta.url), 'utf8');
  for (const [, key] of code.matchAll(/\bt\('([^']+)'/g)) assert.ok(Object.hasOwn(i.EN, key), key);
});
