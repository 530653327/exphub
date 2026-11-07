/* 经验阁后台 PWA · Service Worker
 *
 * 设计原则（重要，别为了「能离线」把后台数据缓存了）：
 *   1. 只缓存**壳**：图标、manifest、离线提示页。不缓存任何 HTML 页面、不缓存 /api/**。
 *      经验正文和统计随时在变，缓存了就会给人看旧结论 —— 在知识库里这比打不开更糟。
 *   2. 页面与 API 一律走网络；失败时页面回离线提示，API 直接失败（让前端自己显示错误）。
 *   3. 版本号变了才清旧缓存；不要用 skipWaiting 抢更新，避免用户正写着东西被刷新。
 */
const VERSION = 'exphub-shell-v1';
const SCOPE = self.registration.scope;   // 形如 https://cloudim.club/exphub/

const SHELL = [
  SCOPE + 'manifest.json',
  SCOPE + 'images/pwa/icon-192.png',
  SCOPE + 'images/pwa/icon-512.png',
  SCOPE + 'images/pwa/apple-touch-icon.png',
  SCOPE + 'offline.html'
];

self.addEventListener('install', (e) => {
  // 单个文件失败不能让整个安装失败，否则以后加一个图标就把 PWA 装坏了
  e.waitUntil(
    caches.open(VERSION).then((c) =>
      Promise.all(SHELL.map((u) => c.add(u).catch(() => null)))
    )
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  // 只处理自己作用域内的请求
  if (!url.pathname.startsWith(new URL(SCOPE).pathname)) return;

  // API 与页面：永不缓存，直接放行给网络
  const isApi = url.pathname.includes('/api/');
  const isPage = req.mode === 'navigate';
  if (isApi || isPage) {
    if (isPage) {
      e.respondWith(fetch(req).catch(() => caches.match(SCOPE + 'offline.html')));
    }
    return;
  }

  // 静态壳：缓存优先，后台顺手更新
  e.respondWith(
    caches.match(req).then((hit) => {
      const net = fetch(req).then((res) => {
        if (res && res.ok) {
          const copy = res.clone();
          caches.open(VERSION).then((c) => c.put(req, copy)).catch(() => null);
        }
        return res;
      }).catch(() => hit);
      return hit || net;
    })
  );
});
