import express from 'express';
import cors from 'cors';
import bodyParser from 'body-parser';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  defaultExtensionRepository,
  ExtensionBundleError,
  isScrapingSourceMethod,
  normalizeRepoUrl,
  SourceExecutionError,
} from '@radiant/extension-sdk';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = express();
const PORT = Number(process.env.PORT || 3001);
const BIND_HOST = process.env.BIND_HOST || '127.0.0.1';
const AUTH_TOKEN = process.env.RADIANT_DEV_TOKEN || '';
const CORS_ORIGIN = process.env.CORS_ORIGIN || 'http://127.0.0.1:3001';

app.use(cors({ origin: CORS_ORIGIN }));
app.use(bodyParser.json({ limit: '1mb' }));

function requireAuth(req, res, next) {
  const header = req.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!AUTH_TOKEN || token !== AUTH_TOKEN) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  return next();
}

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
app.use((req, res, next) => {
  if (MUTATING.has(req.method) || req.path.startsWith('/library') && req.method !== 'GET') {
    return requireAuth(req, res, next);
  }
  if (req.path === '/extensions' && req.method === 'GET') {
    return requireAuth(req, res, next);
  }
  return next();
});

let settings = {
  repoUrl: '',
};

let library = [];

function catalogPayload() {
  const state = defaultExtensionRepository.getState();
  return {
    available: state.available,
    installed: state.installed,
    installingId: state.installingId,
    isLoading: state.isLoading,
    error: state.error,
    installError: state.installError,
    repoUrl: state.repoUrl,
    repoName: state.repoName,
    lastFetchedAt: state.lastFetchedAt,
  };
}

app.get('/extensions/available', (_req, res) => {
  res.json(catalogPayload());
});

app.post('/extensions/repo/refresh', async (req, res) => {
  const repoUrl = req.body?.repoUrl ?? req.body?.url ?? settings.repoUrl;
  if (!repoUrl || typeof repoUrl !== 'string') {
    return res.status(400).json({ error: 'repoUrl is required' });
  }

  try {
    settings.repoUrl = normalizeRepoUrl(repoUrl);
    await defaultExtensionRepository.fetchIndex(settings.repoUrl);
    res.json(catalogPayload());
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to refresh repository';
    res.status(500).json({ error: message, ...catalogPayload() });
  }
});

app.get('/extensions/repo', async (_req, res) => {
  if (!settings.repoUrl) {
    return res.json({
      name: 'Default Repo',
      description: 'No repo URL set',
      extensions: [],
      ...catalogPayload(),
    });
  }

  try {
    await defaultExtensionRepository.fetchIndex(settings.repoUrl);
    const state = defaultExtensionRepository.getState();
    res.json({
      name: state.repoName ?? 'Extension Repository',
      description: '',
      extensions: state.available,
      ...catalogPayload(),
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to fetch repository';
    res.status(500).json({ error: message, ...catalogPayload() });
  }
});

app.get('/extensions', (_req, res) => {
  res.json(defaultExtensionRepository.getInstalledExtensions());
});

app.post('/extensions/install', async (req, res) => {
  const extensionId = req.body?.repoId ?? req.body?.extensionId ?? req.body?.id;
  if (!extensionId || typeof extensionId !== 'string') {
    return res.status(400).json({ error: 'extensionId is required' });
  }

  try {
    const result = await defaultExtensionRepository.installExtension(extensionId);
    res.json({
      success: true,
      installedId: result.extension.id,
      sourceId: result.sourceId,
      extension: result.extension,
      ...catalogPayload(),
    });
  } catch (error) {
    const status = error instanceof ExtensionBundleError && error.code === 'already_installed' ? 409 : 500;
    const message = error instanceof Error ? error.message : 'Install failed';
    res.status(status).json({ ...catalogPayload(), success: false, error: message });
  }
});

const ALLOWED_RUN_METHODS = new Set([
  'getPopularWebtoons',
  'getLatestWebtoons',
  'searchWebtoons',
  'getWebtoonDetail',
  'getChapterList',
  'getChapterPages',
]);

app.post('/extensions/:id/run', async (req, res) => {
  const { method, args = [] } = req.body ?? {};
  if (typeof method !== 'string' || !ALLOWED_RUN_METHODS.has(method)) {
    return res.status(400).json({ error: 'method is not allowed' });
  }
  if (!Array.isArray(args)) {
    return res.status(400).json({ error: 'args must be an array' });
  }

  const extensionId = req.params.id;
  const source = defaultExtensionRepository.getSource(extensionId);
  if (!source) {
    return res.status(404).json({ error: 'Installed source not found' });
  }

  try {
    const value = isScrapingSourceMethod(method)
      ? await defaultExtensionRepository.runSourceMethod(extensionId, method, args)
      : await source[method](...args);
    res.json({ success: true, value });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Source invocation failed';
    const status =
      error instanceof SourceExecutionError && error.code === 'timeout' ? 504 : 500;
    res.status(status).json({ success: false, error: message });
  }
});

app.delete('/extensions/:id', (req, res) => {
  defaultExtensionRepository.uninstallExtension(req.params.id);
  res.json({ success: true, ...catalogPayload() });
});

app.get('/library', requireAuth, (_req, res) => {
  res.json(library);
});

app.post('/library', (req, res) => {
  const { sourceId, webtoonId } = req.body ?? {};
  if (typeof sourceId !== 'string' || typeof webtoonId !== 'string') {
    return res.status(400).json({ error: 'sourceId and webtoonId are required' });
  }
  if (!library.find((item) => item.id === webtoonId)) {
    library.push({ id: webtoonId, sourceId });
  }
  res.json({ success: true });
});

app.delete('/library/:extId/:webtoonId', (req, res) => {
  library = library.filter(
    (item) => !(item.id === req.params.webtoonId && item.sourceId === req.params.extId),
  );
  res.json({ success: true });
});

app.post('/library/sync', (req, res) => {
  if (!Array.isArray(req.body)) {
    return res.status(400).json({ error: 'body must be an array' });
  }
  library = req.body;
  res.json({ success: true });
});

app.patch('/settings', (req, res) => {
  if (!req.body || typeof req.body !== 'object' || Array.isArray(req.body)) {
    return res.status(400).json({ error: 'body must be an object' });
  }
  settings = { ...settings, ...req.body };
  res.json({ success: true });
});

app.use('/fixtures', express.static(path.join(__dirname, 'fixtures')));

export { app, requireAuth };

if (process.env.RADIANT_SERVER_TEST !== '1') {
  app.listen(PORT, BIND_HOST, () => {
    console.log(`WebtoonReader Backend listening at http://${BIND_HOST}:${PORT}`);
  });
}
