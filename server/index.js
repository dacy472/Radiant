const express = require('express');
const cors = require('cors');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const PORT = 3001;

app.use(cors());
app.use(bodyParser.json());

// In-memory data storage (simulating a database)
let settings = {
    repoUrl: ""
};
let extensions = [
    { id: "picsum", name: "Picsum", lang: "EN", version: "1.0.0" },
    { id: "mock", name: "Mock (Local)", lang: "EN", version: "1.0.0" }
];
let repoExtensions = [];
let library = [];

// Extensions Endpoints
app.get('/extensions', (req, res) => {
    res.json(extensions);
});

app.get('/extensions/repo', async (req, res) => {
    if (!settings.repoUrl) {
        return res.json({ name: "Default Repo", description: "No repo URL set", extensions: [] });
    }
    
    try {
        const response = await axios.get(settings.repoUrl);
        repoExtensions = response.data.extensions || [];
        res.json(response.data);
    } catch (e) {
        res.status(500).json({ error: "Failed to fetch repository: " + e.message });
    }
});

app.post('/extensions/install', (req, res) => {
    const { repoId } = req.body;
    const ext = repoExtensions.find(e => e.id === repoId) || 
               extensions.find(e => e.id === repoId);
    
    if (ext && !extensions.find(e => e.id === repoId)) {
        extensions.push(ext);
    }
    res.json({ success: true });
});

app.delete('/extensions/:id', (req, res) => {
    extensions = extensions.filter(e => e.id !== req.params.id);
    res.json({ success: true });
});

// Library Endpoints
app.get('/library', (req, res) => {
    res.json(library);
});

app.post('/library', (req, res) => {
    const { sourceId, webtoonId } = req.body;
    if (!library.find(i => i.id === webtoonId)) {
        library.push({ id: webtoonId, sourceId });
    }
    res.json({ success: true });
});

app.delete('/library/:extId/:webtoonId', (req, res) => {
    library = library.filter(i => !(i.id === req.params.webtoonId && i.sourceId === req.params.extId));
    res.json({ success: true });
});

app.post('/library/sync', (req, res) => {
    library = req.body;
    res.json({ success: true });
});

// Settings Endpoints
app.patch('/settings', (req, res) => {
    settings = { ...settings, ...req.body };
    console.log("Settings updated:", settings);
    res.json({ success: true });
});

app.listen(PORT, '0.0.0.0', () => {
    console.log(`WebtoonReader Backend listening at http://0.0.0.0:${PORT}`);
});
