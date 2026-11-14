package ru.personal.lingua;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Translate AI 0.4.0: native UI and official ChatGPT plan-use OAuth.
 * No website, no web scraping, no direct use of ChatGPT web cookies.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_VOICE=100;
    private static final int REQUEST_IMAGE=101;
    private static final int BG=Color.rgb(247,248,252);
    private static final int INK=Color.rgb(24,34,55);
    private static final int BLUE=Color.rgb(41,105,246);
    private static final int MUTED=Color.rgb(102,114,134);
    private ChatAuth auth;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextToSpeech tts;
    private boolean ttsReady=false, translating=true, ruToZh=true, busy=false;
    private Uri attachedImage;
    private LinearLayout root, translationScreen, chatScreen, messages;
    private EditText translationInput, chatInput;
    private TextView translationOutput, translationNote, photoLabel, chatPhotoLabel, accountLabel;
    private Button translationButton, chatSendButton, photoRemoveButton, chatRemoveButton;
    private ScrollView chatScroll;
    private JSONArray chatHistory=new JSONArray();
    private String lastTranslation="";
    private String lastModel="";

    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        auth=new ChatAuth(this);
        tts=new TextToSpeech(this,status -> ttsReady=status==TextToSpeech.SUCCESS);
        LocalLog.event(this,"native_040_started");
        if(auth.isConnected())showMain();
        else showLogin();
    }

    private void showLogin(){
        root=column();root.setPadding(dp(22),dp(28),dp(22),dp(20));
        root.setBackgroundColor(BG);setContentView(root);
        Space space=new Space(this);root.addView(space,new LinearLayout.LayoutParams(-1,0,1));
        TextView logo=text("文  Translate AI",30,INK,true);
        root.addView(logo);
        TextView subtitle=text("Переводчик и ИИ-помощник\nс фотографиями и голосом",17,MUTED,false);
        subtitle.setPadding(0,dp(14),0,dp(30));root.addView(subtitle);
        TextView note=text("Один вход в твой аккаунт ChatGPT. "
             +"После подключения приложение работает в собственном интерфейсе.",15,INK,false);
        note.setPadding(dp(16),dp(16),dp(16),dp(16));
        note.setBackground(shape(Color.WHITE,18));
        root.addView(note,new LinearLayout.LayoutParams(-1,-2));
        Space spacer=new Space(this);root.addView(spacer,new LinearLayout.LayoutParams(-1,0,1));
        TextView status=text("Вход требуется только при первом подключении "
            +"или после отзыва доступа.",13,MUTED,false);
        root.addView(status);
        Button login=button("Продолжить с ChatGPT",true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));
        lp.topMargin=dp(16);root.addView(login,lp);
        login.setOnClickListener(v -> {
            if(busy)return;
            busy=true;login.setEnabled(false);
            status.setText("Открываю защищённую авторизацию OpenAI…");
            auth.startLogin((ok,detail)->{
                busy=false;
                login.setEnabled(true);
                if(ok){LocalLog.event(this,"native_login_success");showMain();}
                else{status.setText(detail);LocalLog.event(this,"native_login_failed");}
            });
        });
    }

    private void showMain(){
        root=column();root.setBackgroundColor(BG);setContentView(root);
        LinearLayout header=row();
        header.setPadding(dp(16),dp(10),dp(16),dp(4));
        LinearLayout heading=column();
        heading.addView(text("Translate AI",24,INK,true));
        accountLabel=text(auth.accountStatus(),11,MUTED,false);
        heading.addView(accountLabel);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=button("⋮",false);
        settings.setOnClickListener(v->new AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setItems(new String[]{"Логи теста","Выйти из аккаунта"},(dialog,which)->{
                if(which==0)shareLogs();
                else new AlertDialog.Builder(this).setTitle("Отключить ChatGPT?")
                    .setMessage("Локальное подключение удалится. При следующем входе "
                            +"потребуется подтверждение аккаунта.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Отключить",(d,w)->auth.signOut((ok,msg)->{
                        LocalLog.event(this,"native_logout");
                        showLogin();
                    })).show();
            }).show());
        header.addView(settings,new LinearLayout.LayoutParams(dp(50),dp(48)));
        root.addView(header);

        LinearLayout tabs=row();tabs.setPadding(dp(14),dp(8),dp(14),dp(7));
        Button translatorTab=button("文  Переводчик",true);
        Button chatTab=button("◉  ИИ-чат",false);
        tabs.addView(translatorTab,new LinearLayout.LayoutParams(0,dp(49),1));
        LinearLayout.LayoutParams chatParams=new LinearLayout.LayoutParams(0,dp(49),1);
        chatParams.leftMargin=dp(8);tabs.addView(chatTab,chatParams);
        root.addView(tabs);

        FrameLayout container=new FrameLayout(this);
        root.addView(container,new LinearLayout.LayoutParams(-1,0,1));
        buildTranslationScreen();buildChatScreen();
        container.addView(translationScreen,new FrameLayout.LayoutParams(-1,-1));
        container.addView(chatScreen,new FrameLayout.LayoutParams(-1,-1));
        translatorTab.setOnClickListener(v->{
            if(translating)return;
            translating=true;translationScreen.setVisibility(View.VISIBLE);
            chatScreen.setVisibility(View.GONE);translatorTab.setBackground(shape(BLUE,17));
            translatorTab.setTextColor(Color.WHITE);
            chatTab.setBackground(shape(Color.WHITE,17));chatTab.setTextColor(INK);
        });
        chatTab.setOnClickListener(v->{
            if(!translating)return;
            translating=false;translationScreen.setVisibility(View.GONE);
            chatScreen.setVisibility(View.VISIBLE);chatTab.setBackground(shape(BLUE,17));
            chatTab.setTextColor(Color.WHITE);
            translatorTab.setBackground(shape(Color.WHITE,17));
            translatorTab.setTextColor(INK);
        });
        chatScreen.setVisibility(View.GONE);
        updatePhotoLabels();
    }

    private void buildTranslationScreen(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        translationScreen=column();
        translationScreen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=column();
        body.setPadding(dp(14),dp(10),dp(14),dp(22));
        scroll.addView(body);

        LinearLayout languages=row();
        TextView from=text("Русский",16,INK,true);
        TextView to=text("中文",16,INK,true);
        Button swap=button("⇄",false);
        languages.setPadding(dp(15),dp(10),dp(15),dp(10));
        languages.setBackground(shape(Color.WHITE,18));
        languages.addView(from,new LinearLayout.LayoutParams(0,dp(44),1));
        languages.addView(swap,new LinearLayout.LayoutParams(dp(65),dp(44)));
        languages.addView(to,new LinearLayout.LayoutParams(0,dp(44),1));
        to.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        from.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        body.addView(languages);
        swap.setOnClickListener(v->{
            ruToZh=!ruToZh;
            from.setText(ruToZh?"Русский":"中文");
            to.setText(ruToZh?"中文":"Русский");
            LocalLog.event(this,"native_direction_swapped");
        });

        TextView sourceLabel=text("ИСХОДНЫЙ ТЕКСТ",12,MUTED,true);
        sourceLabel.setPadding(dp(8),dp(22),0,dp(8));body.addView(sourceLabel);
        LinearLayout sourceCard=column();
        sourceCard.setPadding(dp(15),dp(10),dp(15),dp(14));
        sourceCard.setBackground(shape(Color.WHITE,20));
        translationInput=new EditText(this);
        translationInput.setTextSize(19);translationInput.setTextColor(INK);
        translationInput.setHint("Напиши, продиктуй или добавь фото");
        translationInput.setBackgroundColor(Color.TRANSPARENT);
        translationInput.setGravity(Gravity.TOP);
        translationInput.setMinLines(3);translationInput.setMaxLines(8);
        sourceCard.addView(translationInput,new LinearLayout.LayoutParams(-1,dp(130)));
        LinearLayout tools=row();
        Button mic=button("🎙 Голос",false);mic.setOnClickListener(v->recognizeSpeech());
        Button image=button("＋ Фото",false);image.setOnClickListener(v->chooseImage());
        tools.addView(mic,new LinearLayout.LayoutParams(0,dp(48),1));
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(48),1);
        right.leftMargin=dp(8);tools.addView(image,right);
        sourceCard.addView(tools);
        photoLabel=text("",12,MUTED,false);
        photoLabel.setPadding(0,dp(8),0,0);sourceCard.addView(photoLabel);
        photoRemoveButton=button("Убрать фото",false);
        photoRemoveButton.setOnClickListener(v->{attachedImage=null;updatePhotoLabels();});
        sourceCard.addView(photoRemoveButton,new LinearLayout.LayoutParams(-1,dp(38)));
        body.addView(sourceCard);

        translationButton=button("Перевести",true);
        LinearLayout.LayoutParams translateLp=new LinearLayout.LayoutParams(-1,dp(56));
        translateLp.topMargin=dp(14);body.addView(translationButton,translateLp);
        translationButton.setOnClickListener(v->submitTranslation());

        TextView translatedLabel=text("РЕЗУЛЬТАТ",12,MUTED,true);
        translatedLabel.setPadding(dp(8),dp(24),0,dp(8));body.addView(translatedLabel);
        LinearLayout resultCard=column();
        resultCard.setPadding(dp(17),dp(15),dp(17),dp(14));
        resultCard.setBackground(shape(Color.WHITE,20));
        translationOutput=text("Здесь появится перевод, пиньинь и объяснение.",19,INK,false);
        translationOutput.setMinHeight(dp(132));
        resultCard.addView(translationOutput);
        LinearLayout resultTools=row();
        Button copy=button("⧉ Копировать",false);
        copy.setOnClickListener(v->copyAnswer());
        Button listen=button("🔊 Прослушать",false);
        listen.setOnClickListener(v->speakAnswer());
        resultTools.addView(copy,new LinearLayout.LayoutParams(0,dp(48),1));
        LinearLayout.LayoutParams listenLp=new LinearLayout.LayoutParams(0,dp(48),1);
        listenLp.leftMargin=dp(8);resultTools.addView(listen,listenLp);
        resultCard.addView(resultTools);
        body.addView(resultCard);
        translationNote=text("",12,MUTED,false);
        translationNote.setPadding(dp(8),dp(10),dp(8),0);body.addView(translationNote);
    }

    private void buildChatScreen(){
        chatScreen=column();chatScreen.setPadding(dp(12),dp(4),dp(12),dp(10));
        chatScroll=new ScrollView(this);
        messages=column();messages.setPadding(dp(5),dp(5),dp(5),dp(20));
        chatScroll.addView(messages);
        chatScreen.addView(chatScroll,new LinearLayout.LayoutParams(-1,0,1));
        addBubble("Спроси что угодно. Можно прикрепить фотографию, "
            +"перевести надпись или продолжить разговор.",false);
        chatPhotoLabel=text("",12,MUTED,false);
        chatScreen.addView(chatPhotoLabel);
        chatInput=new EditText(this);chatInput.setTextSize(17);
        chatInput.setHint("Напиши сообщение…");
        chatInput.setSingleLine(false);chatInput.setMinLines(2);chatInput.setMaxLines(5);
        chatInput.setPadding(dp(14),dp(10),dp(14),dp(10));
        chatInput.setBackground(shape(Color.WHITE,18));
        chatScreen.addView(chatInput,new LinearLayout.LayoutParams(-1,dp(88)));
        LinearLayout actions=row();actions.setPadding(0,dp(8),0,0);
        Button attach=button("＋ Фото",false);attach.setOnClickListener(v->chooseImage());
        Button mic=button("🎙",false);mic.setOnClickListener(v->recognizeSpeech());
        chatSendButton=button("Отправить",true);
        chatSendButton.setOnClickListener(v->submitChat());
        actions.addView(attach,new LinearLayout.LayoutParams(0,dp(48),1));
        LinearLayout.LayoutParams mLp=new LinearLayout.LayoutParams(dp(59),dp(48));
        mLp.leftMargin=dp(7);actions.addView(mic,mLp);
        LinearLayout.LayoutParams sendLp=new LinearLayout.LayoutParams(0,dp(48),1.2f);
        sendLp.leftMargin=dp(7);actions.addView(chatSendButton,sendLp);
        chatScreen.addView(actions);
        chatRemoveButton=button("Убрать выбранное фото",false);
        chatRemoveButton.setOnClickListener(v->{attachedImage=null;updatePhotoLabels();});
        chatScreen.addView(chatRemoveButton);
    }

    private void submitTranslation(){
        if(busy)return;
        String source=translationInput.getText().toString().trim();
        if(source.isEmpty()&&attachedImage==null){toast("Сначала введи текст или выбери фото");return;}
        if(source.length()>8000){toast("Текст слишком длинный");return;}
        String instructions=ruToZh ?
            "Ты профессиональный переводчик с русского на упрощённый китайский. "
            +"Строго дай: 1) естественный перевод, 2) пиньинь с тонами, "
            +"3) короткое объяснение на русском. Если есть фото, распознай и переведи текст на нём. "
            +"Не выдумывай неразборчивые символы." :
            "Ты профессиональный переводчик с китайского на русский. "
            +"Строго дай: 1) перевод, 2) пиньинь оригинала с тонами, "
            +"3) короткое объяснение на русском. Если есть фото, переведи текст с фото. "
            +"Отмечай сомнительные иероглифы.";
        Uri image=attachedImage;
        translationOutput.setText("Перевожу…");
        translationNote.setText("");
        setBusy(true);
        io.execute(()->{
            try{
                String encoded=image==null?null:encodeImage(image);
                runOnUiThread(()->auth.ask(instructions,null,source,encoded,
                    new ChatAuth.StreamListener(){
                        public void onModel(String model){lastModel=model;}
                        public void onDelta(String partial){translationOutput.setText(partial);}
                        public void onComplete(String output){
                            lastTranslation=output;
                            translationOutput.setText(output);
                            translationNote.setText("Модель: "+lastModel);
                            attachedImage=null;updatePhotoLabels();setBusy(false);
                        }
                        public void onError(String error){
                            translationOutput.setText("Ошибка: "+error);
                            setBusy(false);
                        }
                    }));
            }catch(Exception e){
                LocalLog.event(this,"photo_prepare_failed_"+e.getClass().getSimpleName());
                runOnUiThread(()->{translationOutput.setText("Не удалось прочитать фото");setBusy(false);});
            }
        });
    }

    private void submitChat(){
        if(busy)return;
        String userText=chatInput.getText().toString().trim();
        if(userText.isEmpty()&&attachedImage==null){toast("Напиши сообщение или прикрепи фото");return;}
        if(userText.length()>8000){toast("Сообщение слишком длинное");return;}
        Uri image=attachedImage;
        String display=(image==null?"":"📷 Фото\n")+userText;
        addBubble(display,true);
        chatInput.setText("");
        attachedImage=null;updatePhotoLabels();
        TextView answer=addBubble("Думаю…",false);
        JSONArray prior=chatHistory;
        setBusy(true);
        io.execute(()->{
            try{
                String encoded=image==null?null:encodeImage(image);
                runOnUiThread(()->auth.ask(
                    "Ты умный, внимательный ассистент. Отвечай на русском, "
                    +"если пользователь не просит другой язык. Анализируй изображения, "
                    +"описывай видимое и честно говори о неопределённости.",
                    prior,userText,encoded,new ChatAuth.StreamListener(){
                        public void onModel(String model){lastModel=model;}
                        public void onDelta(String partial){
                            answer.setText(partial);
                            scrollChatToBottom();
                        }
                        public void onComplete(String output){
                            answer.setText(output);
                            try {
                                chatHistory.put(new JSONObject().put("role","user")
                                        .put("content",userText.isEmpty() ?
                                            "Вопрос к приложенной фотографии" : userText));
                                chatHistory.put(new JSONObject().put("role","assistant")
                                        .put("content",output));
                            }catch(Exception ignored){}
                            setBusy(false);scrollChatToBottom();
                        }
                        public void onError(String error){
                            answer.setText("Ошибка: "+error);
                            setBusy(false);
                        }
                    }));
            }catch(Exception e){
                LocalLog.event(this,"chat_photo_failed_"+e.getClass().getSimpleName());
                runOnUiThread(()->{answer.setText("Не удалось прочитать фото");setBusy(false);});
            }
        });
    }

    private TextView addBubble(String content,boolean user){
        LinearLayout wrapper=row();
        wrapper.setGravity(user?Gravity.RIGHT:Gravity.LEFT);
        TextView bubble=text(content,17,user?Color.WHITE:INK,false);
        bubble.setPadding(dp(14),dp(12),dp(14),dp(12));
        bubble.setBackground(shape(user?BLUE:Color.WHITE,17));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                (int)(getResources().getDisplayMetrics().widthPixels*0.79f),-2);
        lp.topMargin=dp(9);wrapper.addView(bubble,lp);
        messages.addView(wrapper);
        scrollChatToBottom();
        bubble.setOnLongClickListener(v->{copyText(bubble.getText().toString());return true;});
        return bubble;
    }
    private void scrollChatToBottom(){
        if(chatScroll!=null)chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));
    }
    private void chooseImage(){
        try{
            Intent pick=new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(pick,"Выбрать фотографию"),REQUEST_IMAGE);
            LocalLog.event(this,"photo_picker_opened");
        }catch(Exception e){toast("Выбор фото недоступен");}
    }
    private String encodeImage(Uri uri)throws Exception{
        BitmapFactory.Options b=new BitmapFactory.Options();
        b.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            BitmapFactory.decodeStream(in,null,b);
        }
        if(b.outWidth<=0||b.outHeight<=0)throw new IllegalArgumentException("Invalid image");
        int sample=1;
        while(Math.max(b.outWidth/sample,b.outHeight/sample)>1536)sample*=2;
        b=new BitmapFactory.Options();b.inSampleSize=sample;
        Bitmap bitmap;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            bitmap=BitmapFactory.decodeStream(in,null,b);
        }
        if(bitmap==null)throw new IllegalArgumentException("Unreadable image");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG,78,bytes);
        bitmap.recycle(); // strips EXIF / GPS metadata
        if(bytes.size()>4_500_000)throw new IllegalArgumentException("Photo too large");
        LocalLog.event(this,"photo_encoded");
        return "data:image/jpeg;base64,"+
            Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
    }
    private void updatePhotoLabels(){
        boolean selected=attachedImage!=null;
        if(photoLabel!=null)photoLabel.setText(selected?"📷 Фото выбрано":"");
        if(chatPhotoLabel!=null)chatPhotoLabel.setText(selected?"📷 Фото прикреплено":"");
        if(photoRemoveButton!=null)photoRemoveButton.setVisibility(selected?View.VISIBLE:View.GONE);
        if(chatRemoveButton!=null)chatRemoveButton.setVisibility(selected?View.VISIBLE:View.GONE);
    }
    private void setBusy(boolean value){
        busy=value;
        if(translationButton!=null)translationButton.setEnabled(!value);
        if(chatSendButton!=null)chatSendButton.setEnabled(!value);
    }
    private void recognizeSpeech(){
        try{
            Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,translating?(ruToZh?"ru-RU":"zh-CN"):"ru-RU");
            startActivityForResult(i,REQUEST_VOICE);
            LocalLog.event(this,"speech_started");
        }catch(Exception e){toast("На телефоне нет сервиса распознавания");}
    }
    @Override protected void onActivityResult(int req,int resultCode,Intent data){
        super.onActivityResult(req,resultCode,data);
        if(resultCode!=RESULT_OK||data==null)return;
        if(req==REQUEST_IMAGE){
            Uri uri=data.getData();
            if(uri!=null){attachedImage=uri;updatePhotoLabels();LocalLog.event(this,"photo_selected");}
        }else if(req==REQUEST_VOICE){
            ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(words!=null&&!words.isEmpty()){
                if(translating){translationInput.setText(words.get(0));submitTranslation();}
                else{chatInput.setText(words.get(0));submitChat();}
            }
        }
    }

    private void speakAnswer(){
        String s=lastTranslation.trim();
        if(s.isEmpty()){toast("Сначала получи перевод");return;}
        if(!ttsReady){toast("Системный голос недоступен");return;}
        String spoken=s;
        if(ruToZh){
            String[] lines=s.split("\\r?\\n");
            if(lines.length>0)spoken=lines[0].replaceFirst("^[0-9).\\s]*(翻译|Перевод)?[:：]?\\s*","");
        }
        int code=tts.setLanguage(ruToZh?Locale.SIMPLIFIED_CHINESE:new Locale("ru","RU"));
        if(code==TextToSpeech.LANG_MISSING_DATA||code==TextToSpeech.LANG_NOT_SUPPORTED){
            toast("Установи системный голос для нужного языка");return;
        }
        tts.speak(spoken,TextToSpeech.QUEUE_FLUSH,null,"translate_ai");
        LocalLog.event(this,"tts_started");
    }
    private void copyAnswer(){
        if(lastTranslation.trim().isEmpty()){toast("Сначала получи перевод");return;}
        copyText(lastTranslation);
    }
    private void copyText(String value){
        ClipboardManager mgr=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(mgr!=null){mgr.setPrimaryClip(ClipData.newPlainText("Translate AI",value));toast("Скопировано");}
    }
    private void shareLogs(){
        Intent intent=new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT,"Translate AI: тестовые логи");
        intent.putExtra(Intent.EXTRA_TEXT,LocalLog.read(this));
        startActivity(Intent.createChooser(intent,"Отправить журнал"));
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    private TextView text(String value,int size,int color,boolean bold){
        TextView view=new TextView(this);view.setText(value);
        view.setTextSize(size);view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }
    private Button button(String value,boolean primary){
        Button b=new Button(this);b.setText(value);
        b.setTextSize(13);b.setAllCaps(false);
        b.setTextColor(primary?Color.WHITE:INK);
        b.setBackground(shape(primary?BLUE:Color.WHITE,17));
        b.setElevation(dp(1));return b;
    }
    private GradientDrawable shape(int color,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout column(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private LinearLayout row(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);return l;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){
        auth.destroy();io.shutdownNow();
        if(tts!=null)tts.shutdown();
        super.onDestroy();
    }
}
