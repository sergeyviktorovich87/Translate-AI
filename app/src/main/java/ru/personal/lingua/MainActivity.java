package ru.personal.lingua;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Personal Android shell: ChatGPT website + native speech translation through official account OAuth. */
public final class MainActivity extends Activity {
    private static final int REQUEST_VOICE = 200;
    private static final int REQUEST_FILES = 201;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private ChatAuth auth;
    private WebView web;
    private FrameLayout pages;
    private LinearLayout webPane;
    private ScrollView translatorPane;
    private EditText input, result;
    private TextView accountStatus, busyLabel;
    private Button directionButton, translateButton, loginButton;
    private ValueCallback<Uri[]> fileCallback;
    private TextToSpeech tts;
    private boolean ttsReady = false, ruToZh = true, isTranslator = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(245,246,248));
        getWindow().setNavigationBarColor(Color.rgb(245,246,248));
        auth = new ChatAuth(this);
        tts = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            @Override public void uncaughtException(Thread thread, Throwable error) {
                // Never log throwable messages or sensitive UI content.
                LocalLog.event(MainActivity.this, "crash_" + error.getClass().getSimpleName());
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
        buildUi();
        showTranslator(false);
        LocalLog.event(this, "app_started");
    }

    private void buildUi() {
        LinearLayout layout = column(); layout.setBackgroundColor(Color.rgb(248,249,251));
        setContentView(layout);
        LinearLayout tabs = row(); tabs.setPadding(dp(8), dp(8), dp(8), dp(4));
        Button chat = button("🌐 ChatGPT"); chat.setOnClickListener(v -> showTranslator(false));
        Button translator = button("🎙 Переводчик"); translator.setOnClickListener(v -> showTranslator(true));
        tabs.addView(chat, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(translator, new LinearLayout.LayoutParams(0, dp(48), 1));
        layout.addView(tabs);
        pages = new FrameLayout(this);
        layout.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        buildWebPage(); buildTranslatorPage();

        LinearLayout bar = row(); bar.setPadding(dp(6), dp(4), dp(6), dp(6));
        Button voiceRussian = button("🎙 RU");
        Button voiceChinese = button("🎙 中文");
        Button copy = button("📋");
        Button speaker = button("🔊");
        voiceRussian.setOnClickListener(v -> {ruToZh = true; showTranslator(true); updateDirection(); startRecognition();});
        voiceChinese.setOnClickListener(v -> {ruToZh = false; showTranslator(true); updateDirection(); startRecognition();});
        copy.setOnClickListener(v -> copyResult());
        speaker.setOnClickListener(v -> speak());
        for (Button b : new Button[] {voiceRussian, voiceChinese, copy, speaker})
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
        layout.addView(bar);
    }

    private void buildWebPage() {
        webPane = column();
        LinearLayout bar = row(); bar.setPadding(dp(10), 0, dp(10), 0);
        TextView hint = new TextView(this); hint.setText("Сайт ChatGPT. Вход выполняется на стороне OpenAI.");
        hint.setTextSize(12); hint.setTextColor(Color.DKGRAY);
        bar.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        Button browser = button("Браузер");
        browser.setOnClickListener(v -> openExternal("https://chatgpt.com/"));
        bar.addView(browser);
        webPane.addView(bar);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(true); // Picker-granted content:// uploads only
        s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme(), host = u.getHost();
                if (scheme == null || host == null || !scheme.equalsIgnoreCase("https")) return true;
                if (host.equals("chatgpt.com") || host.endsWith(".chatgpt.com") ||
                    host.equals("openai.com") || host.endsWith(".openai.com")) return false;
                if (request.isForMainFrame()) openExternal(u.toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> callback,
                                                         FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), REQUEST_FILES); }
                catch (Exception ex) {fileCallback = null; callback.onReceiveValue(null); return false;}
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                // No automatic camera/mic permission grant to web content.
                runOnUiThread(request::deny);
            }
        });
        web.loadUrl("https://chatgpt.com/");
        webPane.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        pages.addView(webPane);
    }

    private void buildTranslatorPage() {
        translatorPane = new ScrollView(this); translatorPane.setFillViewport(true);
        LinearLayout inner = column(); inner.setPadding(dp(16), dp(10), dp(16), dp(20));
        translatorPane.addView(inner);
        TextView title = label("Личный голосовой переводчик", 23, Color.BLACK); inner.addView(title);
        accountStatus = label(auth.accountStatus(), 13, Color.DKGRAY); inner.addView(accountStatus);
        loginButton = button("Подключить аккаунт ChatGPT");
        loginButton.setOnClickListener(v -> {
            loginButton.setEnabled(false);
            accountStatus.setText("Авторизация открыта в браузере…");
            auth.startLogin((ok, message) -> {
                loginButton.setEnabled(true);
                accountStatus.setText(ok ? auth.accountStatus() : message);
                if (!ok) showMessage(message);
            });
        });
        inner.addView(loginButton, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView note = label("Для перевода разреши приложению использовать свой тариф ChatGPT. " +
                "Существующие чаты через этот доступ не читаются.", 12, Color.GRAY);
        inner.addView(note);
        directionButton = button("Русский → 简体中文  ⇄");
        directionButton.setOnClickListener(v -> { ruToZh = !ruToZh; updateDirection(); });
        inner.addView(directionButton, new LinearLayout.LayoutParams(-1, dp(50)));
        inner.addView(label("Исходный текст", 16, Color.DKGRAY));
        input = new EditText(this); input.setTextSize(19); input.setGravity(Gravity.TOP);
        input.setSingleLine(false); input.setMinLines(3); input.setMaxLines(8);
        input.setHint("Говори или напечатай фразу…");
        inner.addView(input, new LinearLayout.LayoutParams(-1, dp(140)));
        LinearLayout controls = row();
        Button mic = button("🎙 Записать"); mic.setOnClickListener(v -> startRecognition());
        translateButton = button("Перевести"); translateButton.setOnClickListener(v -> translate());
        controls.addView(mic, new LinearLayout.LayoutParams(0, dp(54), 1));
        controls.addView(translateButton, new LinearLayout.LayoutParams(0, dp(54), 1));
        inner.addView(controls);
        busyLabel = label("", 13, Color.DKGRAY); inner.addView(busyLabel);
        inner.addView(label("Перевод и пиньинь", 16, Color.DKGRAY));
        result = new EditText(this); result.setTextSize(20); result.setGravity(Gravity.TOP);
        result.setMinLines(4); result.setSingleLine(false);
        result.setHint("Здесь появится результат");
        inner.addView(result, new LinearLayout.LayoutParams(-1, dp(180)));
        LinearLayout actions = row();
        Button copy = button("📋 Копировать"); copy.setOnClickListener(v -> copyResult());
        Button speak = button("🔊 Прослушать"); speak.setOnClickListener(v -> speak());
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(speak, new LinearLayout.LayoutParams(0, dp(52), 1));
        inner.addView(actions);
        LinearLayout extra = row();
        Button logout = button("Отключить аккаунт");
        logout.setOnClickListener(v -> {
            accountStatus.setText("Отключение…");
            auth.signOut((ok, text) -> { accountStatus.setText(auth.accountStatus()); showMessage(text); });
        });
        Button logs = button("Логи теста"); logs.setOnClickListener(v -> exportLogs());
        extra.addView(logout, new LinearLayout.LayoutParams(0, dp(50), 1));
        extra.addView(logs, new LinearLayout.LayoutParams(0, dp(50), 1));
        inner.addView(extra);
        pages.addView(translatorPane);
    }

    private void updateDirection() {
        directionButton.setText(ruToZh ? "Русский → 简体中文  ⇄" : "简体中文 → Русский  ⇄");
    }
    private void showTranslator(boolean show) {
        isTranslator = show;
        translatorPane.setVisibility(show ? View.VISIBLE : View.GONE);
        webPane.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show) accountStatus.setText(auth.accountStatus());
    }
    private void startRecognition() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, ruToZh ? "ru-RU" : "zh-CN");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите…");
            startActivityForResult(i, REQUEST_VOICE);
            LocalLog.event(this, "speech_launch");
        } catch (Exception e) {
            LocalLog.event(this, "speech_unavailable");
            showMessage("На телефоне не найден сервис распознавания речи.");
        }
    }
    private void translate() {
        String source = input.getText().toString().trim();
        if (source.isEmpty()) { showMessage("Сначала введи текст или нажми микрофон."); return; }
        translateButton.setEnabled(false); busyLabel.setText("Переводим…");
        LocalLog.event(this, "translation_start");
        final boolean direction = ruToZh;
        background.execute(() -> {
            try {
                String translated = auth.translate(direction, source);
                runOnUiThread(() -> {
                    translateButton.setEnabled(true); busyLabel.setText("Готово");
                    result.setText(translated);
                });
            } catch (Exception e) {
                LocalLog.event(this, "translation_error_" + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    translateButton.setEnabled(true); busyLabel.setText("Ошибка перевода");
                    showMessage("Перевод не выполнен: " + ChatAuth.safeError(e));
                });
            }
        });
    }
    private void copyResult() {
        String text = result.getText().toString();
        if (text.isEmpty()) { showMessage("Сначала переведи фразу."); return; }
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("Перевод", text));
        showMessage("Скопировано");
    }
    private void speak() {
        String text = ChatAuth.targetForSpeech(result.getText().toString());
        if (text.isEmpty()) { showMessage("Нет текста для озвучивания."); return; }
        if (!ttsReady) { showMessage("Системный синтез речи недоступен."); return; }
        int status = tts.setLanguage(ruToZh ? Locale.SIMPLIFIED_CHINESE : new Locale("ru", "RU"));
        if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
            showMessage("Нужно установить системный голос выбранного языка."); return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation");
    }
    private void exportLogs() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Личный переводчик: тестовые логи");
        i.putExtra(Intent.EXTRA_TEXT, LocalLog.read(this));
        startActivity(Intent.createChooser(i, "Отправить логи"));
    }
    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { showMessage("Браузер не найден"); }
    }
    private void showMessage(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setTextSize(13); b.setAllCaps(false); return b; }
    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(color); view.setPadding(dp(4), dp(10), dp(4), dp(9)); return view;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request == REQUEST_FILES) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        } else if (request == REQUEST_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                showTranslator(true); input.setText(results.get(0));
                LocalLog.event(this, "speech_success");
                // Voice is a one-tap translation action after the user has authorized an account.
                translate();
            }
        }
    }
    @Override public void onBackPressed() {
        if (!isTranslator && web.canGoBack()) web.goBack();
        else if (isTranslator) showTranslator(false);
        else super.onBackPressed();
    }
    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
    @Override protected void onDestroy() {
        auth.destroy(); background.shutdownNow();
        if (tts != null) tts.shutdown();
        if (web != null) {web.destroy();}
        super.onDestroy();
    }
}
