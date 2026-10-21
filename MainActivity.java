package com.inkrecog.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Base64;
import android.view.MotionEvent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;
import com.google.mlkit.vision.digitalink.RecognitionContext;
import com.google.mlkit.vision.digitalink.WritingArea;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 2001;
    private static final int REQ_SAVE = 2002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private byte[] pendingBytes;

    // Makes <a download href="blob:..."> work inside the WebView.
    private static final String DL_JS =
            "(function(){if(window.__inkDl)return;window.__inkDl=1;"
          + "var oc=HTMLAnchorElement.prototype.click;"
          + "HTMLAnchorElement.prototype.click=function(){"
          + "if(this.hasAttribute('download')&&/^blob:/.test(this.href)){"
          + "var n=this.getAttribute('download')||'file',h=this.href;"
          + "fetch(h).then(function(r){return r.blob();}).then(function(b){"
          + "var fr=new FileReader();fr.onload=function(){"
          + "Android.saveBase64(n,b.type||'application/octet-stream',String(fr.result).split(',')[1]);};"
          + "fr.readAsDataURL(b);});return;}"
          + "return oc.apply(this,arguments);};})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        // s7a: ask Android to deliver stylus events immediately instead of batching them once per frame
        webView.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) v.requestUnbufferedDispatch(ev);
            return false;
        });

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(DL_JS, null);
                pushPowerSave();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        // s7b: tell the page when Android battery saver is on, so it can draw the live ink more cheaply
        psReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { pushPowerSave(); }
        };
        registerReceiver(psReceiver, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
    }

    private BroadcastReceiver psReceiver;

    private void pushPowerSave() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        final boolean on = pm != null && pm.isPowerSaveMode();
        runOnUiThread(() -> webView.evaluateJavascript("window.__powerSave&&window.__powerSave(" + on + ")", null));
    }

    @Override
    protected void onDestroy() {
        if (psReceiver != null) { try { unregisterReceiver(psReceiver); } catch (Exception ignored) { } }
        super.onDestroy();
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private DigitalInkRecognizer recognizer;
    private DigitalInkRecognitionModel inkModel;

    private void sendResult(final String text) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
            webView.evaluateJavascript("window.__inkResult&&window.__inkResult(" + JSONObject.quote(text) + ")", null);
        });
    }

    private void status(final String msg) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            webView.evaluateJavascript("window.__inkStatus&&window.__inkStatus(" + JSONObject.quote(msg) + ",true)", null);
        });
    }

    private interface Cb { void done(String text, boolean ok); }

    private void recognizeInk(final String json) {
        recognize(json, (t, ok) -> sendResult(t));
    }

    // payload: {pre:"text before", lines:[{strokes:[[{x,y,t}]], w, h}]} — each line is recognised on its own, with the
    // text recognised so far as context, and the results are joined with "\n".
    private void recognize(final String json, final Cb cb) {
        try {
            final JSONObject payload = new JSONObject(json);
            final JSONArray lines = payload.getJSONArray("lines");
            final String pre = payload.optString("pre", "");

            if (recognizer == null) {
                DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US");
                inkModel = DigitalInkRecognitionModel.builder(id).build();
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(inkModel).build());
            }
            final DigitalInkRecognizer rec = recognizer;
            final RemoteModelManager mm = RemoteModelManager.getInstance();
            final DigitalInkRecognitionModel model = inkModel;
            status("Checking language pack…");
            mm.isModelDownloaded(model).addOnSuccessListener(isDl -> {
                if (isDl) {
                    runLines(rec, lines, 0, pre, new StringBuilder(), cb);
                } else {
                    status("Downloading English language pack… needs internet, please wait");
                    mm.download(model, new DownloadConditions.Builder().build())
                        .addOnSuccessListener(v -> { status("Language pack downloaded"); runLines(rec, lines, 0, pre, new StringBuilder(), cb); })
                        .addOnFailureListener(e -> cb.done("Language pack download failed: " + e.getMessage() + " (check internet / Google Play services)", false));
                }
            }).addOnFailureListener(e -> cb.done("ML Kit error: " + e.getMessage() + " (needs Google Play services)", false));
        } catch (Exception e) {
            cb.done("Recognition error: " + e.getMessage(), false);
        }
    }

    private void runLines(final DigitalInkRecognizer rec, final JSONArray lines, final int idx, final String pre, final StringBuilder acc, final Cb cb) {
        if (idx >= lines.length()) { cb.done(acc.toString(), true); return; }
        try {
            JSONObject line = lines.getJSONObject(idx);
            JSONArray strokes = line.getJSONArray("strokes");
            Ink.Builder ib = Ink.builder();
            for (int i = 0; i < strokes.length(); i++) {
                JSONArray pts = strokes.getJSONArray(i);
                Ink.Stroke.Builder sb = Ink.Stroke.builder();
                for (int k = 0; k < pts.length(); k++) {
                    JSONObject p = pts.getJSONObject(k);
                    sb.addPoint(Ink.Point.create((float) p.getDouble("x"), (float) p.getDouble("y"), p.getLong("t")));
                }
                ib.addStroke(sb.build());
            }
            String ctx = acc.length() > 0 ? acc.toString() : pre;
            if (ctx.length() > 20) ctx = ctx.substring(ctx.length() - 20);
            RecognitionContext rc = RecognitionContext.builder()
                .setPreContext(ctx)
                .setWritingArea(new WritingArea((float) line.getDouble("w"), (float) line.getDouble("h")))
                .build();
            status(lines.length() > 1 ? "Recognizing line " + (idx + 1) + " of " + lines.length() + "…" : "Recognizing…");
            rec.recognize(ib.build(), rc)
                .addOnSuccessListener(r -> {
                    String t = r.getCandidates().isEmpty() ? "" : r.getCandidates().get(0).getText();
                    if (acc.length() > 0 && !t.isEmpty()) acc.append("\n");
                    acc.append(t);
                    runLines(rec, lines, idx + 1, pre, acc, cb);
                })
                .addOnFailureListener(e -> cb.done("Recognition failed: " + e.getMessage(), false));
        } catch (Exception e) {
            cb.done("Recognition error: " + e.getMessage(), false);
        }
    }

    private void ensureInkObjects() {
        if (recognizer == null) {
            DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US");
            inkModel = DigitalInkRecognitionModel.builder(id).build();
            recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(inkModel).build());
        }
    }

    // Is the handwriting language pack already on the phone? Answer goes to window.__lpCheck(true/false).
    private void checkLangPack() {
        try {
            ensureInkObjects();
            RemoteModelManager.getInstance().isModelDownloaded(inkModel)
                .addOnSuccessListener(isDl -> lpCheck(isDl))
                .addOnFailureListener(e -> lpCheck(true)); // cannot check: let the normal recognition flow report its own error
        } catch (Exception e) {
            lpCheck(true);
        }
    }

    private void lpCheck(final boolean has) {
        runOnUiThread(() -> webView.evaluateJavascript("window.__lpCheck&&window.__lpCheck(" + has + ")", null));
    }

    // Download the handwriting language pack (landing page + on-demand pop-up), then tell the page (window.__lpReady).
    private void prefetchLangPack(final boolean online) {
        try {
            ensureInkObjects();
            final RemoteModelManager mm = RemoteModelManager.getInstance();
            final DigitalInkRecognitionModel model = inkModel;
            mm.isModelDownloaded(model).addOnSuccessListener(isDl -> {
                if (isDl) { lpReady(true, "already downloaded"); }
                else if (!online) { lpReady(false, "offline"); }
                else {
                    mm.download(model, new DownloadConditions.Builder().build())
                        .addOnSuccessListener(v -> lpReady(true, "downloaded"))
                        .addOnFailureListener(e -> lpReady(false, "download failed: " + e.getMessage()));
                }
            }).addOnFailureListener(e -> lpReady(false, "check failed: " + e.getMessage()));
        } catch (Exception e) {
            lpReady(false, "error: " + e.getMessage());
        }
    }

    private void lpReady(final boolean ok, final String msg) {
        runOnUiThread(() -> webView.evaluateJavascript("window.__lpReady&&window.__lpReady(" + ok + "," + JSONObject.quote(msg) + ")", null));
    }

    private class Bridge {
        @JavascriptInterface
        public void checkLangPack() {
            runOnUiThread(() -> MainActivity.this.checkLangPack());
        }

        @JavascriptInterface
        public void prefetchLangPack(final boolean online) {
            runOnUiThread(() -> MainActivity.this.prefetchLangPack(online));
        }

        @JavascriptInterface
        public void recognizeLive(final int id, final String json) {
            runOnUiThread(() -> recognize(json, (t, ok) -> runOnUiThread(() ->
                webView.evaluateJavascript("window.__liveResult&&window.__liveResult(" + id + "," + JSONObject.quote(t) + "," + ok + ")", null))));
        }

        @JavascriptInterface
        public void recognizeInk(final String json) {
            runOnUiThread(() -> MainActivity.this.recognizeInk(json));
        }

        @JavascriptInterface
        public void setClipboard(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("INKdown", text == null ? "" : text));
            });
        }

        @JavascriptInterface
        public void saveBase64(final String name, final String mime, final String b64) {
            try {
                pendingBytes = Base64.decode(b64, Base64.DEFAULT);
            } catch (Exception e) {
                toast("Save failed");
                return;
            }
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                i.putExtra(Intent.EXTRA_TITLE, name == null || name.isEmpty() ? "file" : name);
                startActivityForResult(i, REQ_SAVE);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;

        } else if (requestCode == REQ_SAVE) {
            final byte[] bytes = pendingBytes;
            pendingBytes = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || bytes == null) return;
            final Uri uri = data.getData();
            new Thread(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new Exception("Cannot write");
                    out.write(bytes);
                    out.flush();
                    toast("Saved");
                } catch (Exception e) {
                    toast("Save failed: " + e.getMessage());
                }
            }).start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }
}
