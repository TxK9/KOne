// Test-only dependency resolution; no personal paths are stored in the shared tree.
const path = require('node:path');
function resolveDependency(name) {
  try { return require.resolve(name); } catch { /* try explicitly provided module roots */ }
  const roots = [process.env.KONE_TEST_NODE_MODULES, process.env.NODE_PATH].filter(Boolean).flatMap(value => value.split(path.delimiter)).filter(Boolean);
  for (const root of roots) {
    try { return require.resolve(path.join(root, name)); } catch { /* next root */ }
  }
  throw new Error('Install ' + name + ' locally or set KONE_TEST_NODE_MODULES / NODE_PATH.');
}
exports.playwright = () => require(resolveDependency('playwright'));
exports.launchOptions = () => ({headless: true, ...(process.env.KONE_TEST_BROWSER ? {executablePath: process.env.KONE_TEST_BROWSER} : {channel: 'chrome'})});
exports.babelTools = () => {
  try {
    const parser = require(resolveDependency('@babel/parser'));
    const traversal = require(resolveDependency('@babel/traverse'));
    return {babelParse: code => parser.parse(code, {sourceType: 'module'}), traverse: traversal.default || traversal};
  } catch {
    // The existing local Playwright distribution can supply its parser without an install.
    const bundle = require(path.join(path.dirname(resolveDependency('playwright')), 'lib/transform/babelBundle.js'));
    return {babelParse: (code, filename) => bundle.babelParse(code, filename, true), traverse: bundle.traverse};
  }
};
