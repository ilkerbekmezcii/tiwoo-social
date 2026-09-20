package com.yakintalk.app;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
public final class IletRuntime implements WifiDirectController.Listener {
    private static volatile IletRuntime instance;
    public static IletRuntime get(Context c){
        if(instance==null)synchronized(IletRuntime.class){if(instance==null)instance=new IletRuntime(c.getApplicationContext());}
        return instance;
    }
    private static final class PendingChat{
        final String id;final String message;
        PendingChat(String id,String message){this.id=id;this.message=message;}
    }
    private final WifiDirectController wifi;
    private final IletNotifications notifications;
    private final List<PendingChat> pendingChats=new ArrayList<>();
    private WifiDirectController.Listener uiListener;
    private String pendingIncomingCallEndpoint;
    private String lastStatus="Hazırlanıyor";
    private IletRuntime(Context c){wifi=new WifiDirectController(c,this);notifications=IletNotifications.get(c);}
    public synchronized void attachUi(WifiDirectController.Listener l){
        uiListener=l;l.onPeersChanged(wifi.getPeers());l.onStatus(lastStatus);
        if(!pendingChats.isEmpty()){List<PendingChat> copy=new ArrayList<>(pendingChats);pendingChats.clear();for(PendingChat p:copy)l.onChatMessage(p.id,p.message);}
        if(pendingIncomingCallEndpoint!=null)l.onControl(pendingIncomingCallEndpoint,"CALL_INVITE","");
    }
    public synchronized void detachUi(WifiDirectController.Listener l){if(uiListener==l)uiListener=null;}
    public void start(){wifi.start();}
    public void stop(){wifi.stop();}
    public List<Peer> getPeers(){return wifi.getPeers();}
    public boolean isConnected(String id){return wifi.isConnected(id);}
    public String getDisplayName(){return wifi.getDisplayName();}
    public void setDisplayName(String name){wifi.setDisplayName(name);}
    public void sendChat(String id,String text){wifi.sendChat(id,text);}
    public void sendAudio(String id,byte[] pcm,int len){wifi.sendAudio(id,pcm,len);}
    public synchronized void sendControl(String id,String type,String value){
        if("CALL_ACCEPT".equals(type)||"CALL_REJECT".equals(type)||"CALL_END".equals(type)){
            if(id!=null&&id.equals(pendingIncomingCallEndpoint))pendingIncomingCallEndpoint=null;
            notifications.stopIncomingCall();
        }
        wifi.sendControl(id,type,value);
    }
    public synchronized void stopIncomingAlert(){pendingIncomingCallEndpoint=null;notifications.stopIncomingCall();}
    @Override public synchronized void onPeersChanged(List<Peer> p){if(uiListener!=null)uiListener.onPeersChanged(p);}
    @Override public synchronized void onChatMessage(String id,String message){
        notifications.showMessage(id,peerName(id));
        if(uiListener!=null)uiListener.onChatMessage(id,message);
        else{if(pendingChats.size()>=100)pendingChats.remove(0);pendingChats.add(new PendingChat(id,message));}
    }
    @Override public synchronized void onControl(String id,String type,String value){
        if("CALL_INVITE".equals(type)){pendingIncomingCallEndpoint=id;notifications.startIncomingCall(peerName(id));}
        else if("CALL_REJECT".equals(type)||"CALL_END".equals(type)){
            if(id!=null&&id.equals(pendingIncomingCallEndpoint)){pendingIncomingCallEndpoint=null;notifications.stopIncomingCall();}
        }
        if(uiListener!=null)uiListener.onControl(id,type,value);
    }
    @Override public synchronized void onAudio(String id,byte[] pcm){if(uiListener!=null)uiListener.onAudio(id,pcm);}
    @Override public synchronized void onStatus(String s){lastStatus=s;if(uiListener!=null)uiListener.onStatus(s);}
    private String peerName(String id){for(Peer p:wifi.getPeers())if(p.endpointId.equals(id))return p.name;return "Yakındaki cihaz";}
}
