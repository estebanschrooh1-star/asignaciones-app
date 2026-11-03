// Service worker: permite usar la app sin conexión una vez instalada.
// Si cambias archivos de la app, sube el número de VERSION para forzar la actualización.
const VERSION = "asig-v11";
const CORE = ["./", "index.html", "manifest.webmanifest", "icons/icon-192.png", "icons/icon-512.png",
  "icons/apple-touch-icon.png", "icons/favicon.png", "vendor/pdf.min.js", "vendor/pdf.worker.min.js"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(VERSION).then(c => Promise.allSettled(CORE.map(u => c.add(u)))).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(k => Promise.all(k.filter(n => n !== VERSION).map(n => caches.delete(n)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const r = e.request;
  // PDF compartido desde otra app (Web Share Target): se guarda y se abre la app
  if (r.method === "POST" && new URL(r.url).pathname.endsWith("/share-target")) {
    e.respondWith((async () => {
      try {
        const f = (await r.formData()).get("pdf");
        if (f && f.size) {
          const c = await caches.open("share-inbox");
          await c.put("shared-pdf", new Response(f, { headers: { "X-Name": encodeURIComponent(f.name || "planilla.pdf") } }));
        }
      } catch (err) {}
      return Response.redirect(new URL("./?shared=1", self.registration.scope).href, 303);
    })());
    return;
  }
  if (r.method !== "GET" || new URL(r.url).origin !== location.origin) return;
  // Red primero (para recibir actualizaciones), caché si no hay conexión
  e.respondWith(fetch(r).then(res => {
    const copy = res.clone(); caches.open(VERSION).then(c => c.put(r, copy)); return res;
  }).catch(() => caches.match(r, { ignoreSearch: true }).then(m => m || caches.match("index.html"))));
});
