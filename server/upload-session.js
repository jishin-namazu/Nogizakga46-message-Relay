#!/usr/bin/env node

/**
 * Upload browser session to Nogi Relay server
 * Usage: node upload-session.js <session-file> <server-url> <access-token>
 * Example: node upload-session.js ./nogi-browser-state.json https://nogi-relay.fly.dev YOUR_TOKEN
 */

import fs from 'fs/promises';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function fetchSessionStatus(serverUrl, accessToken) {
  const response = await fetch(`${serverUrl}/v1/admin/browser-session/status`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${accessToken}`,
    },
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || `Status request failed (${response.status})`);
  return result;
}

async function waitForActivation(serverUrl, accessToken, requestId, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const status = await fetchSessionStatus(serverUrl, accessToken);
    if (status.requestId === requestId) {
      if (status.activationStatus === 'active' && status.activated) return status;
      if (status.activationStatus === 'failed') {
        throw new Error(status.activationError || 'Monitor rejected the uploaded session');
      }
    }
    await sleep(2_000);
  }
  throw new Error('Timed out waiting for the monitor to activate the uploaded session');
}

async function uploadSession(sessionFilePath, serverUrl, accessToken) {
  try {
    // Read session file
    const sessionData = await fs.readFile(sessionFilePath, 'utf8');
    const session = JSON.parse(sessionData);

    console.log(`Reading session from: ${sessionFilePath}`);
    console.log(`Uploading to: ${serverUrl}/v1/admin/browser-session`);

    // Upload to server
    const response = await fetch(`${serverUrl}/v1/admin/browser-session`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${accessToken}`,
      },
      body: JSON.stringify({ session }),
    });

    const result = await response.json();

    if (response.ok) {
      console.log('✓ Session upload accepted');
      console.log(`  Path: ${result.path}`);
      console.log(`  Timestamp: ${result.timestamp}`);
      console.log(`  Request ID: ${result.requestId}`);
      if (!result.activated) {
        console.log('Waiting for the monitor to activate and verify the session...');
        const status = await waitForActivation(serverUrl, accessToken, result.requestId);
        console.log(`✓ Session activated at ${status.activationUpdatedAt}`);
      } else {
        console.log('✓ Session is already active');
      }
      return true;
    } else {
      console.error('✗ Upload failed:', result.error);
      return false;
    }
  } catch (error) {
    console.error('✗ Error:', error.message);
    return false;
  }
}

async function checkSessionStatus(serverUrl, accessToken) {
  try {
    console.log(`Checking session status: ${serverUrl}/v1/admin/browser-session/status`);

    const result = await fetchSessionStatus(serverUrl, accessToken);

    if (result.exists) {
      console.log('✓ Session file exists');
      console.log(`  Path: ${result.path}`);
      console.log(`  Size: ${result.size} bytes`);
      console.log(`  Last modified: ${result.lastModified}`);
      console.log(`  Activation: ${result.activationStatus}`);
      if (result.activationUpdatedAt) console.log(`  Activation updated: ${result.activationUpdatedAt}`);
      if (result.activationError) console.log(`  Activation error: ${result.activationError}`);
    } else {
      console.log('✗ Session file not found');
      console.log(`  Expected path: ${result.path}`);
    }
    return true;
  } catch (error) {
    console.error('✗ Error:', error.message);
    return false;
  }
}

// Main
const args = process.argv.slice(2);

if (args.length === 0 || args[0] === '--help' || args[0] === '-h') {
  console.log(`
Nogi Relay Browser Session Uploader

Usage:
  node upload-session.js <session-file> <server-url> <access-token>
  node upload-session.js --status <server-url> <access-token>

Examples:
  # Upload session file
  node upload-session.js ./nogi-browser-state.json https://nogi-relay.fly.dev YOUR_TOKEN

  # Check current session status
  node upload-session.js --status https://nogi-relay.fly.dev YOUR_TOKEN

Arguments:
  session-file   Path to nogi-browser-state.json file
  server-url     Server base URL (without trailing slash)
  access-token   ACCESS_TOKEN for authentication
`);
  process.exit(0);
}

if (args[0] === '--status') {
  if (args.length < 3) {
    console.error('Error: --status requires <server-url> and <access-token>');
    process.exit(1);
  }
  const [, serverUrl, accessToken] = args;
  const success = await checkSessionStatus(serverUrl, accessToken);
  process.exit(success ? 0 : 1);
} else {
  if (args.length < 3) {
    console.error('Error: requires <session-file>, <server-url>, and <access-token>');
    console.error('Run with --help for usage information');
    process.exit(1);
  }

  const [sessionFilePath, serverUrl, accessToken] = args;
  const success = await uploadSession(sessionFilePath, serverUrl, accessToken);
  process.exit(success ? 0 : 1);
}
