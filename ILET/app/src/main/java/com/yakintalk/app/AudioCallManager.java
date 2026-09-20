package com.yakintalk.app;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Process;
public final class AudioCallManager {
    public interface Sender{void send(byte[] pcm,int length);}
    private static final int SAMPLE_RATE=16000;
    private static final int FRAME_BYTES=1280;
    private final Context context;
    private final AudioManager audioManager;
    private volatile boolean active;
    private boolean speakerEnabled;
    private AudioRecord recorder;
    private AudioTrack player;
    private Thread captureThread;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    public AudioCallManager(Context c){context=c.getApplicationContext();audioManager=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);}
    public boolean isActive(){return active;}
    public boolean isSpeakerEnabled(){return speakerEnabled;}
    public synchronized boolean start(Sender sender){
        if(active)return true;
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false;
        int inMin=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        int outMin=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(inMin<=0||outMin<=0)return false;
        recorder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(Math.max(inMin*2,FRAME_BYTES*4)).build();
        player=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(Math.max(outMin*2,FRAME_BYTES*8)).setTransferMode(AudioTrack.MODE_STREAM).build();
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED||player.getState()!=AudioTrack.STATE_INITIALIZED){stop();return false;}
        if(AcousticEchoCanceler.isAvailable()){echoCanceler=AcousticEchoCanceler.create(recorder.getAudioSessionId());if(echoCanceler!=null)echoCanceler.setEnabled(true);}
        if(NoiseSuppressor.isAvailable()){noiseSuppressor=NoiseSuppressor.create(recorder.getAudioSessionId());if(noiseSuppressor!=null)noiseSuppressor.setEnabled(true);}
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        setSpeaker(false);
        player.play();recorder.startRecording();active=true;
        captureThread=new Thread(()->{
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            byte[] frame=new byte[FRAME_BYTES];
            while(active){
                int read=recorder.read(frame,0,frame.length,AudioRecord.READ_BLOCKING);
                if(read>0&&active)sender.send(frame,read);
            }
        },"ILET-AudioCapture");
        captureThread.start();
        return true;
    }
    public synchronized void setSpeaker(boolean enabled){
        speakerEnabled=enabled;
        try{
            if(Build.VERSION.SDK_INT>=31){
                if(enabled){
                    for(AudioDeviceInfo d:audioManager.getAvailableCommunicationDevices()){
                        if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){audioManager.setCommunicationDevice(d);return;}
                    }
                }else audioManager.clearCommunicationDevice();
            }else audioManager.setSpeakerphoneOn(enabled);
        }catch(Exception ignored){}
    }
    public void play(byte[] pcm){
        AudioTrack local=player;
        if(!active||local==null||pcm==null||pcm.length==0)return;
        local.write(pcm,0,pcm.length,AudioTrack.WRITE_NON_BLOCKING);
    }
    public synchronized void stop(){
        active=false;setSpeaker(false);
        if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}}
        if(captureThread!=null){captureThread.interrupt();captureThread=null;}
        if(echoCanceler!=null){echoCanceler.release();echoCanceler=null;}
        if(noiseSuppressor!=null){noiseSuppressor.release();noiseSuppressor=null;}
        if(recorder!=null){recorder.release();recorder=null;}
        if(player!=null){try{player.stop();}catch(Exception ignored){}player.release();player=null;}
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }
}
