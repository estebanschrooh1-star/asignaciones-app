# Asignaciones v1.8.2

Un solo código para dos apps: **Android** (APK) e **iPhone/iPad** (PWA). La pantalla es la misma
(`app/src/main/assets/index.html`); GitHub Actions genera las dos versiones en cada push.

## Android
Igual que antes: en la pestaña **Actions** → última ejecución → artefacto **Asignaciones-APK**.
> Mantené en `app/` tu archivo `asignaciones.keystore` (no viene en este zip). Si lo cambiás, Android
> no te dejará actualizar encima de la app instalada.

## iPhone / iPad (PWA)
1. Una sola vez: en GitHub → **Settings → Pages → Build and deployment → Source: GitHub Actions**.
2. Hacé un push (o **Actions → Compilar APK y PWA → Run workflow**).
3. La dirección queda en `https://TU-USUARIO.github.io/TU-REPOSITORIO/`.
4. En el iPhone, abrila en **Safari** → Compartir → **Agregar a inicio**, y usá siempre ese ícono.

Si no querés usar Pages, el artefacto **Asignaciones-iOS-PWA** trae la carpeta lista para alojar en cualquier hosting con https.

## Diferencias en iPhone
- Sin pestaña **Avisos** (recordatorios y aviso de cambiar planilla): iOS no permite notificaciones programadas en una PWA.
- Sin copia automática en Google Drive ni recibir PDF compartido desde otra app. Sí funcionan Exportar/Importar,
  la planilla PDF (se sube desde Configuración), el historial, el bloqueo con patrón y el modo oscuro.
- Los respaldos `.txt` son compatibles entre Android e iPhone.

## Archivos nuevos
- `pwa/` manifiesto, service worker (uso sin conexión) e íconos.
- `scripts/build-pwa.sh` arma la versión web; `scripts/get-pdfjs.sh` descarga el visor de PDF (pdf.js 3.11.174).
