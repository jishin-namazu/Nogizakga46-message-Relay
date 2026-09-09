import express from 'express';
import fs from 'fs/promises';
import { randomUUID } from 'node:crypto';
import { recordError } from '../services/error-log.js';
import {
  atomicWritePrivateFile,
  atomicWritePrivateJson,
  browserSessionPaths,
  readJsonIfExists,
  sessionVersion,
} from '../services/browser-session.js';

const router = express.Router();
let sessionWriteQueue = Promise.resolve();

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
    const { uploadStatusFilePath, activationStatusFilePath } = browserSessionPaths(stateFilePath);
    const serializedSession = JSON.stringify(session, null, 2);
    const version = sessionVersion(serializedSession);
    const requestId = randomUUID();
    const uploadedAt = new Date().toISOString();

    const writeSession = async () => {
      await atomicWritePrivateFile(stateFilePath, serializedSession);
      await atomicWritePrivateJson(uploadStatusFilePath, { requestId, version, uploadedAt });
    };
    sessionWriteQueue = sessionWriteQueue.then(writeSession, writeSession);
    await sessionWriteQueue;

    const activation = await readJsonIfExists(activationStatusFilePath).catch(() => null);
    const activated = activation?.requestId === requestId && activation?.status === 'active';

    console.log(`Browser session updated: ${stateFilePath}`);

    res.status(activated ? 200 : 202).json({
      success: true,
      accepted: true,
      activated,
      activationStatus: activated ? 'active' : 'pending',
      requestId,
      version,
      message: activated
        ? 'Browser session is already active.'
        : 'Browser session uploaded. Monitor activation is pending.',
      path: stateFilePath,
      timestamp: uploadedAt,
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
    const { uploadStatusFilePath, activationStatusFilePath } = browserSessionPaths(stateFilePath);

    try {
      const [stats, upload, activation] = await Promise.all([
        fs.stat(stateFilePath),
        readJsonIfExists(uploadStatusFilePath),
        readJsonIfExists(activationStatusFilePath),
      ]);
      const activated = Boolean(
        upload?.requestId
        && activation?.requestId === upload.requestId
        && activation?.status === 'active',
      );
      res.json({
        success: true,
        exists: true,
        path: stateFilePath,
        size: stats.size,
        lastModified: stats.mtime.toISOString(),
        lastAccessed: stats.atime.toISOString(),
        uploadedVersion: upload?.version || null,
        uploadedAt: upload?.uploadedAt || null,
        activeVersion: activation?.status === 'active' ? activation.version : null,
        activeRequestId: activation?.status === 'active' ? activation.requestId || null : null,
        requestId: upload?.requestId || null,
        activated,
        activationStatus: !upload?.requestId
          ? 'unknown'
          : activation?.requestId === upload.requestId
            ? activation.status
            : 'pending',
        activationUpdatedAt: activation?.updatedAt || null,
        activationError: activation?.requestId === upload?.requestId && activation?.status === 'failed'
          ? activation.error || null
          : null,
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
