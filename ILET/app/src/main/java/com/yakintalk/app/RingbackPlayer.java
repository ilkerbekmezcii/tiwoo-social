package com.yakintalk.app;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
public final class RingbackPlayer {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ToneGenerator tone;
    private boolean active;
    private final Runnable cycle=new Runnable(){
        @Override public void run(){
            if(!active)return;
            try{
                if(tone==null)tone=new ToneGenerator(AudioManager.STREAM_VOICE_CALL,75);
                tone.startTone(ToneGenerator.TONE_SUP_RINGTONE,1200);
            }catch(Exception ignored){}
            handler.postDelayed(this,3600);
        }
    };
    public void start(){ if(active)return; active=true; handler.post(cycle); }
    public void stop(){
        active=false; handler.removeCallbacks(cycle);
        if(tone!=null){try{tone.stopTone();}catch(Exception ignored){} try{tone.release();}catch(Exception ignored){} tone=null;}
    }
}
