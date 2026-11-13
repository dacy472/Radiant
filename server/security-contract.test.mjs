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
