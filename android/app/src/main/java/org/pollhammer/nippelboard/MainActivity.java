package org.pollhammer.nippelboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String INDEX = "https://" + HOST + "/index.html";
    private static final String HOMEPAGE = "https://www.pollhammer.org/nippel";
    private static final String AUTHORITY = "org.pollhammer.nippelboard.clips";
    private static final int SAVE_CLIP = 10;
    private static final int PICK_SOUNDS = 11;
    private static final int MAX_BASE64_LENGTH = 4 * ((10 * 1024 * 1024 + 2) / 3);

    private WebView board;
    private File pendingSave;
    private ValueCallback<Uri[]> pendingSoundFiles;

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (savedState != null) {
            String token = savedState.getString("saveToken");
            String name = savedState.getString("saveName");
            if (isClipPath(token, name)) pendingSave = clipFile(getCacheDir(), token, name);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(17, 26, 36));
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        board = new WebView(this);
        board.setBackgroundColor(Color.rgb(13, 20, 29));
        root.addView(board, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.requestApplyInsets();

        WebSettings settings = board.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        board.addJavascriptInterface(new ClipActions(), "AndroidClips");
        board.setWebViewClient(new LocalPageClient());
        board.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams options) {
                if (pendingSoundFiles != null) pendingSoundFiles.onReceiveValue(null);
                pendingSoundFiles = callback;
                Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                open.addCategory(Intent.CATEGORY_OPENABLE);
                open.setType("audio/*");
                open.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(open, PICK_SOUNDS);
                } catch (ActivityNotFoundException e) {
                    deliverSoundFiles(null);
                    showMessage("Die Dateiauswahl ist nicht verfügbar.");
                }
                return true;
            }
        });

        if (savedState == null || board.restoreState(savedState) == null) {
            board.loadUrl(INDEX);
        }
    }

    private static boolean isIndex(Uri uri) {
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
            && "/index.html".equals(uri.getPath()) && uri.getPort() == -1;
    }

    private final class LocalPageClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (request.isForMainFrame() && request.hasGesture() && HOMEPAGE.equals(uri.toString())) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException e) {
                    showMessage("Zum Öffnen der Homepage ist ein Browser erforderlich.");
                }
            }
            return !isIndex(uri);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            try {
                if (isIndex(uri)) {
                    return new WebResourceResponse("text/html", "UTF-8", getAssets().open("index.html"));
                }
                if ("https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
                        && uri.getPort() == -1 && "/favicon.webp".equals(uri.getPath())) {
                    return new WebResourceResponse("image/webp", null, getAssets().open("favicon.webp"));
                }
            } catch (IOException ignored) {
                // Fall through to the offline error response.
            }
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null,
                new ByteArrayInputStream("Datei nicht verfügbar".getBytes(StandardCharsets.UTF_8)));
        }
    }

    private final class ClipActions {
        @JavascriptInterface
        public void open(String filename, String encoded) {
            if (!isClipName(filename) || encoded == null || encoded.length() > MAX_BASE64_LENGTH) return;
            try {
                byte[] contents = Base64.decode(encoded, Base64.DEFAULT);
                String token = UUID.randomUUID().toString();
                File clip = clipFile(getCacheDir(), token, filename);
                if (!clip.getParentFile().mkdirs()) throw new IOException("Cache directory unavailable");
                try (FileOutputStream output = new FileOutputStream(clip)) {
                    output.write(contents);
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showClipActions(clip, token);
                });
            } catch (IllegalArgumentException | IOException e) {
                runOnUiThread(() -> showMessage("Der Sound konnte nicht vorbereitet werden."));
            }
        }
    }

    private void showClipActions(File clip, String token) {
        new AlertDialog.Builder(this)
            .setTitle(clip.getName())
            .setItems(new String[]{"Teilen", "Datei speichern"}, (dialog, position) -> {
                if (position == 0) {
                    Uri uri = new Uri.Builder().scheme("content").authority(AUTHORITY)
                        .appendPath(token).appendPath(clip.getName()).build();
                    Intent send = new Intent(Intent.ACTION_SEND).setType(mimeType(clip.getName()));
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.setClipData(ClipData.newRawUri(clip.getName(), uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Sound teilen"));
                } else {
                    pendingSave = clip;
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType(mimeType(clip.getName()));
                    save.putExtra(Intent.EXTRA_TITLE, clip.getName());
                    startActivityForResult(save, SAVE_CLIP);
                }
            }).show();
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void deliverSoundFiles(Uri[] files) {
        ValueCallback<Uri[]> callback = pendingSoundFiles;
        pendingSoundFiles = null;
        if (callback != null) callback.onReceiveValue(files);
    }

    private Uri[] selectedSoundFiles(Intent result) {
        if (result == null) return null;
        List<Uri> candidates = new ArrayList<>();
        ClipData selected = result.getClipData();
        if (selected != null) {
            for (int i = 0; i < selected.getItemCount(); i++) candidates.add(selected.getItemAt(i).getUri());
        } else {
            candidates.add(result.getData());
        }
        Uri[] files = candidates.stream()
            .filter(uri -> uri != null && "content".equals(uri.getScheme()) && !AUTHORITY.equals(uri.getAuthority()))
            .toArray(Uri[]::new);
        return files.length == 0 ? null : files;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode == PICK_SOUNDS) {
            deliverSoundFiles(resultCode == RESULT_OK ? selectedSoundFiles(result) : null);
            return;
        }
        if (requestCode != SAVE_CLIP) return;
        File clip = pendingSave;
        pendingSave = null;
        if (resultCode != RESULT_OK || result == null || result.getData() == null || clip == null) return;

        Uri destination = result.getData();
        new Thread(() -> {
            try (FileInputStream input = new FileInputStream(clip);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Document unavailable");
                byte[] buffer = new byte[16384];
                for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                runOnUiThread(() -> showMessage("Sound gespeichert."));
            } catch (IOException | SecurityException e) {
                runOnUiThread(() -> showMessage("Der Sound konnte nicht gespeichert werden."));
            }
        }, "save-sound").start();
    }

    @Override
    public void onSaveInstanceState(Bundle state) {
        if (pendingSave != null) {
            state.putString("saveToken", pendingSave.getParentFile().getName());
            state.putString("saveName", pendingSave.getName());
        }
        board.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        board.evaluateJavascript("if (!speaker.paused) { speaker.pause(); clearPlayback(); }", null);
        board.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        board.onResume();
    }

    @Override
    public void onBackPressed() {
        board.evaluateJavascript("(function(){if(dialog.open){dialog.close();return true;}if(openMenu){closeMenu(true);return true;}return false;})()", handled -> {
            if ("true".equals(handled)) return;
            if (board.canGoBack()) board.goBack();
            else super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        deliverSoundFiles(null);
        ((FrameLayout) board.getParent()).removeView(board);
        board.destroy();
        super.onDestroy();
    }

    private static boolean isClipName(String name) {
        return name != null && name.length() <= 150 && !name.contains("/") && !name.contains("\\")
            && name.chars().noneMatch(Character::isISOControl)
            && (name.endsWith(".mp3") || name.endsWith(".wav"));
    }

    private static boolean isClipPath(String token, String name) {
        return token != null && token.matches("[a-f0-9-]{36}") && isClipName(name);
    }

    private static File clipFile(File cache, String token, String name) {
        return new File(new File(new File(cache, "shared-clips"), token), name);
    }

    private static String mimeType(String name) {
        return name.endsWith(".wav") ? "audio/wav" : "audio/mpeg";
    }

    public static final class SharedClipProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }

        private File getClip(Uri uri) {
            List<String> parts = uri.getPathSegments();
            if (!AUTHORITY.equals(uri.getAuthority()) || parts.size() != 2
                    || !isClipPath(parts.get(0), parts.get(1))) throw new IllegalArgumentException("Invalid clip URI");
            return clipFile(getContext().getCacheDir(), parts.get(0), parts.get(1));
        }

        @Override public String getType(Uri uri) { return mimeType(getClip(uri).getName()); }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
            return ParcelFileDescriptor.open(getClip(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String sort) {
            File clip = getClip(uri);
            String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            if (clip.isFile()) {
                MatrixCursor.RowBuilder row = cursor.newRow();
                for (String column : columns) {
                    if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(clip.getName());
                    else if (OpenableColumns.SIZE.equals(column)) row.add(clip.length());
                    else row.add(null);
                }
            }
            return cursor;
        }

        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String sel, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String sel, String[] args) { throw new UnsupportedOperationException(); }
    }
}
