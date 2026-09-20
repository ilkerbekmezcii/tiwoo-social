package com.yakintalk.app;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
public final class IletNotifications {
    private static final String CH_SERVICE="ilet_wifi_background";
    private static final String CH_MESSAGE="ilet_messages_v2";
    private static final String CH_CALL="ilet_calls_v2";
    private static final int CALL_ID=4200;
    private static volatile IletNotifications instance;
    public static IletNotifications get(Context c){
        if(instance==null) synchronized(IletNotifications.class){
            if(instance==null)instance=new IletNotifications(c.getApplicationContext());
        }
        return instance;
    }
    private final Context context;
    private final NotificationManager manager;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ToneGenerator incomingTone;
    private boolean incomingActive;
    private final Runnable incomingCycle=new Runnable(){
        @Override public void run(){
            if(!incomingActive)return;
            try{
                if(incomingTone==null)incomingTone=new ToneGenerator(AudioManager.STREAM_RING,80);
                incomingTone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,650);
            }catch(Exception ignored){}
            handler.postDelayed(this,2100);
        }
    };
    private IletNotifications(Context c){context=c;manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);createChannels();}
    private void createChannels(){
        if(Build.VERSION.SDK_INT<26)return;
        NotificationChannel s=new NotificationChannel(CH_SERVICE,"İLET Wi‑Fi bağlantısı",NotificationManager.IMPORTANCE_MIN);
        s.setSound(null,null);s.setShowBadge(false);manager.createNotificationChannel(s);
        Uri sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes a=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build();
        NotificationChannel m=new NotificationChannel(CH_MESSAGE,"Mesajlar",NotificationManager.IMPORTANCE_HIGH);
        m.enableVibration(true);m.setSound(sound,a);manager.createNotificationChannel(m);
        NotificationChannel c=new NotificationChannel(CH_CALL,"Aramalar",NotificationManager.IMPORTANCE_HIGH);
        c.setSound(null,null);c.enableVibration(true);c.setVibrationPattern(new long[]{0,450,450,450,900});manager.createNotificationChannel(c);
    }
    public Notification buildServiceNotification(){
        return builder(CH_SERVICE).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("İLET").setContentText("Wi‑Fi üzerinden yakındaki cihazlar aranıyor")
            .setContentIntent(mainIntent()).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_MIN).setShowWhen(false).build();
    }
    public void showMessage(String id,String sender){
        Notification n=builder(CH_MESSAGE).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(sender).setContentText("Yeni mesaj").setContentIntent(mainIntent())
            .setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Build.VERSION.SDK_INT<26?Notification.DEFAULT_SOUND|Notification.DEFAULT_VIBRATE:0).build();
        manager.notify(5000+Math.abs(id.hashCode()%1000),n);
    }
    public synchronized void startIncomingCall(String caller){
        stopIncomingCall();
        Notification n=builder(CH_CALL).setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Gelen İLET araması").setContentText(caller+" sizi arıyor").setContentIntent(mainIntent())
            .setOngoing(true).setCategory(Notification.CATEGORY_CALL).setPriority(Notification.PRIORITY_MAX).build();
        manager.notify(CALL_ID,n); incomingActive=true; handler.post(incomingCycle);
    }
    public synchronized void stopIncomingCall(){
        incomingActive=false;handler.removeCallbacks(incomingCycle);manager.cancel(CALL_ID);
        if(incomingTone!=null){try{incomingTone.stopTone();}catch(Exception ignored){}try{incomingTone.release();}catch(Exception ignored){}incomingTone=null;}
    }
    private Notification.Builder builder(String ch){return Build.VERSION.SDK_INT>=26?new Notification.Builder(context,ch):new Notification.Builder(context);}
    private PendingIntent mainIntent(){
        Intent i=new Intent(context,MainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int f=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=23)f|=PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context,77,i,f);
    }
}
