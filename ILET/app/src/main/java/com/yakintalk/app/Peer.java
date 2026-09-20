package com.yakintalk.app;
public final class Peer {
    public final String endpointId;
    public String stableId;
    public String name;
    public boolean discovered;
    public boolean connected;
    public long lastSeenMs;
    public Peer(String endpointId,String stableId,String name){
        this.endpointId=endpointId; this.stableId=stableId; this.name=name;
        this.discovered=true; this.connected=false; this.lastSeenMs=System.currentTimeMillis();
    }
}
