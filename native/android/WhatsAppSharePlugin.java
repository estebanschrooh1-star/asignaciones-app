package __PACKAGE__;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;

/**
 * Comparte una imagen (y un texto opcional) directamente con WhatsApp.
 * Primero prueba con WhatsApp normal y, si no está, con WhatsApp Business.
 */
@CapacitorPlugin(name = "WhatsAppShare")
public class WhatsAppSharePlugin extends Plugin {

    @PluginMethod
    public void share(PluginCall call) {
        String uriStr = call.getString("uri");
        String text = call.getString("text", "");
        if (uriStr == null || uriStr.isEmpty()) {
            call.reject("missing_uri");
            return;
        }
        try {
            File file = new File(Uri.parse(uriStr).getPath());
            Uri content = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                file
            );

            String[] packages = new String[] { "com.whatsapp", "com.whatsapp.w4b" };
            for (String pkg : packages) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/jpeg");
                intent.putExtra(Intent.EXTRA_STREAM, content);
                if (text != null && !text.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_TEXT, text);
                }
                intent.setPackage(pkg);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    getContext().startActivity(intent);
                    call.resolve();
                    return;
                } catch (ActivityNotFoundException e) {
                    // probar con el siguiente paquete
                }
            }
            call.reject("not_installed");
        } catch (Exception e) {
            call.reject("error: " + e.getMessage());
        }
    }
}
