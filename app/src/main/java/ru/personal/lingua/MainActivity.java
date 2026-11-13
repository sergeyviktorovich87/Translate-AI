package ru.personal.lingua;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Personal ChatGPT website shell. ONE WebView cookie session, no API or OAuth tokens.
 * User-triggered prompt preparation only; answers remain on the ChatGPT website.
 * Does not extract ChatGPT responses or access cookies from JavaScript.
 */
public final class MainActivity extends Activity {
    private static final int VOICE_REQUEST = 123;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web, popup;
    private Dialog popupDialog;
    private LinearLayout translatorControls;
    private EditText source;
    private Button directionButton;
    private TextToSpeech tts;
    private boolean ttsReady, ruToZh = true, translatorMode = true;
    private String lastNavigationTag = "";

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().setStatusBarColor(Color.rgb(245, 247, 250));
        getWindow().setNavigationBarColor(Color.rgb(245, 247, 250));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        tts = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);
        buildUi();
        LocalLog.event(this, "app_started_v0_2");
        web.loadUrl("https://chatgpt.com/");
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(247, 248, 252));
        setContentView(root);

        LinearLayout tabs = row();
        tabs.setPadding(dp(6), dp(6), dp(6), 0);
        Button translator = button("🌐 Переводчик");
        Button chat = button("💬 ChatGPT");
        translator.setOnClickListener(v -> showMode(true));
        chat.setOnClickListener(v -> showMode(false));
        tabs.addView(translator, new LinearLayout.LayoutParams(0, dp(49), 1));
        tabs.addView(chat, new LinearLayout.LayoutParams(0, dp(49), 1));
        root.addView(tabs);

        translatorControls = column();
        translatorControls.setPadding(dp(10), dp(2), dp(10), dp(4));
        TextView note = label("Один вход в ChatGPT. Ответ показывается в чате ниже.", 12);
        translatorControls.addView(note);
        directionButton = button("Русский → 中文  ⇄");
        directionButton.setOnClickListener(v -> {
            ruToZh = !ruToZh;
            directionButton.setText(ruToZh ? "Русский → 中文  ⇄" : "中文 → Русский  ⇄");
            LocalLog.event(this, "translation_direction_changed");
        });
        translatorControls.addView(directionButton, new LinearLayout.LayoutParams(-1, dp(44)));

        source = new EditText(this);
        source.setTextSize(17);
        source.setHint("Напиши или продиктуй фразу…");
        source.setSingleLine(false);
        source.setMaxLines(4);
        source.setMinLines(2);
        translatorControls.addView(source, new LinearLayout.LayoutParams(-1, dp(95)));

        LinearLayout actions = row();
        Button microphone = button("🎙 Голос");
        microphone.setOnClickListener(v -> startVoice());
        Button translate = button("Перевести в ChatGPT");
        translate.setOnClickListener(v -> preparePrompt());
        actions.addView(microphone, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(translate, new LinearLayout.LayoutParams(0, dp(48), 2));
        translatorControls.addView(actions);
        root.addView(translatorControls);

        // SAME WebView instance is retained between modes: one account, one cookie jar.
        web = new WebView(this);
        configureWebView(web, false);
        web.setWebViewClient(new SiteClient(false));
        web.setWebChromeClient(new BrowserChrome());
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = row();
        bottom.setPadding(dp(5), dp(2), dp(5), dp(5));
        Button copy = button("📋 Текст");
        copy.setOnClickListener(v -> {
            String text = source.getText().toString();
            if (text.trim().isEmpty()) {toast("Нет текста для копирования"); return;}
            copyText(text);
        });
        Button speaker = button("🔊 Перевод");
        speaker.setOnClickListener(v -> speakClipboard());
        Button logs = button("Логи");
        logs.setOnClickListener(v -> exportLogs());
        Button reload = button("↻");
        reload.setOnClickListener(v -> web.reload());
        bottom.addView(copy, new LinearLayout.LayoutParams(0, dp(46), 1));
        bottom.addView(speaker, new LinearLayout.LayoutParams(0, dp(46), 1));
        bottom.addView(logs, new LinearLayout.LayoutParams(0, dp(46), 1));
        bottom.addView(reload, new LinearLayout.LayoutParams(0, dp(46), 0.55f));
        root.addView(bottom);
    }

    private void showMode(boolean translator) {
        translatorMode = translator;
        translatorControls.setVisibility(translator ? View.VISIBLE : View.GONE);
        // Do not reload: reloading can interrupt an authentication redirect.
        LocalLog.event(this, translator ? "show_translator" : "show_chat");
    }

    private void configureWebView(WebView view, boolean loginPopup) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(!loginPopup);
        settings.setJavaScriptCanOpenWindowsAutomatically(!loginPopup);
        // Remove Android WebView marker; preserve browser/Android Chrome version.
        String ua = settings.getUserAgentString();
        if (ua != null) settings.setUserAgentString(ua.replace("; wv", ""));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(view, loginPopup);
    }

    private boolean isChatHost(String host) {
        return host != null &&
            (host.equals("chatgpt.com") || host.endsWith(".chatgpt.com"));
    }
    private boolean isSiteHost(String host) {
        return isChatHost(host) || domain(host, "openai.com");
    }
    private boolean domain(String host, String suffix) {
        return host != null && (host.equals(suffix) || host.endsWith("." + suffix));
    }
    private boolean isLoginHost(String host) {
        // Google auth hops stay in same WebView cookie partition.
        return domain(host, "google.com")
            || domain(host, "googleusercontent.com")
            || domain(host, "gstatic.com")
            || host != null && (host.equals("accounts.youtube.com")
                 || host.startsWith("accounts.google.")
                 || domain(host, "microsoftonline.com")
                 || host.equals("appleid.apple.com"));
    }

    private final class SiteClient extends WebViewClient {
        private final boolean isPopup;
        SiteClient(boolean popupClient) {isPopup = popupClient;}
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if ("about".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme))
                return false;
            if (!"https".equalsIgnoreCase(scheme)) return true;
            if (!request.isForMainFrame()) return false;
            if (isSiteHost(host) || isLoginHost(host)) return false;
            if (isPopup) {
                closePopup(false);
            }
            external(uri);
            return true;
        }
        @Override public void onPageFinished(WebView view, String url) {
            Uri uri;
            try {uri = Uri.parse(url);} catch (Exception e) {return;}
            String host = uri.getHost();
            String navTag = isChatHost(host) ? "chat_page_loaded" :
                    isLoginHost(host) || domain(host, "auth.openai.com") ?
                    "auth_page_loaded" : "other_page_loaded";
            if (!navTag.equals(lastNavigationTag)) {
                LocalLog.event(MainActivity.this, (isPopup ? "popup_" : "main_") + navTag);
                lastNavigationTag = navTag;
            }
            // Some sign-in providers open a new window; it shares cookies with the main WebView.
            if (isPopup && isChatHost(host) && !uri.getPath().startsWith("/auth/")) {
                LocalLog.event(MainActivity.this, "popup_login_returned");
                ui.postDelayed(() -> closePopup(true), 650);
            }
        }
        @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                              WebResourceError error) {
            if (request.isForMainFrame()) {
                LocalLog.event(MainActivity.this, "web_network_error_" + error.getErrorCode());
                toast("Страница недоступна. Проверь соединение и DNS.");
            }
        }
    }

    private final class BrowserChrome extends WebChromeClient {
        @Override public boolean onCreateWindow(WebView view, boolean isDialog,
                                                 boolean isUserGesture, Message resultMsg) {
            if (!isUserGesture || popup != null) return false;
            LocalLog.event(MainActivity.this, "auth_popup_created");
            popup = new WebView(MainActivity.this);
            configureWebView(popup, true);
            popup.setWebViewClient(new SiteClient(true));
            popup.setWebChromeClient(new WebChromeClient() {
                @Override public void onCloseWindow(WebView window) {closePopup(true);}
                @Override public void onPermissionRequest(PermissionRequest request) {
                    runOnUiThread(request::deny);
                }
            });
            popupDialog = new Dialog(MainActivity.this);
            popupDialog.setContentView(popup);
            popupDialog.setOnDismissListener(d -> {
                // A posted destroy avoids destroying WebView mid-WebKit callback.
                WebView old = popup;
                popup = null;
                popupDialog = null;
                if (old != null) ui.post(old::destroy);
                CookieManager.getInstance().flush();
            });
            popupDialog.show();
            if (popupDialog.getWindow() != null) {
                popupDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                                                  ViewGroup.LayoutParams.MATCH_PARENT);
            }
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }
        @Override public void onCloseWindow(WebView window) {closePopup(true);}
        @Override public void onPermissionRequest(PermissionRequest request) {
            // Do not grant web microphone/camera permissions automatically.
            runOnUiThread(request::deny);
        }
    }

    private void closePopup(boolean refreshMain) {
        ui.post(() -> {
            if (popupDialog != null) popupDialog.dismiss();
            if (refreshMain) {
                CookieManager.getInstance().flush();
                web.loadUrl("https://chatgpt.com/");
            }
        });
    }

    private void preparePrompt() {
        String message = source.getText().toString().trim();
        if (message.isEmpty()) {toast("Сначала напиши или продиктуй фразу"); return;}
        String instruction = ruToZh
            ? "Переведи с русского на упрощённый китайский. Сначала дай китайский перевод, "
              + "затем пиньинь с тонами, затем краткое объяснение по-русски. "
              + "Без вступления и лишнего текста.\nИсходный текст:\n"
            : "Переведи с китайского на русский. Сначала дай русский перевод, "
              + "затем пиньинь оригинала с тонами, затем краткое объяснение по-русски. "
              + "Без вступления и лишнего текста.\nИсходный текст:\n";
        String fullPrompt = instruction + message;
        copyTextQuiet(fullPrompt);
        LocalLog.event(this, "prompt_prepared");
        Uri page = null;
        try {page = Uri.parse(web.getUrl());} catch (Exception ignored) {}
        if (page == null || !isChatHost(page.getHost())) {
            toast("Запрос скопирован. Войди в ChatGPT, вставь текст и отправь.");
            return;
        }
        // User-triggered convenience: prefill the site's input. No response scraping.
        String js = "(function() {"
          + "const box=document.querySelector('#prompt-textarea')"
          + "||document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]')"
          + "||document.querySelector('div[contenteditable=\"true\"]')"
          + "||document.querySelector('textarea');"
          + "if(!box)return false;"
          + "const v=" + JSONObject.quote(fullPrompt) + ";"
          + "box.focus();"
          + "if(box.tagName==='TEXTAREA'){const d=Object.getOwnPropertyDescriptor("
          + "HTMLTextAreaElement.prototype,'value');d.set.call(box,v);}"
          + "else {box.textContent=v;}"
          + "box.dispatchEvent(new Event('input',{bubbles:true}));"
          + "return true;})()";
        web.evaluateJavascript(js, ok -> {
            if ("true".equals(ok)) {
                LocalLog.event(this, "prompt_prefilled");
                toast("Текст готов в ChatGPT. Нажми отправку.");
            } else {
                LocalLog.event(this, "prompt_prefill_unavailable");
                toast("Запрос скопирован. Вставь его в ChatGPT и отправь.");
            }
        });
    }

    private void startVoice() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, ruToZh ? "ru-RU" : "zh-CN");
            startActivityForResult(intent, VOICE_REQUEST);
            LocalLog.event(this, "speech_started");
        } catch (Exception e) {
            LocalLog.event(this, "speech_unavailable");
            toast("На телефоне нет распознавания речи");
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent intent) {
        super.onActivityResult(req, result, intent);
        if (req == VOICE_REQUEST && result == RESULT_OK && intent != null) {
            ArrayList<String> words = intent.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (words != null && !words.isEmpty()) {
                source.setText(words.get(0));
                LocalLog.event(this, "speech_completed");
                preparePrompt();
            }
        }
    }

    private void speakClipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip() || manager.getPrimaryClip() == null
                || manager.getPrimaryClip().getItemCount() == 0) {
            toast("Сначала скопируй перевод в ChatGPT"); return;
        }
        CharSequence text = manager.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            toast("Буфер обмена пуст"); return;
        }
        if (!ttsReady) {toast("Озвучивание пока недоступно"); return;}
        int status = tts.setLanguage(ruToZh ? Locale.SIMPLIFIED_CHINESE : new Locale("ru", "RU"));
        if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("Установи голос выбранного языка в настройках Android"); return;
        }
        tts.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null, "translate_ai_clipboard");
        LocalLog.event(this, "speech_playback_started");
    }

    private void copyTextQuiet(String text) {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Translate AI", text));
    }
    private void copyText(String text) {copyTextQuiet(text); toast("Скопировано");}

    private void exportLogs() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Translate AI: диагностические события");
        share.putExtra(Intent.EXTRA_TEXT, LocalLog.read(this));
        startActivity(Intent.createChooser(share, "Отправить логи теста"));
    }
    private void external(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(i);
        } catch (Exception e) {toast("Браузер не найден");}
    }
    private void toast(String text) {Toast.makeText(this, text, Toast.LENGTH_LONG).show();}
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private TextView label(String text, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(Color.DKGRAY);
        t.setPadding(dp(6), dp(5), dp(6), dp(6));
        return t;
    }
    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }
    private int dp(int n) {return Math.round(n * getResources().getDisplayMetrics().density);}
    @Override public void onBackPressed() {
        if (popupDialog != null) {closePopup(false); return;}
        if (web.canGoBack()) {web.goBack(); return;}
        super.onBackPressed();
    }
    @Override protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
    @Override protected void onDestroy() {
        if (popupDialog != null) popupDialog.dismiss();
        if (web != null) web.destroy();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
