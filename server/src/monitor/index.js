import dotenv from 'dotenv';
import { recordError } from '../services/error-log.js';

dotenv.config();

const monitor = (await import('./nogi-browser.js')).default;
const blogMonitor = (await import('./blog-monitor.js')).default;
const mediaServer = (await import('./media-server.js')).default;

await mediaServer.start();

monitor.start().catch(error => {
  void recordError('monitor.start', error);
  process.exitCode = 1;
});
blogMonitor.start().catch(error => {
  void recordError('blog_monitor.start', error);
  process.exitCode = 1;
});

process.on('uncaughtException', error => {
  void recordError('monitor.uncaught_exception', error).finally(() => process.exit(1));
});
process.on('unhandledRejection', reason => {
  void recordError('monitor.unhandled_rejection', reason instanceof Error ? reason : new Error(String(reason)));
});

const shutdown = async () => {
  await blogMonitor.stop();
  await monitor.stop();
  await mediaServer.stop();
  process.exit(0);
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
