# Node backend trust model

The Android app does **not** expose this server. `server/index.js` is a local development tool.

## Binding

- Default listen address is `127.0.0.1`.
- Set `BIND_HOST=0.0.0.0` only when you intentionally expose the port on the LAN.

## Authentication

Mutating and private routes require `Authorization: Bearer <token>` matching `RADIANT_DEV_TOKEN`.
If the token is unset, those routes return 401.

## CORS

Allowed origin defaults to `http://127.0.0.1:3001`. Override with `CORS_ORIGIN`.

## Extension code

Repository ESM is never loaded with host `import()`. Catalog ID allowlists are not sufficient: install requires a trusted `publisher` (`radiant-official`) and, when advertised, a matching `sha256`. Bundles containing the `RADIANT_HOST_FS` marker or `node:fs` are rejected before `vm` execution. Script bundles, if executed at all, run in `node:vm` which is **not** an OS security boundary. Do not deploy this server to the public internet.
