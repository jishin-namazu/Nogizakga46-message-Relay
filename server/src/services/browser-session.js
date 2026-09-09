import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

export function browserSessionPaths(stateFilePath) {
  return {
    stateFilePath,
    uploadStatusFilePath: `${stateFilePath}.upload-status.json`,
    activationStatusFilePath: `${stateFilePath}.activation-status.json`,
  };
}

export function sessionVersion(serializedSession) {
  return createHash('sha256').update(serializedSession).digest('hex');
}

export async function atomicWritePrivateFile(filePath, content) {
  const directory = path.dirname(filePath);
  await fs.mkdir(directory, { recursive: true });
  const tempFile = `${filePath}.tmp-${process.pid}-${randomUUID()}`;
  try {
    await fs.writeFile(tempFile, content, { encoding: 'utf8', mode: 0o600 });
    await fs.chmod(tempFile, 0o600);
    await fs.rename(tempFile, filePath);
  } catch (error) {
    await fs.unlink(tempFile).catch(() => {});
    throw error;
  }
}

export async function atomicWritePrivateJson(filePath, value) {
  await atomicWritePrivateFile(filePath, JSON.stringify(value, null, 2));
}

export async function readJsonIfExists(filePath) {
  try {
    return JSON.parse(await fs.readFile(filePath, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

export async function readBrowserSession(stateFilePath) {
  const serialized = await fs.readFile(stateFilePath, 'utf8');
  const state = JSON.parse(serialized);
  if (!state || typeof state !== 'object') throw new Error('invalid browser storage state');
  return {
    state,
    version: sessionVersion(serialized),
  };
}
