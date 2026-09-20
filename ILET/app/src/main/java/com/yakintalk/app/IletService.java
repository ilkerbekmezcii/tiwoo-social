package com.yakintalk.app;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
public final class IletService extends Service {
    public static final int FOREGROUND_ID=4100;
    public static void start(Context c){
        Intent i=new Intent(c,IletService.class);
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i); else c.startService(i);
    }
    @Override public void onCreate(){
        super.onCreate();
        try {
            Notification n=IletNotifications.get(this).buildServiceNotification();
            if(Build.VERSION.SDK_INT>=29) {
                startForeground(FOREGROUND_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(FOREGROUND_ID,n);
            }
            IletRuntime.get(this).start();
        } catch (Throwable t) {
            stopSelf();
        }
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        try {
            IletRuntime.get(this).start();
            return START_NOT_STICKY;
        } catch (Throwable t) {
            stopSelf();
            return START_NOT_STICKY;
        }
    }
    @Override public void onDestroy(){IletRuntime.get(this).stop(); super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
