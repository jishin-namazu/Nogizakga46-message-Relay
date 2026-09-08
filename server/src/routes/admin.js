import express from 'express';
import fs from 'fs/promises';
import path from 'path';
import { recordError } from '../services/error-log.js';

const router = express.Router();

/**
 * POST /v1/admin/browser-session
 * Upload new browser session state without redeployment
 */
router.post('/browser-session', async (req, res) => {
  try {
    const { session } = req.body;

    if (!session || typeof session !== 'object') {
      return res.status(400).json({
        success: false,
        error: 'Missing or invalid session data. Expected JSON object with cookies, origins, and localStorage.',
      });
    }

    // Validate session structure
    if (!session.cookies || !Array.isArray(session.cookies)) {
      return res.status(400).json({
        success: false,
        error: 'Invalid session structure: missing or invalid cookies array',
      });
    }

    if (!session.origins || !Array.isArray(session.origins)) {
      return res.status(400).json({
        success: false,
        error: 'Invalid session structure: missing or invalid origins array',
      });
    }

    // Get the state file path from environment or use default
    const stateFilePath = process.env.NOGI_BROWSER_STATE_FILE || '/data/nogi-browser-state.json';
    const stateDir = path.dirname(stateFilePath);

    // Ensure directory exists
    try {
      await fs.access(stateDir);
    } catch {
      await fs.mkdir(stateDir, { recursive: true });
    }

    // Write session to file
    await fs.writeFile(stateFilePath, JSON.stringify(session, null, 2), 'utf8');

    // Set file permissions to 0600 (owner read/write only)
    await fs.chmod(stateFilePath, 0o600);

    console.log(`Browser session updated: ${stateFilePath}`);

    res.json({
      success: true,
      message: 'Browser session uploaded successfully. Monitor will reload automatically.',
      path: stateFilePath,
      timestamp: new Date().toISOString(),
    });
  } catch (error) {
    await recordError('server.admin.upload_browser_session', error);
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

/**
 * GET /v1/admin/browser-session/status
 * Check if browser session file exists and when it was last modified
 */
router.get('/browser-session/status', async (req, res) => {
  try {
    const stateFilePath = process.env.NOGI_BROWSER_STATE_FILE || '/data/nogi-browser-state.json';

    try {
      const stats = await fs.stat(stateFilePath);
      res.json({
        success: true,
        exists: true,
        path: stateFilePath,
        size: stats.size,
        lastModified: stats.mtime.toISOString(),
        lastAccessed: stats.atime.toISOString(),
      });
    } catch (error) {
      if (error.code === 'ENOENT') {
        res.json({
          success: true,
          exists: false,
          path: stateFilePath,
          message: 'Browser session file not found',
        });
      } else {
        throw error;
      }
    }
  } catch (error) {
    await recordError('server.admin.check_browser_session', error);
    res.status(500).json({
      success: false,
      error: error.message,
    });
  }
});

export default router;
