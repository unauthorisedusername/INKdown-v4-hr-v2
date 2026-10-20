package com.inkrecog.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
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

    private void recognizeInk(final String json) {
        try {
            Ink.Builder ib = Ink.builder();
            JSONArray strokes = new JSONArray(json);
            for (int i = 0; i < strokes.length(); i++) {
                JSONArray pts = strokes.getJSONArray(i);
                Ink.Stroke.Builder sb = Ink.Stroke.builder();
                for (int k = 0; k < pts.length(); k++) {
                    JSONObject p = pts.getJSONObject(k);
                    sb.addPoint(Ink.Point.create((float) p.getDouble("x"), (float) p.getDouble("y"), p.getLong("t")));
                }
                ib.addStroke(sb.build());
            }
            final Ink ink = ib.build();

            if (recognizer == null) {
                DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US");
                inkModel = DigitalInkRecognitionModel.builder(id).build();
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(inkModel).build());
            }
            final DigitalInkRecognizer rec = recognizer;
            RemoteModelManager mm = RemoteModelManager.getInstance();
            mm.download(inkModel, new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> rec.recognize(ink)
                    .addOnSuccessListener(r -> sendResult(r.getCandidates().isEmpty() ? "(nothing recognised)" : r.getCandidates().get(0).getText()))
                    .addOnFailureListener(e -> sendResult("Recognition failed: " + e.getMessage())))
                .addOnFailureListener(e -> sendResult("Model download failed (needs internet once): " + e.getMessage()));
        } catch (Exception e) {
            sendResult("Recognition error: " + e.getMessage());
        }
    }

    private class Bridge {
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
