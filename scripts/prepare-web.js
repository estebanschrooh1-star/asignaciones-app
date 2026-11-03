// Copia pdf.js (lector de PDF) dentro de www/vendor para que la app funcione sin internet.
const fs = require("fs"), path = require("path");
const src = path.join(__dirname, "..", "node_modules", "pdfjs-dist", "build");
const dst = path.join(__dirname, "..", "www", "vendor");
fs.mkdirSync(dst, { recursive: true });
for (const f of ["pdf.min.js", "pdf.worker.min.js"]) {
  fs.copyFileSync(path.join(src, f), path.join(dst, f));
  console.log("copiado", f);
}
