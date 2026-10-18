package com.musicplayer.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Lets the web page pick a folder, list the songs in it, read parts of them and play them. */
@CapacitorPlugin(name = "FolderPicker")
public class FolderPickerPlugin extends Plugin {

    @PluginMethod
    public void pick(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "pickResult");
    }

    @ActivityCallback
    private void pickResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("cancelled");
            return;
        }
        Uri tree = data.getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // the folder still works for this session
        }
        respond(call, tree);
    }

    @PluginMethod
    public void scan(PluginCall call) {
        String s = call.getString("uri");
        if (s == null) {
            call.reject("no uri");
            return;
        }
        respond(call, Uri.parse(s));
    }

    private void respond(final PluginCall call, final Uri tree) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSArray files = new JSArray();
                    walk(tree, DocumentsContract.getTreeDocumentId(tree), "", files);
                    JSObject out = new JSObject();
                    out.put("uri", tree.toString());
                    out.put("files", files);
                    call.resolve(out);
                } catch (Exception e) {
                    call.reject("scan failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void walk(Uri tree, String parentId, String rel, JSArray out) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        Cursor c = getContext().getContentResolver().query(children, cols, null, null, null);
        if (c == null) {
            return;
        }
        try {
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.getLong(3);
                if (name == null) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(tree, id, rel + name + "/", out);
                } else {
                    String low = name.toLowerCase(Locale.ROOT);
                    boolean audio = (mime != null && mime.startsWith("audio/"))
                            || low.matches(".*\\.(mp3|m4a|flac|ogg|opus|wav|aac|webm)$");
                    if (audio || low.endsWith(".lrc")) {
                        JSObject o = new JSObject();
                        o.put("name", name);
                        o.put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                        o.put("size", size);
                        o.put("rel", rel + name);
                        out.put(o);
                    }
                }
            }
        } finally {
            c.close();
        }
    }

    @PluginMethod
    public void read(PluginCall call) {
        String s = call.getString("uri");
        Integer offsetObj = call.getInt("offset", 0);
        Integer lengthObj = call.getInt("length", 0);
        int offset = offsetObj == null ? 0 : offsetObj;
        int length = lengthObj == null ? 0 : lengthObj;
        JSObject out = new JSObject();
        if (s == null || length <= 0) {
            out.put("data", "");
            call.resolve(out);
            return;
        }
        InputStream in = null;
        try {
            in = getContext().getContentResolver().openInputStream(Uri.parse(s));
            long skipped = 0;
            while (skipped < offset) {
                long n = in.skip(offset - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            byte[] buf = new byte[length];
            int got = 0;
            while (got < length) {
                int n = in.read(buf, got, length - got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            out.put("data", Base64.encodeToString(buf, 0, got, Base64.NO_WRAP));
            call.resolve(out);
        } catch (Exception e) {
            call.reject("read failed: " + e.getMessage());
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    @PluginMethod
    public void cache(PluginCall call) {
        String s = call.getString("uri");
        String name = call.getString("name", "track");
        if (s == null) {
            call.reject("no uri");
            return;
        }
        InputStream in = null;
        OutputStream os = null;
        try {
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".mp3";
            File f = new File(getContext().getCacheDir(), "play_" + System.nanoTime() + ext);
            in = getContext().getContentResolver().openInputStream(Uri.parse(s));
            os = new FileOutputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
            JSObject r = new JSObject();
            r.put("path", f.getAbsolutePath());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("copy failed: " + e.getMessage());
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (os != null) {
                    os.close();
                }
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    @PluginMethod
    public void drop(PluginCall call) {
        String p = call.getString("path");
        if (p != null && p.startsWith(getContext().getCacheDir().getAbsolutePath())) {
            new File(p).delete();
        }
        call.resolve();
    }
}
