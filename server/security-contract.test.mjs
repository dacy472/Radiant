import assert from 'node:assert/strict';
import http from 'node:http';

process.env.RADIANT_SERVER_TEST = '1';
process.env.RADIANT_DEV_TOKEN = 'test-token';
process.env.BIND_HOST = '127.0.0.1';

const { app } = await import('./index.js');

function request(server, { method, path, body, token } = {}) {
  return new Promise((resolve, reject) => {
    const address = server.address();
    const payload = body ? JSON.stringify(body) : null;
    const req = http.request(
      {
        host: '127.0.0.1',
        port: address.port,
        method,
        path,
        headers: {
          ...(payload ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) } : {}),
          ...(token ? { authorization: `Bearer ${token}` } : {}),
        },
      },
      (res) => {
        let data = '';
        res.on('data', (chunk) => {
          data += chunk;
        });
        res.on('end', () => resolve({ status: res.statusCode, body: data }));
      },
    );
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

const server = app.listen(0, '127.0.0.1');
await new Promise((resolve) => server.once('listening', resolve));

const unauth = await request(server, { method: 'POST', path: '/extensions/install', body: { id: 'en.mock' } });
assert.equal(unauth.status, 401);

const forbiddenMethod = await request(server, {
  method: 'POST',
  path: '/extensions/en.mock/run',
  token: 'test-token',
  body: { method: 'constructor', args: [] },
});
assert.equal(forbiddenMethod.status, 400);

const token = 'test-token';
const available = await request(server, { method: 'GET', path: '/extensions/available' });
assert.equal(available.status, 200);
assert.equal(typeof JSON.parse(available.body).available, 'object');

const repo = await request(server, { method: 'GET', path: '/extensions/repo' });
assert.equal(repo.status, 200);
assert.deepEqual(JSON.parse(repo.body).extensions, []);

const installedLocked = await request(server, { method: 'GET', path: '/extensions' });
assert.equal(installedLocked.status, 401);
const installed = await request(server, { method: 'GET', path: '/extensions', token });
assert.equal(installed.status, 200);
assert.ok(Array.isArray(JSON.parse(installed.body)));

const refreshLocked = await request(server, { method: 'POST', path: '/extensions/repo/refresh', body: {} });
assert.equal(refreshLocked.status, 401);
const refreshMissing = await request(server, {
  method: 'POST',
  path: '/extensions/repo/refresh',
  token,
  body: {},
});
assert.equal(refreshMissing.status, 400);

const installMissing = await request(server, {
  method: 'POST',
  path: '/extensions/install',
  token,
  body: {},
});
assert.equal(installMissing.status, 400);
const installUnknown = await request(server, {
  method: 'POST',
  path: '/extensions/install',
  token,
  body: { id: 'en.does-not-exist' },
});
assert.equal(installUnknown.status, 500);

const runMissing = await request(server, {
  method: 'POST',
  path: '/extensions/en.mock/run',
  token,
  body: { method: 'getPopularWebtoons', args: [] },
});
assert.equal(runMissing.status, 404);
const runBadArgs = await request(server, {
  method: 'POST',
  path: '/extensions/en.mock/run',
  token,
  body: { method: 'getPopularWebtoons', args: 'nope' },
});
assert.equal(runBadArgs.status, 400);

const removed = await request(server, { method: 'DELETE', path: '/extensions/en.mock', token });
assert.equal(removed.status, 200);

const libraryLocked = await request(server, { method: 'GET', path: '/library' });
assert.equal(libraryLocked.status, 401);
const libraryEmpty = await request(server, { method: 'GET', path: '/library', token });
assert.equal(libraryEmpty.status, 200);
assert.deepEqual(JSON.parse(libraryEmpty.body), []);

const libraryBad = await request(server, { method: 'POST', path: '/library', token, body: {} });
assert.equal(libraryBad.status, 400);
const libraryAdd = await request(server, {
  method: 'POST',
  path: '/library',
  token,
  body: { sourceId: 'en.mock', webtoonId: 'series-1' },
});
assert.equal(libraryAdd.status, 200);
const libraryAfter = JSON.parse((await request(server, { method: 'GET', path: '/library', token })).body);
assert.equal(libraryAfter.length, 1);

const libraryDelete = await request(server, {
  method: 'DELETE',
  path: '/library/en.mock/series-1',
  token,
});
assert.equal(libraryDelete.status, 200);

const syncBad = await request(server, { method: 'POST', path: '/library/sync', token, body: { nope: true } });
assert.equal(syncBad.status, 400);
const syncOk = await request(server, {
  method: 'POST',
  path: '/library/sync',
  token,
  body: [{ id: 'series-2', sourceId: 'en.mock' }],
});
assert.equal(syncOk.status, 200);

const settingsBad = await request(server, { method: 'PATCH', path: '/settings', token, body: ['nope'] });
assert.equal(settingsBad.status, 400);
const settingsOk = await request(server, {
  method: 'PATCH',
  path: '/settings',
  token,
  body: { repoUrl: '' },
});
assert.equal(settingsOk.status, 200);

const wrongToken = await request(server, { method: 'GET', path: '/library', token: 'wrong' });
assert.equal(wrongToken.status, 401);

server.close();

const { assertTrustedArtifact, HOST_FS_MARKER } = await import('./artifact-trust.mjs');

assert.throws(
  () =>
    assertTrustedArtifact({
      extensionId: 'en.mock',
      publisher: 'radiant-official',
      expectedSha256: 'abc',
      actualSha256: 'def',
      code: 'module.exports = {}',
    }),
  /Tampered bundle/,
);

assert.throws(
  () =>
    assertTrustedArtifact({
      extensionId: 'en.mock',
      publisher: 'untrusted-publisher',
      expectedSha256: 'abc',
      actualSha256: 'abc',
      code: 'module.exports = {}',
    }),
  /Untrusted publisher/,
);

assert.throws(
  () =>
    assertTrustedArtifact({
      extensionId: 'en.mock',
      publisher: 'radiant-official',
      expectedSha256: 'abc',
      actualSha256: 'abc',
      code: `/* ${HOST_FS_MARKER} */ import fs from 'node:fs';`,
    }),
  /host-fs/,
);

console.log('server auth contract ok');
