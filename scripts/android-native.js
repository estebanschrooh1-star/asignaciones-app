// Agrega al proyecto Android (generado por "npx cap add android") los plugins propios
// (WhatsApp directo y recepción de PDF compartido), los registra en MainActivity
// y declara en el manifiesto que la app acepta PDF desde el menú "Compartir".
// Los recordatorios usan @capacitor/local-notifications, que ya trae sus permisos.
const fs = require("fs"), path = require("path");

const root = path.join(__dirname, "..");
const javaRoot = path.join(root, "android", "app", "src", "main", "java");

function find(dir, name) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { const r = find(p, name); if (r) return r; }
    else if (e.name === name) return p;
  }
  return null;
}

const mainPath = find(javaRoot, "MainActivity.java");
if (!mainPath) { console.error("No encontré MainActivity.java. ¿Se creó el proyecto Android?"); process.exit(1); }

const pkg = /package\s+([\w.]+);/.exec(fs.readFileSync(mainPath, "utf8"))[1];
const dir = path.dirname(mainPath);

// 1) Plugin
for (const f of ["WhatsAppSharePlugin.java", "PdfSharePlugin.java"]) {
  const tpl = fs.readFileSync(path.join(root, "native", "android", f), "utf8");
  fs.writeFileSync(path.join(dir, f), tpl.replace("__PACKAGE__", pkg));
}

// 2) Registro del plugin en MainActivity
fs.writeFileSync(mainPath, `package ${pkg};

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WhatsAppSharePlugin.class);
        registerPlugin(PdfSharePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);

// 3) El proveedor de archivos debe poder compartir la carpeta de caché
const fp = path.join(root, "android", "app", "src", "main", "res", "xml", "file_paths.xml");
fs.mkdirSync(path.dirname(fp), { recursive: true });
fs.writeFileSync(fp, `<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-path name="my_images" path="." />
    <cache-path name="my_cache_images" path="." />
</paths>
`);

// 4) Manifiesto: aceptar PDF compartidos desde otras apps
const mf = path.join(root, "android", "app", "src", "main", "AndroidManifest.xml");
let xml = fs.readFileSync(mf, "utf8");
if (!xml.includes("android.intent.action.SEND")) {
  const filter = `
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/pdf" />
            </intent-filter>
        `;
  if (!xml.includes("</activity>")) { console.error("No encontré <activity> en AndroidManifest.xml"); process.exit(1); }
  xml = xml.replace("</activity>", filter + "</activity>");
}
if (!/android:launchMode=/.test(xml)) xml = xml.replace(/(<activity\b)/, '$1 android:launchMode="singleTask"');
fs.writeFileSync(mf, xml);

console.log("Funciones nativas agregadas en el paquete", pkg);
