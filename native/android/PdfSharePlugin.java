package __PACKAGE__;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Recibe un PDF compartido desde otra app (menú "Compartir" de Android).
 * - getPending(): devuelve {name, data(base64)} si hay un PDF esperando, o {} si no.
 * - Evento "pdfShared": se dispara cuando llega un PDF con la app ya abierta.
 */
@CapacitorPlugin(name = "PdfShare")
public class PdfSharePlugin extends Plugin {

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (sharedUri(intent) != null) {
            getActivity().setIntent(intent);
            notifyListeners("pdfShared", new JSObject(), true);
        }
    }

    @SuppressWarnings("deprecation")
    private Uri sharedUri(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return null;
        String type = i.getType();
        if (type != null && !type.toLowerCase().contains("pdf") && !type.equals("application/octet-stream")) return null;
        Uri u;
        if (Build.VERSION.SDK_INT >= 33) u = i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        else u = (Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null) {
            ClipData cd = i.getClipData();
            if (cd != null && cd.getItemCount() > 0) u = cd.getItemAt(0).getUri();
        }
        return u;
    }

    private String nameOf(Uri u) {
        try (Cursor c = getContext().getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) {
                    String n = c.getString(ix);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception e) {
            // se usa el nombre por defecto
        }
        String last = u.getLastPathSegment();
        return (last != null && !last.isEmpty()) ? last : "planilla.pdf";
    }

    @PluginMethod
    public void getPending(PluginCall call) {
        JSObject out = new JSObject();
        Intent i = getActivity().getIntent();
        Uri u = sharedUri(i);
        if (u == null) {
            call.resolve(out);
            return;
        }
        // Marcar como consumido para no importarlo dos veces
        i.setAction(Intent.ACTION_MAIN);
        i.removeExtra(Intent.EXTRA_STREAM);
        try {
            ContentResolver cr = getContext().getContentResolver();
            try (InputStream in = cr.openInputStream(u); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                if (in == null) {
                    call.reject("no_stream");
                    return;
                }
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                out.put("data", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
            }
            out.put("name", nameOf(u));
            call.resolve(out);
        } catch (Exception e) {
            call.reject("error: " + e.getMessage());
        }
    }
}
