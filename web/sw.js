// 가족 캠 서비스 워커
const CACHE = 'family-cam-v19';
const ASSETS = [
  './', './index.html', './manifest.webmanifest',
  './icon-192.png', './icon-512.png', './icon-maskable.png', './apple-touch-icon.png', './bg-default.jpg',
  'https://unpkg.com/peerjs@1.5.4/dist/peerjs.min.js'
];

// 파일 하나가 실패해도 설치가 멈추지 않도록 하나씩 저장
self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => Promise.all(ASSETS.map((u) => c.add(u).catch(() => {}))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// 네트워크 우선, 실패하면 저장본
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  // 화면(HTML)은 브라우저 캐시를 거치지 않고 항상 최신으로 받음 (GitHub Pages 10분 캐시 회피)
  const req = (e.request.mode === 'navigate') ? new Request(e.request, { cache: 'no-store' }) : e.request;
  e.respondWith(
    fetch(req)
      .then((res) => {
        if (res.ok && (e.request.url.startsWith(self.location.origin) || e.request.url.includes('peerjs'))) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy));
        }
        return res;
      })
      .catch(() => caches.match(e.request).then((r) => r || caches.match('./index.html')))
  );
});
