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
const PORT = 3001;

app.use(cors());
app.use(bodyParser.json());

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

// ─── Extension catalog ──────────────────────────────────────────────────────

app.get('/extensions/available', (_req, res) => {
  res.json(catalogPayload());
});

app.post('/extensions/repo/refresh', async (req, res) => {
  const repoUrl = req.body?.repoUrl ?? req.body?.url ?? settings.repoUrl;
  if (!repoUrl) {
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

// ─── Installed extensions ───────────────────────────────────────────────────

app.get('/extensions', (_req, res) => {
  res.json(defaultExtensionRepository.getInstalledExtensions());
});

app.post('/extensions/install', async (req, res) => {
  const extensionId = req.body?.repoId ?? req.body?.extensionId ?? req.body?.id;
  if (!extensionId) {
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

/** Invoke a Source method on an installed JS extension (smoke-test / proxy API). */
app.post('/extensions/:id/run', async (req, res) => {
  const extensionId = req.params.id;
  const source = defaultExtensionRepository.getSource(extensionId);
  if (!source) {
    return res.status(404).json({ error: 'Installed source not found' });
  }

  const { method, args = [] } = req.body ?? {};
  if (typeof method !== 'string') {
    return res.status(400).json({ error: 'method must name a function on the Source instance' });
  }

  try {
    const value = isScrapingSourceMethod(method)
      ? await defaultExtensionRepository.runSourceMethod(extensionId, method, args)
      : await (() => {
          if (typeof source[method] !== 'function') {
            throw new Error('method must name a function on the Source instance');
          }
          return source[method](...args);
        })();
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

// ─── Library ────────────────────────────────────────────────────────────────

app.get('/library', (_req, res) => {
  res.json(library);
});

app.post('/library', (req, res) => {
  const { sourceId, webtoonId } = req.body;
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
  library = req.body;
  res.json({ success: true });
});

// ─── Settings ───────────────────────────────────────────────────────────────

app.patch('/settings', (req, res) => {
  settings = { ...settings, ...req.body };
  console.log('Settings updated:', settings);
  res.json({ success: true });
});

app.use('/fixtures', express.static(path.join(__dirname, 'fixtures')));

app.listen(PORT, '0.0.0.0', () => {
  console.log(`WebtoonReader Backend listening at http://0.0.0.0:${PORT}`);
});
