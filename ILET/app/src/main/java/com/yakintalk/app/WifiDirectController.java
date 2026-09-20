package com.yakintalk.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WifiDirectController {
    public interface Listener {
        void onPeersChanged(List<Peer> peers);
        void onChatMessage(String endpointId, String message);
        void onControl(String endpointId, String type, String value);
        void onAudio(String endpointId, byte[] pcm);
        void onStatus(String status);
    }

    private static final String SERVICE_INSTANCE = "ilet";
    private static final String SERVICE_TYPE = "_ilet._tcp";
    private static final int PORT = 8988;

    private static final byte TYPE_HELLO = 1;
    private static final byte TYPE_PRESENCE = 2;
    private static final byte TYPE_CHAT = 3;
    private static final byte TYPE_CONTROL = 4;
    private static final byte TYPE_AUDIO = 5;

    private final Context context;
    private final Listener listener;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<String, WifiP2pDevice> devicesByStableId = new HashMap<>();
    private final Map<String, String> stableIdByDeviceAddress = new HashMap<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    private final String localStableId;
    private String displayName;
    private boolean running;
    private boolean receiverRegistered;
    private boolean groupOwner;
    private Connection ownerConnection;
    private ServerSocket serverSocket;
    private Thread serverThread;

    private final Runnable discoveryLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            discoverServices();
            handler.postDelayed(this, 25000);
        }
    };

    public WifiDirectController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager == null ? null : manager.initialize(context, Looper.getMainLooper(), null);

        android.content.SharedPreferences prefs =
                context.getSharedPreferences("identity", Context.MODE_PRIVATE);
        String id = prefs.getString("peer_id", null);
        if (id == null) {
            byte[] random = new byte[6];
            new SecureRandom().nextBytes(random);
            StringBuilder sb = new StringBuilder();
            for (byte b : random) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            id = sb.toString();
            prefs.edit().putString("peer_id", id).apply();
        }
        localStableId = id;

        String saved = prefs.getString("display_name", null);
        if (saved == null || saved.matches("Kullanıcı [A-F0-9a-f]{4}")) {
            saved = defaultDeviceName();
            prefs.edit().putString("display_name", saved).apply();
        }
        displayName = saved;
    }

    private String defaultDeviceName() {
        String name = null;
        try {
            name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (Exception ignored) {}
        if (name == null || name.trim().isEmpty()) name = Build.MODEL;
        if (name == null || name.trim().isEmpty()) name = "Android";
        name = name.trim();
        if (name.length() > 24) name = name.substring(0, 24);
        return name;
    }

    public String getDisplayName() { return displayName; }

    private boolean hasWifiPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureWifiPermission() {
        if (hasWifiPermission()) return true;
        listener.onStatus("Yakındaki Wi‑Fi cihaz izni gerekli");
        return false;
    }

    public void setDisplayName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 24) clean = clean.substring(0, 24);
        displayName = clean;
        context.getSharedPreferences("identity", Context.MODE_PRIVATE)
                .edit().putString("display_name", clean).apply();
        if (running) registerLocalService();
    }

    public synchronized void start() {
        if (running) return;
        if (manager == null || channel == null) {
            listener.onStatus("Wi‑Fi Direct bu cihazda kullanılamıyor");
            return;
        }
        if (!ensureWifiPermission()) return;
        running = true;
        registerReceiver();
        setupServiceDiscovery();
        handler.post(discoveryLoop);
        listener.onStatus("Yalnızca Wi‑Fi ile yakındaki cihazlar aranıyor");
    }

    public synchronized void stop() {
        running = false;
        handler.removeCallbacks(discoveryLoop);
        closeServer();
        for (Connection c : new ArrayList<>(connections.values())) c.close();
        connections.clear();
        ownerConnection = null;
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        if (manager != null && channel != null) {
            try { manager.clearLocalServices(channel, null); } catch (Exception ignored) {}
            try { manager.clearServiceRequests(channel, null); } catch (Exception ignored) {}
        }
    }

    public synchronized List<Peer> getPeers() {
        List<Peer> out = new ArrayList<>(peers.values());
        Collections.sort(out, Comparator
                .comparing((Peer p) -> !p.connected)
                .thenComparing(p -> p.name.toLowerCase(Locale.ROOT)));
        return out;
    }

    public synchronized boolean isConnected(String stableId) {
        Peer p = peers.get(stableId);
        return p != null && p.connected;
    }

    public void sendChat(String id, String text) {
        sendFrame(TYPE_CHAT, id, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendControl(String id, String type, String value) {
        String body = type + "|" + (value == null ? "" : value);
        sendFrame(TYPE_CONTROL, id, body.getBytes(StandardCharsets.UTF_8));
    }

    public void sendAudio(String id, byte[] pcm, int length) {
        if (pcm == null || length <= 0) return;
        byte[] payload = new byte[length];
        System.arraycopy(pcm, 0, payload, 0, length);
        sendFrame(TYPE_AUDIO, id, payload);
    }

    private void sendFrame(byte type, String destination, byte[] payload) {
        Connection direct = connections.get(destination);
        if (direct != null) {
            direct.send(type, localStableId, destination, payload);
            return;
        }
        Connection owner = ownerConnection;
        if (!groupOwner && owner != null) {
            owner.send(type, localStableId, destination, payload);
        } else {
            listener.onStatus("Wi‑Fi veri kanalı henüz hazır değil");
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    @SuppressLint("MissingPermission")
    private void setupServiceDiscovery() {
        if (!ensureWifiPermission()) return;
        try {
        manager.setDnsSdResponseListeners(channel,
                (instanceName, registrationType, srcDevice) -> {},
                (fullDomain, txtRecordMap, srcDevice) -> {
                    String id = txtRecordMap.get("id");
                    String name = txtRecordMap.get("name");
                    if (id == null || id.equals(localStableId)) return;
                    if (name == null || name.trim().isEmpty()) name = "Yakındaki cihaz";

                    synchronized (WifiDirectController.this) {
                        Peer p = peers.get(id);
                        if (p == null) {
                            p = new Peer(id, id, name);
                            peers.put(id, p);
                        }
                        p.name = name;
                        p.discovered = true;
                        p.lastSeenMs = System.currentTimeMillis();
                        devicesByStableId.put(id, srcDevice);
                        stableIdByDeviceAddress.put(srcDevice.deviceAddress, id);
                    }
                    publishPeers();
                    maybeConnect(id, srcDevice);
                });

        registerLocalService();

        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                WifiP2pDnsSdServiceRequest request =
                        WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE);
                manager.addServiceRequest(channel, request, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { discoverServices(); }
                    @Override public void onFailure(int reason) {
                        listener.onStatus("Wi‑Fi servis taraması hazırlanamadı: " + reason);
                    }
                });
            }
            @Override public void onFailure(int reason) {
                listener.onStatus("Wi‑Fi servis taraması hazırlanamadı: " + reason);
            }
        });
        } catch (SecurityException e) {
            listener.onStatus("Yakındaki Wi‑Fi cihaz izni gerekli");
            running = false;
        }
    }

    @SuppressLint("MissingPermission")
    private void registerLocalService() {
        if (manager == null || channel == null || !ensureWifiPermission()) return;
        Map<String, String> record = new HashMap<>();
        record.put("id", localStableId);
        record.put("name", displayName);
        record.put("v", "1");
        WifiP2pDnsSdServiceInfo service =
                WifiP2pDnsSdServiceInfo.newInstance(SERVICE_INSTANCE, SERVICE_TYPE, record);

        try {
        manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                manager.addLocalService(channel, service, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() {}
                    @Override public void onFailure(int reason) {
                        listener.onStatus("İLET Wi‑Fi yayını başlatılamadı: " + reason);
                    }
                });
            }
            @Override public void onFailure(int reason) {}
        });
        } catch (SecurityException e) {
            listener.onStatus("Yakındaki Wi‑Fi cihaz izni gerekli");
        }
    }

    @SuppressLint("MissingPermission")
    private void discoverServices() {
        if (!running || manager == null || channel == null || !ensureWifiPermission()) return;
        try {
        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {}
            @Override public void onFailure(int reason) {
                if (reason != WifiP2pManager.BUSY) {
                    listener.onStatus("Wi‑Fi taraması başlatılamadı: " + reason);
                }
            }
        });
        } catch (SecurityException e) {
            listener.onStatus("Yakındaki Wi‑Fi cihaz izni gerekli");
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void maybeConnect(String id, WifiP2pDevice device) {
        if (!ensureWifiPermission()) return;
        Peer peer = peers.get(id);
        if (peer != null && peer.connected) return;
        if (connections.containsKey(id)) return;
        if (localStableId.compareTo(id) >= 0) return;

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;

        try {
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                listener.onStatus("İzole Wi‑Fi bağlantısı kuruluyor");
            }
            @Override public void onFailure(int reason) {
                listener.onStatus("Wi‑Fi bağlantısı kurulamadı: " + reason);
            }
        });
        } catch (SecurityException e) {
            listener.onStatus("Yakındaki Wi‑Fi cihaz izni gerekli");
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    listener.onStatus("Wi‑Fi Direct kapalı");
                }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (ni != null && ni.isConnected()) {
                    if (ensureWifiPermission()) {
                        try {
                            manager.requestConnectionInfo(channel, WifiDirectController.this::handleConnectionInfo);
                        } catch (SecurityException e) {
                            listener.onStatus("Yakındaki Wi‑Fi cihaz izni gerekli");
                        }
                    }
                } else {
                    markAllDisconnected();
                    closeServer();
                }
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void handleConnectionInfo(WifiP2pInfo info) {
        if (!info.groupFormed) return;
        groupOwner = info.isGroupOwner;
        if (groupOwner) {
            startServer();
            listener.onStatus("İzole Wi‑Fi grubu hazır");
        } else if (info.groupOwnerAddress != null && ensureWifiPermission()) {
            try {
                manager.requestGroupInfo(channel, group -> connectToOwner(info.groupOwnerAddress, group));
            } catch (SecurityException e) {
                listener.onStatus("Yakındaki Wi‑Fi cihaz izni gerekli");
            }
        }
    }

    private synchronized void connectToOwner(InetAddress address, WifiP2pGroup group) {
        if (ownerConnection != null && ownerConnection.isOpen()) return;
        String expected = null;
        if (group != null && group.getOwner() != null) {
            expected = stableIdByDeviceAddress.get(group.getOwner().deviceAddress);
        }
        final String expectedId = expected;
        new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(address, PORT), 8000);
                Connection conn = new Connection(socket, expectedId);
                ownerConnection = conn;
                conn.start();
            } catch (Exception e) {
                listener.onStatus("Wi‑Fi veri kanalı kurulamadı");
            }
        }, "ILET-WifiClient").start();
    }

    private synchronized void startServer() {
        if (serverThread != null && serverThread.isAlive()) return;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (running && !serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    new Connection(socket, null).start();
                }
            } catch (Exception ignored) {}
        }, "ILET-WifiServer");
        serverThread.start();
    }

    private synchronized void closeServer() {
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        serverThread = null;
    }

    private synchronized void markAllDisconnected() {
        for (Peer p : peers.values()) p.connected = false;
        for (Connection c : new ArrayList<>(connections.values())) c.close();
        connections.clear();
        ownerConnection = null;
        publishPeers();
    }

    private synchronized void publishPeers() {
        listener.onPeersChanged(getPeers());
    }

    private synchronized void onPeerHello(Connection conn, String id, String name) {
        conn.remoteStableId = id;
        connections.put(id, conn);

        Peer p = peers.get(id);
        if (p == null) {
            p = new Peer(id, id, name);
            peers.put(id, p);
        }
        p.name = name;
        p.connected = true;
        p.discovered = true;
        p.lastSeenMs = System.currentTimeMillis();

        if (groupOwner) {
            conn.sendPresence(localStableId, displayName, true);
            for (Peer peer : peers.values()) {
                if (!peer.stableId.equals(id) && peer.connected) {
                    conn.sendPresence(peer.stableId, peer.name, true);
                }
            }
            for (Connection other : connections.values()) {
                if (other != conn) other.sendPresence(id, name, true);
            }
        }
        publishPeers();
    }

    private synchronized void onConnectionClosed(Connection conn) {
        String id = conn.remoteStableId;
        if (id != null) {
            if (connections.get(id) == conn) connections.remove(id);
            Peer p = peers.get(id);
            if (p != null) p.connected = false;
            if (groupOwner) {
                for (Connection other : connections.values()) {
                    other.sendPresence(id, p == null ? "Yakındaki cihaz" : p.name, false);
                }
            }
        }
        if (ownerConnection == conn) ownerConnection = null;
        publishPeers();
    }

    private void routeOrDeliver(Connection incoming, byte type, String source,
                                String destination, byte[] payload) {
        if (destination.equals(localStableId) || "*".equals(destination)) {
            deliver(type, source, payload);
            return;
        }
        if (groupOwner) {
            Connection target = connections.get(destination);
            if (target != null && target != incoming) {
                target.send(type, source, destination, payload);
            }
        }
    }

    private void deliver(byte type, String source, byte[] payload) {
        if (type == TYPE_PRESENCE) {
            String body = new String(payload, StandardCharsets.UTF_8);
            String[] parts = body.split("\\|", 3);
            if (parts.length == 3) {
                synchronized (this) {
                    Peer p = peers.get(parts[0]);
                    if (p == null) {
                        p = new Peer(parts[0], parts[0], parts[1]);
                        peers.put(parts[0], p);
                    }
                    p.name = parts[1];
                    p.connected = "1".equals(parts[2]);
                    p.discovered = p.connected;
                    p.lastSeenMs = System.currentTimeMillis();
                }
                publishPeers();
            }
        } else if (type == TYPE_CHAT) {
            listener.onChatMessage(source, new String(payload, StandardCharsets.UTF_8));
        } else if (type == TYPE_CONTROL) {
            String body = new String(payload, StandardCharsets.UTF_8);
            int split = body.indexOf('|');
            String control = split >= 0 ? body.substring(0, split) : body;
            String value = split >= 0 ? body.substring(split + 1) : "";
            listener.onControl(source, control, value);
        } else if (type == TYPE_AUDIO) {
            listener.onAudio(source, payload);
        }
    }

    private final class Connection {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final Object writeLock = new Object();
        private volatile boolean open = true;
        private volatile String remoteStableId;

        Connection(Socket socket, String expectedId) throws IOException {
            this.socket = socket;
            remoteStableId = expectedId;
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 32768));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 32768));
        }

        boolean isOpen() { return open && !socket.isClosed(); }

        void start() {
            send(TYPE_HELLO, localStableId, "*", displayName.getBytes(StandardCharsets.UTF_8));
            new Thread(this::readLoop, "ILET-WifiReader").start();
        }

        void sendPresence(String id, String name, boolean connected) {
            String body = id + "|" + name.replace('|', ' ') + "|" + (connected ? "1" : "0");
            send(TYPE_PRESENCE, localStableId, "*", body.getBytes(StandardCharsets.UTF_8));
        }

        void send(byte type, String source, String destination, byte[] payload) {
            if (!isOpen()) return;
            synchronized (writeLock) {
                try {
                    out.writeByte(type);
                    out.writeUTF(source == null ? "" : source);
                    out.writeUTF(destination == null ? "" : destination);
                    out.writeInt(payload == null ? 0 : payload.length);
                    if (payload != null && payload.length > 0) out.write(payload);
                    out.flush();
                } catch (IOException e) {
                    close();
                }
            }
        }

        void readLoop() {
            try {
                while (isOpen()) {
                    byte type = in.readByte();
                    String source = in.readUTF();
                    String destination = in.readUTF();
                    int len = in.readInt();
                    if (len < 0 || len > 1024 * 1024) throw new IOException("bad frame");
                    byte[] payload = new byte[len];
                    in.readFully(payload);

                    if (type == TYPE_HELLO) {
                        onPeerHello(this, source, new String(payload, StandardCharsets.UTF_8));
                    } else {
                        routeOrDeliver(this, type, source, destination, payload);
                    }
                }
            } catch (EOFException ignored) {
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        void close() {
            if (!open) return;
            open = false;
            try { socket.close(); } catch (Exception ignored) {}
            onConnectionClosed(this);
        }
    }
}
