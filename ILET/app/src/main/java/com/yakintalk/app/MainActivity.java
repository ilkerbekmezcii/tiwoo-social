package com.yakintalk.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity implements WifiDirectController.Listener {
    private static final int PERMISSION_REQUEST = 40;
    private static final int BG = Color.rgb(246,247,251);
    private static final int SURFACE = Color.WHITE;
    private static final int TEXT = Color.rgb(25,29,38);
    private static final int MUTED = Color.rgb(116,123,138);
    private static final int BORDER = Color.rgb(228,232,240);
    private static final int ACCENT = Color.rgb(91,92,226);
    private static final int ACCENT_DARK = Color.rgb(67,67,202);
    private static final int ACCENT_SOFT = Color.rgb(239,239,255);
    private static final int GREEN = Color.rgb(35,162,109);
    private static final int RED = Color.rgb(225,74,86);
    private static final int DARK = Color.rgb(20,23,36);

    private LinearLayout root;
    private IletRuntime runtime;
    private AudioCallManager audio;
    private RingbackPlayer ringback;
    private List<Peer> peers = new ArrayList<>();
    private final Map<String,List<String>> chats = new HashMap<>();
    private String status = "Hazırlanıyor";
    private String chatEndpoint;
    private String callEndpoint;
    private boolean callActive;
    private boolean updateCheckedThisSession;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        runtime = IletRuntime.get(this);
        audio = new AudioCallManager(this);
        ringback = new RingbackPlayer();
        requestPermissionsIfNeeded();
        renderHome();
    }

    @Override protected void onStart() {
        super.onStart();
        runtime.attachUi(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasWifiPermission()) IletService.start(this);
        if (AutoUpdater.resumePendingInstall(this)) return;
        if (!updateCheckedThisSession) {
            updateCheckedThisSession = true;
            AutoUpdater.check(this, false);
        }
    }

    @Override protected void onStop() {
        runtime.detachUi(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        ringback.stop();
        audio.stop();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (callEndpoint != null) { stopCall(true); return; }
        if (chatEndpoint != null) { chatEndpoint = null; renderHome(); return; }
        super.onBackPressed();
    }

    private void requestPermissionsIfNeeded() {
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
            wanted.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else {
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        List<String> missing = new ArrayList<>();
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
    }

    private boolean hasWifiPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != PERMISSION_REQUEST) return;

        if (hasWifiPermission()) {
            status = "Yakındaki cihazlar yalnızca Wi‑Fi ile aranıyor";
            IletService.start(this);
        } else {
            status = "Yakındaki Wi‑Fi cihaz izni gerekli";
        }
        renderCurrent();
    }

    private void renderCurrent() {
        if (callEndpoint != null) renderCall();
        else if (chatEndpoint != null) renderChat(chatEndpoint);
        else renderHome();
    }

    private void prepareLight() {
        root.setGravity(Gravity.NO_GRAVITY);
        root.setBackgroundColor(BG);
        root.removeAllViews();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void renderHome() {
        chatEndpoint = null;
        prepareLight();

        LinearLayout header = row();
        header.setPadding(dp(20),dp(18),dp(18),dp(10));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("İLET",28,TEXT,true));
        TextView subtitle = text("Yakındaki cihazlarla izole Wi‑Fi iletişimi",13,MUTED,false);
        subtitle.setPadding(0,dp(3),0,0);
        titles.addView(subtitle);
        header.addView(titles,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));

        TextView update = circleAction("↻");
        update.setContentDescription("Güncellemeleri kontrol et");
        update.setOnClickListener(v -> AutoUpdater.check(this, true));
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(dp(44),dp(44));
        updateLp.setMargins(0,0,dp(8),0);
        header.addView(update,updateLp);

        TextView profile = profileAvatar(initials(runtime.getDisplayName()));
        profile.setContentDescription("Görünen adı değiştir");
        profile.setOnClickListener(v -> editName());
        header.addView(profile,new LinearLayout.LayoutParams(dp(54),dp(54)));
        root.addView(header);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18),dp(17),dp(18),dp(17));
        hero.setBackground(gradient());

        hero.addView(text("●  " + status,13,Color.WHITE,true));

        TextView title = text("Sadece Wi‑Fi. İnternet gerekmez.",21,Color.WHITE,true);
        title.setPadding(0,dp(14),0,dp(4));
        hero.addView(title);

        TextView body = text("Modeme bağlı olsanız bile İLET verisi ayrı peer-to-peer Wi‑Fi grubunda tutulur. Bluetooth kullanılmaz.",13,Color.rgb(228,229,255),false);
        body.setLineSpacing(0,1.12f);
        hero.addView(body);

        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1,-2);
        heroLp.setMargins(dp(18),dp(6),dp(18),dp(18));
        root.addView(hero,heroLp);

        TextView section = text("YAKINDAKİLER",12,MUTED,true);
        section.setPadding(dp(20),0,dp(20),dp(8));
        root.addView(section);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18),0,dp(18),dp(26));
        scroll.addView(list);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        if (peers.isEmpty()) {
            TextView empty = text("Yakında başka İLET cihazı görünmüyor.",15,MUTED,false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20),dp(32),dp(20),dp(32));
            empty.setBackground(stroked(SURFACE,BORDER,20));
            list.addView(empty);
        } else {
            for (Peer p : peers) list.addView(peerCard(p),spaced(6));
        }
    }

    private View peerCard(Peer p) {
        LinearLayout box = row();
        box.setPadding(dp(14),dp(14),dp(12),dp(14));
        box.setBackground(stroked(SURFACE,BORDER,20));

        TextView avatar = text(initials(p.name),16,p.connected?ACCENT_DARK:MUTED,true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(round(p.connected?ACCENT_SOFT:Color.rgb(239,241,245),30));
        box.addView(avatar,new LinearLayout.LayoutParams(dp(52),dp(52)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12),0,dp(6),0);
        details.addView(text(p.name,16,TEXT,true));

        TextView state = text(p.connected ? "●  Wi‑Fi bağlı" : "●  Yakında",12,p.connected?GREEN:MUTED,false);
        state.setPadding(0,dp(5),0,0);
        details.addView(state);
        box.addView(details,new LinearLayout.LayoutParams(0,-2,1));

        LinearLayout actions = row();
        Button msg = button("Mesaj",false);
        Button call = button("Ara",true);
        msg.setEnabled(p.connected);
        call.setEnabled(p.connected);
        msg.setOnClickListener(v -> renderChat(p.endpointId));
        call.setOnClickListener(v -> placeCall(p.endpointId));

        actions.addView(msg,new LinearLayout.LayoutParams(dp(70),dp(40)));
        Space gap = new Space(this);
        actions.addView(gap,new LinearLayout.LayoutParams(dp(6),1));
        actions.addView(call,new LinearLayout.LayoutParams(dp(58),dp(40)));
        box.addView(actions);

        return box;
    }

    private void renderChat(String id) {
        Peer p = findPeer(id);
        if (p == null) {
            chatEndpoint = null;
            renderHome();
            return;
        }

        chatEndpoint = id;
        prepareLight();

        LinearLayout top = row();
        top.setPadding(dp(12),dp(10),dp(12),dp(10));
        top.setBackgroundColor(SURFACE);

        Button back = button("‹",false);
        back.setTextSize(26);
        back.setOnClickListener(v -> {
            chatEndpoint = null;
            renderHome();
        });
        top.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.addView(text(p.name,16,TEXT,true));
        title.addView(text(p.connected?"●  Wi‑Fi bağlı":"Bağlantı bekleniyor",11,p.connected?GREEN:MUTED,false));
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));

        Button call = button("Ara",true);
        call.setOnClickListener(v -> placeCall(id));
        top.addView(call,new LinearLayout.LayoutParams(dp(68),dp(44)));
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        LinearLayout messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(14),dp(16),dp(14),dp(16));

        for (String line : chats.computeIfAbsent(id,k -> new ArrayList<>())) {
            boolean mine = line.startsWith("Ben: ");
            String clean = mine ? line.substring(5) : stripSender(line);

            LinearLayout holder = new LinearLayout(this);
            holder.setGravity(mine?Gravity.END:Gravity.START);

            TextView bubble = text(clean,15,mine?Color.WHITE:TEXT,false);
            bubble.setPadding(dp(14),dp(10),dp(14),dp(10));
            bubble.setBackground(round(mine?ACCENT:SURFACE,17));
            bubble.setMaxWidth(dp(285));
            holder.addView(bubble);
            messages.addView(holder,spaced(4));
        }

        scroll.addView(messages);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout composer = row();
        composer.setPadding(dp(12),dp(8),dp(12),dp(12));
        composer.setBackgroundColor(SURFACE);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Mesaj yaz…");
        input.setPadding(dp(16),0,dp(16),0);
        input.setBackground(stroked(Color.rgb(248,249,252),BORDER,18));
        composer.addView(input,new LinearLayout.LayoutParams(0,dp(50),1));

        Space gap = new Space(this);
        composer.addView(gap,new LinearLayout.LayoutParams(dp(8),1));

        Button send = button("Gönder",true);
        send.setOnClickListener(v -> {
            String m = input.getText().toString().trim();
            if (m.isEmpty()) return;
            runtime.sendChat(id,m);
            chats.get(id).add("Ben: " + m);
            input.setText("");
            renderChat(id);
        });
        composer.addView(send,new LinearLayout.LayoutParams(dp(88),dp(50)));
        root.addView(composer);
    }

    private void placeCall(String id) {
        if (!runtime.isConnected(id)) {
            Toast.makeText(this,"Wi‑Fi bağlantısı henüz hazır değil",Toast.LENGTH_SHORT).show();
            return;
        }
        callEndpoint = id;
        callActive = false;
        ringback.start();
        runtime.sendControl(id,"CALL_INVITE","");
        renderCall();
    }

    private void renderCall() {
        root.removeAllViews();
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(DARK);
        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);
        getWindow().getDecorView().setSystemUiVisibility(0);

        Peer p = findPeer(callEndpoint);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER_HORIZONTAL);
        screen.setPadding(dp(28),dp(34),dp(28),dp(34));

        screen.addView(text("İLET • WI‑FI",11,Color.rgb(148,153,177),true));

        TextView avatar = text(initials(p==null?"?":p.name),32,Color.WHITE,true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(round(ACCENT,60));
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(108),dp(108));
        avatarLp.setMargins(0,dp(42),0,dp(22));
        screen.addView(avatar,avatarLp);

        TextView name = text(p==null?"Yakındaki cihaz":p.name,27,Color.WHITE,true);
        name.setGravity(Gravity.CENTER);
        screen.addView(name);

        TextView state = text(callActive?"●  Görüşme aktif":"Çalıyor…",14,
                callActive?Color.rgb(101,231,170):Color.rgb(177,182,203),true);
        state.setPadding(0,dp(10),0,dp(38));
        screen.addView(state);

        Button speaker = button(audio.isSpeakerEnabled()?"Hoparlör açık":"Hoparlör",false);
        speaker.setTextColor(Color.WHITE);
        speaker.setBackground(round(Color.rgb(49,54,76),22));
        speaker.setOnClickListener(v -> {
            audio.setSpeaker(!audio.isSpeakerEnabled());
            renderCall();
        });
        screen.addView(speaker,new LinearLayout.LayoutParams(dp(180),dp(50)));

        Button end = button("Aramayı bitir",false);
        end.setTextColor(Color.WHITE);
        end.setBackground(round(RED,26));
        end.setOnClickListener(v -> stopCall(true));
        LinearLayout.LayoutParams endLp = new LinearLayout.LayoutParams(dp(190),dp(56));
        endLp.setMargins(0,dp(16),0,0);
        screen.addView(end,endLp);

        root.addView(screen,new LinearLayout.LayoutParams(-1,-2));
    }

    private void incomingCall(String id) {
        if (callEndpoint != null) {
            runtime.sendControl(id,"CALL_REJECT","busy");
            return;
        }

        ringback.stop();
        Peer p = findPeer(id);

        new AlertDialog.Builder(this)
                .setTitle("Gelen arama")
                .setMessage((p==null?"Yakındaki cihaz":p.name) + " sizi arıyor.")
                .setNegativeButton("Reddet",(d,w) -> runtime.sendControl(id,"CALL_REJECT",""))
                .setPositiveButton("Yanıtla",(d,w) -> {
                    callEndpoint = id;
                    callActive = true;
                    runtime.sendControl(id,"CALL_ACCEPT","");
                    if (!startAudio(id)) {
                        runtime.sendControl(id,"CALL_END","audio");
                        stopCall(false);
                    } else {
                        renderCall();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private boolean startAudio(String id) {
        return audio.start((pcm,len) -> runtime.sendAudio(id,pcm,len));
    }

    private void stopCall(boolean notify) {
        String id = callEndpoint;
        if (notify && id != null && runtime.isConnected(id)) {
            runtime.sendControl(id,"CALL_END","");
        }
        ringback.stop();
        runtime.stopIncomingAlert();
        audio.stop();
        callEndpoint = null;
        callActive = false;
        renderHome();
    }

    @Override public void onPeersChanged(List<Peer> value) {
        runOnUiThread(() -> {
            peers = value;
            if (callEndpoint != null && !runtime.isConnected(callEndpoint)) {
                stopCall(false);
                Toast.makeText(this,"Wi‑Fi bağlantısı kesildi",Toast.LENGTH_SHORT).show();
            } else {
                renderCurrent();
            }
        });
    }

    @Override public void onChatMessage(String id,String message) {
        runOnUiThread(() -> {
            Peer p = findPeer(id);
            chats.computeIfAbsent(id,k -> new ArrayList<>())
                    .add((p==null?"Kişi":p.name) + ": " + message);

            if (id.equals(chatEndpoint) && callEndpoint == null) {
                renderChat(id);
            } else {
                Toast.makeText(this,"Yeni mesaj",Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override public void onControl(String id,String type,String value) {
        runOnUiThread(() -> {
            switch (type) {
                case "CALL_INVITE":
                    incomingCall(id);
                    break;
                case "CALL_ACCEPT":
                    ringback.stop();
                    if (id.equals(callEndpoint) && !callActive) {
                        callActive = true;
                        if (!startAudio(id)) stopCall(true);
                        else renderCall();
                    }
                    break;
                case "CALL_REJECT":
                    if (id.equals(callEndpoint)) {
                        Toast.makeText(this,"Arama reddedildi",Toast.LENGTH_SHORT).show();
                        stopCall(false);
                    }
                    break;
                case "CALL_END":
                    if (id.equals(callEndpoint)) stopCall(false);
                    break;
            }
        });
    }

    @Override public void onAudio(String id,byte[] pcm) {
        if (id.equals(callEndpoint) && callActive) audio.play(pcm);
    }

    @Override public void onStatus(String value) {
        runOnUiThread(() -> {
            status = value;
            if (chatEndpoint == null && callEndpoint == null) renderHome();
        });
    }

    private void editName() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(runtime.getDisplayName());
        input.setSelection(input.length());

        new AlertDialog.Builder(this)
                .setTitle("Görünen ad")
                .setMessage("Varsayılan ad cihaz adıdır. İsterseniz değiştirebilirsiniz.")
                .setView(input)
                .setNegativeButton("Vazgeç",null)
                .setPositiveButton("Kaydet",(d,w) -> {
                    runtime.setDisplayName(input.getText().toString());
                    renderHome();
                })
                .show();
    }

    private Peer findPeer(String id) {
        if (id == null) return null;
        for (Peer p : peers) if (id.equals(p.endpointId)) return p;
        return null;
    }

    private String stripSender(String line) {
        int i = line.indexOf(": ");
        return i >= 0 ? line.substring(i + 2) : line;
    }

    private String initials(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return "?";
        String[] parts = clean.split("\\s+");
        if (parts.length == 1) return parts[0].substring(0,1).toUpperCase(Locale.ROOT);
        return (parts[0].substring(0,1) + parts[parts.length-1].substring(0,1)).toUpperCase(Locale.ROOT);
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private LinearLayout.LayoutParams spaced(int vertical) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(vertical),0,dp(vertical));
        return p;
    }

    private TextView profileAvatar(String label) {
        TextView v = text(label,14,ACCENT_DARK,true);
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setPadding(0,0,0,0);
        v.setBackground(stroked(ACCENT_SOFT,Color.rgb(215,216,250),30));
        return v;
    }

    private TextView circleAction(String label) {
        TextView v = text(label,22,ACCENT_DARK,true);
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setPadding(0,0,0,0);
        v.setBackground(stroked(SURFACE,BORDER,30));
        return v;
    }

    private Button button(String label,boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(primary?Color.WHITE:TEXT);
        b.setPadding(dp(12),0,dp(12),0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setBackground(round(primary?ACCENT:Color.rgb(238,240,245),16));
        return b;
    }

    private TextView text(String value,int sp,int color,boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans",Typeface.BOLD));
        return v;
    }

    private GradientDrawable round(int color,int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable stroked(int color,int stroke,int radius) {
        GradientDrawable d = round(color,radius);
        d.setStroke(dp(1),stroke);
        return d;
    }

    private GradientDrawable gradient() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(101,102,236),Color.rgb(68,69,197)});
        d.setCornerRadius(dp(24));
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
