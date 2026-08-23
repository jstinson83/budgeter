// Home OS service worker.
//
// This app is a server-rendered, session-authenticated app - every page
// carries per-user financial data, so navigations are always network-first
// with no HTML caching. The service worker's job is narrower: make the app
// installable, speed up repeat loads of the unchanging shell assets
// (styles, icons), and show something other than the browser's default
// offline error when a navigation fails with no connection.
//
// Bump CACHE_NAME on any change to PRECACHE_URLS or their contents - the
// activate handler drops any cache whose name doesn't match, which is the
// only invalidation mechanism here.
const CACHE_NAME = "home-os-v2";
const OFFLINE_URL = "/offline.html";
const PRECACHE_URLS = [
  "/styles.css",
  "/favicon.svg",
  "/favicon-32x32.png",
  "/apple-touch-icon.png",
  "/icon-192.png",
  "/icon-512.png",
  "/manifest.webmanifest",
  OFFLINE_URL,
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then((cache) => cache.addAll(PRECACHE_URLS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)))
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Page navigations: always go to the network so signed-in users see
  // current data, falling back to the offline page only when the network
  // request itself fails outright.
  if (request.mode === "navigate") {
    event.respondWith(fetch(request).catch(() => caches.match(OFFLINE_URL)));
    return;
  }

  // Everything else we'd bother caching is a fixed shell asset - serve it
  // from cache first and only hit the network on a cache miss.
  if (PRECACHE_URLS.includes(url.pathname)) {
    event.respondWith(caches.match(request).then((cached) => cached || fetch(request)));
  }
});
