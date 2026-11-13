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
    private LinearLayout translatorControls, resultPane;
    private EditText source, answer;
    private Button directionButton;
    private TextToSpeech tts;
    private boolean ttsReady, ruToZh = true, translatorMode = true;
    private String lastNavigationTag = "";
    private boolean fallbackManualSend = false;
    private TextView translatorStatus;
    private Button translateAction;

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

        // Native shell header. The website remains in the same WebView on both tabs.
        LinearLayout header = row();
        header.setPadding(dp(10), dp(8), dp(10), dp(5));
        TextView brand = label("Translate AI", 20);
        brand.setTextColor(Color.rgb(20, 32, 54));
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button translator = button("Переводчик");
        Button chat = button("Чат");
        translator.setOnClickListener(v -> showMode(true));
        chat.setOnClickListener(v -> showMode(false));
        header.addView(translator, new LinearLayout.LayoutParams(dp(104), dp(44)));
        header.addView(chat, new LinearLayout.LayoutParams(dp(72), dp(44)));
        root.addView(header);

        translatorControls = column();
        translatorControls.setPadding(dp(12), dp(2), dp(12), dp(8));
        TextView subtitle = label("Русский ↔ 简体中文", 13);
        subtitle.setTextColor(Color.rgb(96, 106, 127));
        translatorControls.addView(subtitle);
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
        translateAction = button("Перевести");
        translateAction.setTextColor(Color.WHITE);
        translateAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(35, 106, 241)));
        translateAction.setOnClickListener(v -> preparePrompt());
        actions.addView(microphone, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(translateAction, new LinearLayout.LayoutParams(0, dp(50), 2));
        translatorControls.addView(actions);
        translatorStatus = label("Перевод появится ниже. Ответ остаётся на странице ChatGPT.", 12);
        translatorStatus.setTextColor(Color.rgb(92, 105, 125));
        translatorControls.addView(translatorStatus);
        root.addView(translatorControls);

        // SAME WebView instance is retained between modes: one account, one cookie jar.
        web = new WebView(this);
        configureWebView(web, false);
        web.setWebViewClient(new SiteClient(false));
        web.setWebChromeClient(new BrowserChrome());
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));

        resultPane = column();
        resultPane.setPadding(dp(10), dp(2), dp(10), dp(3));
        resultPane.addView(label("Результат перевода (вставь скопированный ответ)", 12));
        answer = new EditText(this);
        answer.setTextSize(18);
        answer.setHint("Скопируй ответ в ChatGPT, затем нажми «📥 Ответ»");
        answer.setSingleLine(false);
        answer.setMinLines(2);
        answer.setMaxLines(5);
        resultPane.addView(answer, new LinearLayout.LayoutParams(-1, dp(95)));
        root.addView(resultPane);
        resultPane.setVisibility(View.GONE);

        LinearLayout bottom = row();
        bottom.setPadding(dp(5), dp(2), dp(5), dp(5));
        Button paste = button("📥 Ответ");
        paste.setOnClickListener(v -> pasteAnswer());
        Button copy = button("📋 Копировать");
        copy.setOnClickListener(v -> copyAnswer());
        Button speaker = button("🔊 Слушать");
        speaker.setOnClickListener(v -> speakAnswer());
        Button logs = button("Логи");
        logs.setOnClickListener(v -> exportLogs());
        bottom.addView(paste, new LinearLayout.LayoutParams(0, dp(46), 1));
        bottom.addView(copy, new LinearLayout.LayoutParams(0, dp(46), 1.15f));
        bottom.addView(speaker, new LinearLayout.LayoutParams(0, dp(46), 1));
        bottom.addView(logs, new LinearLayout.LayoutParams(0, dp(46), 0.55f));
        root.addView(bottom);
    }

    private void showMode(boolean translator) {
        translatorMode = translator;
        translatorControls.setVisibility(translator ? View.VISIBLE : View.GONE);
        resultPane.setVisibility(translator && answer.length() > 0 ? View.VISIBLE : View.GONE);
        // Never reload the website when changing modes; this preserves login.
        ui.post(() -> applyWebMode());
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
                 || host.equals("accounts.google.de")
                 || host.equals("accounts.google.ru")
                 || host.equals("accounts.google.co.uk")
                 || host.equals("accounts.google.co.jp")
                 || host.equals("accounts.google.com.hk")
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
            if (!isPopup && isChatHost(host)) {
                ui.postDelayed(() -> applyWebMode(), 600);
            }
            // Some sign-in providers open a new window; it shares cookies with the main WebView.
            String loadedPath = uri.getPath();
            if (isPopup && isChatHost(host) &&
                    (loadedPath == null || !loadedPath.startsWith("/auth/"))) {
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

    /**
     * The native Translate button is the user's explicit send action.
     * We never parse/copy the generated answer from the ChatGPT webpage.
     */
    private void preparePrompt() {
        String message = source.getText().toString().trim();
        if (message.isEmpty()) { toast("Напиши или продиктуй фразу"); return; }
        String instruction = ruToZh
            ? "Переведи текст с русского на упрощённый китайский. Ответ строго в формате:\n"
              + "1) Китайский перевод\n2) Пиньинь с тонами\n3) Краткое объяснение по-русски."
              + "\nБез приветствий, рассуждений и дополнительных вопросов.\nТекст:\n"
            : "Переведи текст с упрощённого китайского на русский. Ответ строго в формате:\n"
              + "1) Русский перевод\n2) Пиньинь исходной фразы с тонами"
              + "\n3) Краткое объяснение по-русски. Без вступлений и лишнего текста.\nТекст:\n";
        String prompt = instruction + message;
        copyTextQuiet(prompt); // manual fallback if ChatGPT changes its composer
        LocalLog.event(this, "translator_submit_requested");
        Uri url;
        try { url = Uri.parse(web.getUrl()); }
        catch (Exception ignored) { url = null; }
        if (url == null || !isChatHost(url.getHost())) {
            translatorStatus.setText("Войди в ChatGPT. Запрос уже скопирован.");
            toast("Открой ChatGPT, вставь текст и отправь");
            return;
        }

        translateAction.setEnabled(false);
        translatorStatus.setText("Отправляем запрос…");
        fallbackManualSend = false;
        showWebComposer(true); // make the real input available for filling

        // A strictly scoped site interaction after the user tapped Translate.
        // Do not access network, user identity, cookies, or output in JavaScript.
        String fill = "(function(){"
            + "const box=document.querySelector('#prompt-textarea')"
            + "||document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]')"
            + "||document.querySelector('textarea');"
            + "if(!box)return 'composer_missing';"
            + "let v=" + JSONObject.quote(prompt) + ";"
            + "box.focus();"
            + "if(box.tagName==='TEXTAREA'){"
            + "const d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');"
            + "if(d&&d.set)d.set.call(box,v);else box.value=v;"
            + "}else{"
            + "const selection=window.getSelection();"
            + "const range=document.createRange();range.selectNodeContents(box);"
            + "selection.removeAllRanges();selection.addRange(range);"
            + "if(!document.execCommand('insertText',false,v))box.textContent=v;"
            + "}"
            + "box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:v}));"
            + "return 'prepared';})()";
        web.evaluateJavascript(fill, result -> {
            if (!"\"prepared\"".equals(result)) {
                manualFallback("Поле ChatGPT изменилось. Запрос скопирован, вставь вручную.");
                LocalLog.event(this, "translator_composer_unavailable");
                return;
            }
            LocalLog.event(this, "translator_prompt_prepared");
            ui.postDelayed(() -> trySendPrompt(), 450);
        });
    }

    private void trySendPrompt() {
        // Limit the click to known ChatGPT send controls. No generic buttons.
        String click = "(function(){"
            + "const box=document.querySelector('#prompt-textarea')"
            + "||document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]')"
            + "||document.querySelector('textarea');"
            + "if(!box || !(box.value||box.innerText||box.textContent||'').trim())"
            + "return 'empty_composer';"
            + "const selectors=['button[data-testid=\"send-button\"]',"
            + "'button[data-testid=\"composer-send-button\"]',"
            + "'button[aria-label=\"Send prompt\"]',"
            + "'button[aria-label=\"Отправить сообщение\"]'];"
            + "let send=null;"
            + "for(const selector of selectors){let found=document.querySelector(selector);"
            + "if(found&&!found.disabled){send=found;break;}}"
            + "if(!send)return 'send_button_unavailable';"
            + "send.click();return 'clicked';})()";
        web.evaluateJavascript(click, result -> {
            translateAction.setEnabled(true);
            if ("\"clicked\"".equals(result)) {
                LocalLog.event(this, "translator_send_clicked");
                translatorStatus.setText("Запрос отправлен в ChatGPT. Ответ появится ниже.");
                ui.postDelayed(() -> { if (translatorMode && !fallbackManualSend)
                    applyWebMode(); }, 1250);
            } else {
                LocalLog.event(this, "translator_send_unavailable");
                manualFallback("Запрос подготовлен. Нажми отправку в ChatGPT ниже.");
            }
        });
    }

    private void manualFallback(String reason) {
        fallbackManualSend = true;
        translateAction.setEnabled(true);
        translatorStatus.setText(reason);
        showWebComposer(true);
        toast(reason);
    }

    private void applyWebMode() {
        if (web == null) return;
        Uri uri;
        try {uri=Uri.parse(web.getUrl());}
        catch (Exception e) {return;}
        if (uri == null || !isChatHost(uri.getHost())) return;
        showWebComposer(!translatorMode || fallbackManualSend);
    }

    private void showWebComposer(boolean show) {
        if (web == null) return;
        // Pure local presentation change. Never hide sign-in controls,
        // and allow Chat mode or manual fallback to restore the website.
        String code = "(function(){"
            + "if(!location.hostname.endsWith('chatgpt.com'))return;"
            + "let node=document.getElementById('translate-ai-composer-style');"
            + (show
                ? "if(node)node.remove();"
                : "if(!node){node=document.createElement('style');"
                + "node.id='translate-ai-composer-style';"
                + "node.textContent='form:has(#prompt-textarea){display:none!important}';"
                + "document.head.appendChild(node);}")
            + "})()";
        web.evaluateJavascript(code, null);
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

    private void pasteAnswer() {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip() || manager.getPrimaryClip() == null ||
                manager.getPrimaryClip().getItemCount() == 0) {
            toast("Сначала скопируй ответ из ChatGPT"); return;
        }
        // Read only plain text from the clipboard on a direct user click.
        CharSequence content = manager.getPrimaryClip().getItemAt(0).getText();
        if (content == null || content.length() == 0) {
            toast("В буфере нет текста. Скопируй перевод в ChatGPT."); return;
        }
        if (content.length() > 12000) {
            toast("Ответ слишком длинный для этого поля"); return;
        }
        showMode(true);
        answer.setText(content);
        resultPane.setVisibility(View.VISIBLE);
        LocalLog.event(this, "answer_pasted_from_clipboard");
    }

    private void copyAnswer() {
        String text = answer.getText().toString();
        if (text.trim().isEmpty()) {toast("Сначала вставь результат"); return;}
        copyText(text);
        LocalLog.event(this, "answer_copied");
    }

    private void speakAnswer() {
        String text = answer.getText().toString().trim();
        if (text.isEmpty()) {toast("Сначала вставь результат"); return;}
        if (!ttsReady) {toast("Озвучивание пока недоступно"); return;}
        int status = tts.setLanguage(ruToZh ? Locale.SIMPLIFIED_CHINESE : new Locale("ru", "RU"));
        if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("Установи голос выбранного языка в настройках Android"); return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translate_ai_answer");
        LocalLog.event(this, "answer_speech_started");
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
