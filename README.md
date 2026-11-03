# Asignaciones

Misma app, dos formas de usarla:

- **Android:** GitHub compila un APK automáticamente.
- **iPhone:** se publica como página web y se instala como app desde Safari (PWA).

## Subir el proyecto a GitHub

1. Crea un repositorio **público** en https://github.com/new (por ejemplo `asignaciones`).
   - Público porque GitHub Pages gratis (la versión web) solo funciona con repos públicos.
2. Sube **todo el contenido** de esta carpeta a la rama `main`.
   - Con Git: `git init && git add . && git commit -m "Primera versión" && git branch -M main && git remote add origin https://github.com/TU_USUARIO/asignaciones.git && git push -u origin main`
   - O desde la web: *Add file → Upload files* y arrastra todo. La carpeta `.github` empieza con punto y puede estar oculta: en Windows activa «Elementos ocultos», en Mac usa `Cmd + Shift + .`. Sin ella no se compila nada.
3. Activa la versión web una sola vez: **Settings → Pages → Source: GitHub Actions**.
4. Entra a la pestaña **Actions**. Verás dos procesos corriendo; tardan unos minutos.

## Descargar el APK (Android)

1. **Actions → Compilar Android (APK)** → entra al último proceso en verde.
2. Abajo, en **Artifacts**, descarga **Asignaciones-APK** (viene dentro de un .zip; descomprímelo).
3. Pasa `app-debug.apk` al teléfono y ábrelo (Android pedirá permitir instalar apps desconocidas).

Cada vez que subas cambios al repositorio, se genera un APK nuevo. También puedes lanzarlo a mano con **Run workflow**.

## Instalar en iPhone

1. Cuando termine **Publicar versión web**, tu link será: `https://TU_USUARIO.github.io/NOMBRE_DEL_REPO/`
   (también aparece en Actions, en el paso *deployment*).
2. Ábrelo en **Safari** (tiene que ser Safari).
3. Botón **Compartir → Añadir a pantalla de inicio**.
4. Se instala con el logo y se abre a pantalla completa como una app. Funciona sin internet después de la primera carga.

Los datos (textos, PDF) se guardan en cada dispositivo; la versión de iPhone y la de Android no se sincronizan.

## Plantilla de asignación (imagen)

La imagen de la plantilla **no está en el repositorio** a propósito. Al abrir la app por primera vez en cada dispositivo (iPhone o Android), súbela en **Configuración (⚙) → Subir imagen**. Se guarda solo en ese dispositivo.

- Debe ser la misma imagen original (631 × 873 px o con las mismas proporciones), porque los textos se colocan en posiciones fijas.
- Si instalas la app de nuevo o borras los datos del navegador, hay que subirla otra vez.
- Nunca subas esa imagen al repositorio.

## Modificar la app

Todo el código está en `www/index.html`. Si cambias archivos, sube el número `VERSION` en `www/sw.js` (por ejemplo `asig-v2`) para que los iPhones ya instalados se actualicen.

## Logo

Archivos fuente en `brand/` (`logo.svg`, `logo-1024.png`). Los íconos de Android están en `resources/android-res/` y los de la web en `www/icons/`.

## Funciones solo de Android (v2.7)

- **Recordatorio de turnos**: usa `@capacitor/local-notifications` (ya está en `package.json`).
- **WhatsApp directo**: plugin propio en `native/android/WhatsAppSharePlugin.java`.
  El workflow de Android ejecuta `scripts/android-native.js` después de `npx cap add android`,
  que copia el plugin y lo registra en `MainActivity`.
- La versión web (iPhone / PWA) usa los mismos archivos de `www/`: esas funciones se ocultan solas fuera de Android.

## Recibir PDF desde otras apps (v2.8)

- **Android (APK):** en cualquier app, al compartir un PDF aparece **Asignaciones**. Se carga como planilla
  (reemplaza la anterior). Plugin: `native/android/PdfSharePlugin.java`; el filtro de compartir se agrega
  al manifiesto con `scripts/android-native.js`.
- **Web instalada (Chrome/Edge en Android o PC):** funciona con `share_target` del `manifest.webmanifest` y `www/sw.js`.
- **iPhone:** Safari no permite que una web instalada aparezca en el menú Compartir. Ahí se sigue subiendo el PDF
  desde Configuración. Para tenerlo en iPhone haría falta una app iOS nativa.

## Firma fija del APK

`resources/signing/debug.keystore` se copia a `~/.android/` antes de compilar, así todos los APK
quedan firmados con la misma clave y Android los instala como actualización (sin desinstalar).
