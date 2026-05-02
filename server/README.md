# Radiant Webtoon Backend

This is a simple Node.js backend server for the Radiant Webtoon Reader app. It handles extension discovery, repository importing, and library synchronization.

## Prerequisites
- [Node.js](https://nodejs.org/) (Version 16 or higher)

## Installation

1. Navigate to the `server` directory.
2. Install dependencies:
   ```bash
   npm install
   ```

## Running the Server

To start the server:
```bash
npm start
```
By default, the server runs on `http://localhost:3001`.

## Connecting the App

### Using Android Emulator
If you are using the official Android Emulator, the app is pre-configured to look for the server at `http://10.0.2.2:3001`. 

### Using a Physical Device
1. Find your computer's local IP address (e.g., `192.168.1.5`).
2. In the App, go to **Settings > Advanced**.
3. Change the **Backend Base URL** to `http://YOUR_IP:3001`.

## Features
- **Extension Repo**: Paste a direct link to an `index.json` file in the app (Extensions > Add Repo) to load remote extensions.
- **Library Sync**: Automatically keeps your library in sync across devices (if connected to the same server).
- **Settings**: Remote settings management (experimental).
