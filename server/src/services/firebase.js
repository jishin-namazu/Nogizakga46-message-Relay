import admin from 'firebase-admin';
import { readFileSync } from 'fs';
import dotenv from 'dotenv';
import mediaArchive from './media.js';
import { recordError } from './error-log.js';

dotenv.config();

let firebaseApp = null;

export function pushPayload(message) {
  const mediaUrl = message.media_local_path
    ? mediaArchive.publicUrl(message.id, 'media')
    : message.media_url;
  const thumbnailUrl = message.thumbnail_local_path
    ? mediaArchive.publicUrl(message.id, 'thumbnail')
    : message.thumbnail_url;
  const phoneImageUrl = message.phone_image_local_path
    ? mediaArchive.publicUrl(message.id, 'phone_image')
    : message.phone_image_url;

  return {
    id: message.id,
    member_id: message.member_id,
    member_name: message.member_name,
    member_avatar_url: message.member_avatar_url,
    phone_image_url: phoneImageUrl,
    type: message.type,
    text: message.text,
    media_url: mediaUrl,
    thumbnail_url: thumbnailUrl,
    duration_seconds: message.duration_seconds,
    sent_at: message.sent_at,
    incoming_call_from: message.incoming_call_from,
    ringtone_url: message.ringtone_url,
    is_played: message.is_played,
  };
}

// FCM caps the entire data map at 4096 bytes. Measure encoded UTF-8 bytes,
// never JavaScript string length (UTF-16 code units), and keep a margin so an
// oversized message degrades to message_id/type instead of failing the whole
// multicast. The client then falls back to GET /v1/messages/:id.
const FCM_DATA_LIMIT_BYTES = 4096;
const FCM_DATA_SAFETY_MARGIN_BYTES = 256;

export function buildDataPayload(message, includePayload) {
  const data = {
    message_id: message.id,
    type: message.type,
  };

  if (!includePayload) return data;

  const payload = JSON.stringify(pushPayload(message));
  const candidate = { ...data, payload };
  const encodedBytes = Buffer.byteLength(JSON.stringify(candidate), 'utf8');
  if (encodedBytes <= FCM_DATA_LIMIT_BYTES - FCM_DATA_SAFETY_MARGIN_BYTES) {
    return candidate;
  }

  console.log(
    `FCM inline payload omitted for message ${message.id}: `
    + `${Buffer.byteLength(payload, 'utf8')} UTF-8 bytes exceeds the safe data budget`,
  );
  return data;
}

/**
 * 初始化 Firebase Admin SDK
 */
export function initializeFirebase() {
  if (firebaseApp) {
    return firebaseApp;
  }

  try {
    let serviceAccount;

    // 支持 Base64 编码的密钥（用于 Fly.io 等平台）
    if (process.env.FIREBASE_PRIVATE_KEY_BASE64) {
      console.log('Using Firebase key from FIREBASE_PRIVATE_KEY_BASE64 env variable');
      const base64Key = process.env.FIREBASE_PRIVATE_KEY_BASE64;
      const jsonKey = Buffer.from(base64Key, 'base64').toString('utf8');
      serviceAccount = JSON.parse(jsonKey);
    } else if (process.env.FIREBASE_PRIVATE_KEY_JSON) {
      // 支持直接 JSON 字符串（另一种方式）
      console.log('Using Firebase key from FIREBASE_PRIVATE_KEY_JSON env variable');
      serviceAccount = JSON.parse(process.env.FIREBASE_PRIVATE_KEY_JSON);
    } else {
      // 从文件读取（本地开发）
      console.log('Using Firebase key from file:', process.env.FIREBASE_PRIVATE_KEY_PATH);
      serviceAccount = JSON.parse(
        readFileSync(process.env.FIREBASE_PRIVATE_KEY_PATH, 'utf8')
      );
    }

    firebaseApp = admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      projectId: process.env.FIREBASE_PROJECT_ID,
    });

    console.log('Firebase Admin SDK initialized');
    return firebaseApp;
  } catch (error) {
    void recordError('firebase.initialize', error);
    throw error;
  }
}

/**
 * 批量发送推送
 * @param {Array<string>} tokens - FCM tokens 数组
 * @param {object} message - 消息对象
 */
export async function sendMulticastPush(tokens, message) {
  if (!firebaseApp) {
    initializeFirebase();
  }

  if (!tokens || tokens.length === 0) {
    return { success: false, error: 'No tokens provided' };
  }

  const data = buildDataPayload(message, true);

  const multicastMessage = {
    data,
    android: {
      priority: 'high',
    },
    tokens,
  };

  try {
    const response = await admin.messaging().sendEachForMulticast(multicastMessage);
    console.log(`FCM multicast: ${response.successCount} success, ${response.failureCount} failed`);
    return {
      success: true,
      successCount: response.successCount,
      failureCount: response.failureCount,
      responses: response.responses,
    };
  } catch (error) {
    await recordError('firebase.multicast', error, { message_id: message.id, token_count: tokens.length });
    return { success: false, error: error.message };
  }
}

export default {
  initializeFirebase,
  sendMulticastPush,
};
