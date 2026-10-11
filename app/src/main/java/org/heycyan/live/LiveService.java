package org.heycyan.live;

import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.net.wifi.p2p.*;
import android.os.*;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.oudmon.ble.base.bluetooth.*;
import com.oudmon.ble.base.communication.LargeDataHandler;
import com.oudmon.ble.base.communication.bigData.resp.*;
import java.util.Locale;

public class LiveService extends Service {
 public static volatile boolean running;
 public static volatile String status="Live stream is off.";
 public static volatile String metrics="No frames received.";
 private final Handler handler=new Handler(Looper.getMainLooper());
 private LargeDataHandler data;
 private BleOperateManager ble;
 private WifiP2pManager p2p;
 private WifiP2pManager.Channel channel;
 private PowerManager.WakeLock wake;
 private MjpegServer server;
 private RtspFrames video;
 private boolean closed,registered,ready,liveCommand,connecting,groupFormed;
 private String address,host;
 private long frames;
 private static boolean sdkInitialized;
 private final Runnable timeout=()->fail("Connection timed out. Check glasses power, Wi-Fi and Location, and close other glasses apps.");
 private final Runnable frameTimeout=()->fail("No fresh video frames for 20 seconds. Restart the stream.");
 private final Runnable heartbeat=new Runnable() { public void run() { if(closed) return; try { data.syncHeartBeat(4); } catch(Exception e) { fail("Bluetooth heartbeat failed: "+e.getMessage()); return; } handler.postDelayed(this,5000); }};
 private final QCBluetoothCallbackCloneReceiver bleReceiver=new QCBluetoothCallbackCloneReceiver() {
  @Override public void connectStatue(BluetoothDevice device,boolean connected) { handler.post(()->{ if(!closed && ready && !connected) fail("Bluetooth disconnected. Check the glasses and restart."); }); }
  @Override public void onServiceDiscovered() { handler.post(()->{
   if(closed || ready) return; ready=true; data.initEnable(); ble.setReady(true);
   data.addOutDeviceListener(101,new GlassesDeviceNotifyListener() {
    @Override public void parseData(int command,GlassesDeviceNotifyRsp response) {
     byte[] bytes=response==null?null:response.getLoadData(); if(bytes==null || bytes.length<7) return; bytes=bytes.clone(); final byte[] packet=bytes;
     handler.post(()->{
      if(closed) return; int event=packet[6]&255;
      if(event==8 && packet.length>=11) { host=(packet[7]&255)+"."+(packet[8]&255)+"."+(packet[9]&255)+"."+(packet[10]&255); android.util.Log.i("HeyCyanLive","Glasses IP: "+host); maybeVideo(); }
      else if(event==9) android.util.Log.w("HeyCyanLive","Glasses error notification: "+java.util.Arrays.toString(packet));
      else if(event==11 && packet.length>=8) android.util.Log.i("HeyCyanLive","Reported temperature: "+(packet[7]&255));
     });
    }
   });
   handler.postDelayed(()->{ if(!closed) startLive(); },1500);
  }); }
 };
 private final BroadcastReceiver wifiReceiver=new BroadcastReceiver() {
  @Override public void onReceive(Context c,Intent intent) {
   if(closed) return;
   if(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(intent.getAction())) peers();
   if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(intent.getAction())) p2p.requestConnectionInfo(channel,info->{
    if(closed) return;
    groupFormed=info.groupFormed;
    if(groupFormed) { if(host==null && !info.isGroupOwner && info.groupOwnerAddress!=null) host=info.groupOwnerAddress.getHostAddress(); maybeVideo(); }
    else if(video!=null) fail("Wi-Fi Direct disconnected. Restart the stream.");
   });
  }
 };
 @Override public IBinder onBind(Intent intent) { return null; }
 @Override public int onStartCommand(Intent intent,int flags,int id) {
  if(intent==null || "STOP".equals(intent.getAction())) { status="Live stream is off."; stopSelf(); return START_NOT_STICKY; }
  if(running) return START_NOT_STICKY; running=true; metrics="Waiting for the first frame.";
  getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("live","Glasses live stream",NotificationManager.IMPORTANCE_LOW));
  startForeground(1,notification("Connecting to glasses"));
  try {
   address=intent.getStringExtra("address"); if(address==null || !BluetoothDeviceCheck.valid(address)) throw new IllegalArgumentException("No valid glasses selected");
   wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"heycyan:live"); wake.acquire();
   server=new MjpegServer(8080);
   data=LargeDataHandler.getInstance(); ble=BleOperateManager.getInstance(getApplication()); ble.setApplication(getApplication()); if(!sdkInitialized) { ble.init(); sdkInitialized=true; }
   BleBaseControl.getInstance(this).setmContext(getApplication());
   LocalBroadcastManager.getInstance(this).registerReceiver(bleReceiver,BleAction.getIntentFilter()); registered=true;
   p2p=getSystemService(WifiP2pManager.class); if(p2p==null) throw new IllegalStateException("Wi-Fi Direct unavailable");
   channel=p2p.initialize(this,handler.getLooper(),()->fail("Wi-Fi Direct channel lost."));
   IntentFilter filter=new IntentFilter(); filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION); filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
   androidx.core.content.ContextCompat.registerReceiver(this,wifiReceiver,filter,androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
   update("Connecting to glasses over Bluetooth."); handler.postDelayed(timeout,60000); ble.connectDirectly(address);
  } catch(Exception e) { fail("Could not start: "+e.getMessage()); }
  return START_NOT_STICKY;
 }
 private void startLive() {
  update("Starting live preview. Waiting for Wi-Fi Direct; approve Android's connection prompt if shown.");
  try {
   p2p.discoverPeers(channel,new WifiP2pManager.ActionListener() { public void onSuccess() { peers(); } public void onFailure(int reason) { fail("Wi-Fi Direct discovery failed ("+reason+"). Turn on Wi-Fi and Location."); }});
   liveCommand=true;
   // The older SDK does not parse work types 20/21. IP notification and decoded frames establish success.
   data.glassesControl(new byte[]{2,1,0x14,1},(command,response)->{});
   handler.post(heartbeat);
  } catch(SecurityException e) { fail("Allow Nearby devices and precise Location in Android app settings."); }
  catch(Exception e) { fail("Live command failed: "+e.getMessage()); }
 }
 private void peers() {
  if(closed || connecting || !liveCommand) return;
  try { p2p.requestPeers(channel,list->{
   if(closed || connecting) return; String suffix=address.replace(":",""); suffix=suffix.substring(suffix.length()-4).toUpperCase(Locale.US);
   for(WifiP2pDevice peer:list.getDeviceList()) {
    String name=peer.deviceName==null?"":peer.deviceName;
    android.util.Log.i("HeyCyanLive","P2P peer: "+name+" "+peer.deviceAddress);
    if(!name.toUpperCase(Locale.US).contains(suffix) && !peer.deviceAddress.equalsIgnoreCase(address)) continue;
    // Prefer the phone as group owner, matching the live-stream report's proven topology.
    connecting=true; WifiP2pConfig config=new WifiP2pConfig(); config.deviceAddress=peer.deviceAddress; config.wps.setup=0; config.groupOwnerIntent=15;
    try { p2p.connect(channel,config,new WifiP2pManager.ActionListener() { public void onSuccess() {} public void onFailure(int reason) { fail("Wi-Fi Direct connection failed ("+reason+"). Retry with other glasses apps closed."); }}); }
    catch(SecurityException e) { fail("Wi-Fi Direct permission was revoked."); } return;
   }
  }); } catch(SecurityException e) { fail("Allow Nearby devices and precise Location in Android app settings."); }
  catch(Exception e) { fail("Wi-Fi Direct discovery: "+e.getMessage()); }
 }
 private void maybeVideo() {
  if(closed || !groupFormed || host==null || video!=null) return;
  update("Wi-Fi Direct connected. Opening live video.");
  video=new RtspFrames(host,new RtspFrames.Listener() {
   public void frame(byte[] jpeg) { handler.post(()->{
    if(closed) return; server.publish(jpeg,"frame-"+(++frames)+".jpg"); metrics="Fresh frames: "+frames+". 1600 by 1200 pixels. Latest JPEG: "+jpeg.length+" bytes.";
    handler.removeCallbacks(timeout); handler.removeCallbacks(frameTimeout); handler.postDelayed(frameTimeout,20000);
    if(frames==1) update("Live stream is running. You can now switch to another app.");
    if(frames==1 || frames%10==0) android.util.Log.i("HeyCyanLive",metrics);
   }); }
   public void error(String text) { handler.post(()->fail(text)); }
  }); video.start();
 }
 private Notification notification(String text) {
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,LiveService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
  return new Notification.Builder(this,"live").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("HeyCyan Live").setContentText(text).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop live stream",stop).build()).build();
 }
 private void update(String text) { if(closed) return; status=text; android.util.Log.i("HeyCyanLive",text); getSystemService(NotificationManager.class).notify(1,notification(text)); }
 private void fail(String text) { if(closed) return; status="Error: "+text; android.util.Log.e("HeyCyanLive",status); stopSelf(); }
 @Override public void onDestroy() {
  closed=true; running=false; handler.removeCallbacksAndMessages(null);
  if(video!=null) video.close(); if(server!=null) server.close();
  if(liveCommand && data!=null) try { data.glassesControl(new byte[]{2,1,0x15,1},(c,r)->{}); } catch(Exception ignored) {}
  if(data!=null) data.removeOutDeviceListener(101);
  if(registered) LocalBroadcastManager.getInstance(this).unregisterReceiver(bleReceiver);
  try { unregisterReceiver(wifiReceiver); } catch(IllegalArgumentException ignored) {}
  if(p2p!=null && channel!=null) { try { p2p.stopPeerDiscovery(channel,null); p2p.cancelConnect(channel,null); p2p.removeGroup(channel,null); } catch(SecurityException ignored) {} channel.close(); }
  // Give the BLE write time to leave the SDK queue before disconnecting.
  if(ble!=null) { BleOperateManager manager=ble; new Handler(Looper.getMainLooper()).postDelayed(()->{ if(!running) { manager.setNeedConnect(false); manager.setReady(false); manager.disconnect(); } },700); }
  if(wake!=null && wake.isHeld()) wake.release(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
 }
 private static class BluetoothDeviceCheck { static boolean valid(String address) { return android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address); } }
}
