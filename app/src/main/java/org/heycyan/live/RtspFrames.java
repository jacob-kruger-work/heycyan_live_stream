package org.heycyan.live;

import android.graphics.*;
import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** RTSP/TCP, RFC 6184 H.264 depacketization and headless MediaCodec decoding. */
final class RtspFrames implements AutoCloseable {
 interface Listener { void frame(byte[] jpeg); void error(String message); }
 private final String host;
 private final Listener listener;
 private volatile boolean closed;
 private volatile Socket socket;
 private DataInputStream input;
 private OutputStream output;
 private int cseq;
 private String session,base;
 private MediaCodec codec;
 private final ByteArrayOutputStream accessUnit=new ByteArrayOutputStream();
 private ByteArrayOutputStream fragment;
 private byte[] sps,pps;
 private int lastSequence=-1;
 private boolean damaged;
 private long timestamp=-1,lastPublished,lastVideo,lastKeepalive;
 private int videoChannel;
 private int rtcpChannel=1;
 private volatile int senderSsrc,highestSequence;
 private int sequenceCycles;
 private final ScheduledExecutorService keepalive=Executors.newSingleThreadScheduledExecutor();
 RtspFrames(String host,Listener listener) { this.host=host; this.listener=listener; base="rtsp://"+host+":8554/ch0"; }
 void start() { Thread t=new Thread(this::run,"heycyan-rtsp"); t.setDaemon(true); t.start(); }
 private void run() {
  try {
   IOException connectionError=null;
   for(int attempt=0;attempt<40 && !closed;attempt++) {
    socket=new Socket();
    try { socket.connect(new InetSocketAddress(host,8554),2000); connectionError=null; break; }
    catch(IOException e) { connectionError=e; socket.close(); if(attempt%5==0) android.util.Log.i("HeyCyanLive","Waiting for glasses RTSP server on "+host+":8554"); Thread.sleep(500); }
   }
   if(closed) return;
   if(connectionError!=null) throw new IOException("Glasses connected over Wi-Fi but their RTSP server did not open port 8554 within the startup window",connectionError);
   socket.setSoTimeout(10000); socket.setTcpNoDelay(true);
   input=new DataInputStream(new BufferedInputStream(socket.getInputStream(),65536)); output=socket.getOutputStream();
   Response description=request("DESCRIBE",base,"Accept: application/sdp\r\n");
   String contentBase=description.headers.get("content-base"); if(contentBase==null) contentBase=base+"/";
   String track=null; boolean video=false;
   for(String line:description.body.split("\r?\n")) {
    if(line.startsWith("m=")) video=line.startsWith("m=video");
    if(video && line.startsWith("a=control:")) track=line.substring(10).trim();
    if(video && line.startsWith("a=fmtp:") && line.contains("sprop-parameter-sets=")) {
     String parameter=line.substring(line.indexOf("sprop-parameter-sets=")+21).split("[; ]",2)[0];
     String[] sets=parameter.split(","); if(sets.length>=2) { sps=Base64.getDecoder().decode(sets[0]); pps=Base64.getDecoder().decode(sets[1]); }
    }
   }
   if(track==null) throw new IOException("RTSP description has no video track");
   String trackUrl=track.startsWith("rtsp://")?track:track.startsWith("/")?"rtsp://"+host+":8554"+track:contentBase+(contentBase.endsWith("/")?"":"/")+track;
   Response setup=request("SETUP",trackUrl,"Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
   session=setup.headers.get("session"); if(session==null) throw new IOException("RTSP SETUP has no session"); session=session.split(";",2)[0];
   String transport=setup.headers.get("transport"); if(transport!=null && transport.contains("interleaved=")) { String[] channels=transport.substring(transport.indexOf("interleaved=")+12).split("[-;]"); videoChannel=Integer.parseInt(channels[0]); if(channels.length>1) rtcpChannel=Integer.parseInt(channels[1]); }
   request("PLAY",base,"Range: npt=0.000-\r\n");
   keepalive.scheduleAtFixedRate(()->{
    if(closed) return;
    try { send("OPTIONS",base,""); receiverReport(); }
    catch(IOException e) { if(!closed) { listener.error("RTSP keep-alive failed: "+e.getMessage()); close(); } }
   },1,2,TimeUnit.SECONDS);
   lastVideo=lastKeepalive=SystemClock.elapsedRealtime();
   while(!closed) {
    readItem(); drain();
    long now=SystemClock.elapsedRealtime();
    if(now-lastVideo>15000) throw new IOException("No video packets received for 15 seconds");
   }
  } catch(Exception e) { if(!closed) { android.util.Log.e("HeyCyanLive","RTSP decoding failed",e); listener.error("Live video: "+e.getClass().getSimpleName()+": "+e.getMessage()); } }
  finally { if(codec!=null) { try { codec.stop(); } catch(Exception ignored) {} codec.release(); } close(); }
 }
 private static final class Response { final Map<String,String> headers=new HashMap<>(); String body=""; int status; }
 private synchronized void send(String method,String url,String headers) throws IOException {
  String message=method+" "+url+" RTSP/1.0\r\nCSeq: "+(++cseq)+"\r\nUser-Agent: HeyCyanLive/1.0\r\n"+(session==null?"":"Session: "+session+"\r\n")+headers+"\r\n";
  output.write(message.getBytes(StandardCharsets.US_ASCII)); output.flush();
 }
 private synchronized void receiverReport() throws IOException {
  if(senderSsrc==0) return;
  // RFC 3550 compound receiver report and source description with CNAME.
  ByteBuffer report=ByteBuffer.allocate(52);
  report.put((byte)0x81).put((byte)201).putShort((short)7).putInt(0x4843594c);
  report.putInt(senderSsrc).putInt(0).putInt(highestSequence).putInt(0).putInt(0).putInt(0);
  report.put((byte)0x81).put((byte)202).putShort((short)4).putInt(0x4843594c);
  report.put((byte)1).put((byte)7).put("heycyan".getBytes(StandardCharsets.US_ASCII)).put((byte)0).putShort((short)0);
  output.write(new byte[]{'$',(byte)rtcpChannel,0,52}); output.write(report.array()); output.flush();
 }
 private Response request(String method,String url,String headers) throws Exception {
  send(method,url,headers); Response response;
  do { response=readItem(); } while(response==null && !closed);
  if(response==null || response.status!=200) throw new IOException(method+" failed: "+(response==null?"closed":response.status));
  return response;
 }
 private Response readItem() throws Exception {
  int first=input.readUnsignedByte();
  if(first=='$') {
   int channel=input.readUnsignedByte(),length=input.readUnsignedShort(); byte[] packet=new byte[length]; input.readFully(packet);
   if(channel==videoChannel) { lastVideo=SystemClock.elapsedRealtime(); rtp(packet); } return null;
  }
  ByteArrayOutputStream header=new ByteArrayOutputStream(); header.write(first); int tail=first;
  while(tail!=0x0d0a0d0a) { int b=input.readUnsignedByte(); header.write(b); tail=(tail<<8)|b; if(header.size()>16384) throw new IOException("RTSP header too large"); }
  String[] lines=header.toString(StandardCharsets.US_ASCII.name()).split("\r\n"); Response response=new Response();
  String[] status=lines[0].split(" "); if(status.length<2 || !status[0].startsWith("RTSP/")) throw new IOException("Unexpected RTSP message"); response.status=Integer.parseInt(status[1]);
  for(int i=1;i<lines.length;i++) { int colon=lines[i].indexOf(':'); if(colon>0) response.headers.put(lines[i].substring(0,colon).trim().toLowerCase(Locale.US),lines[i].substring(colon+1).trim()); }
  int length=Integer.parseInt(response.headers.getOrDefault("content-length","0")); if(length<0 || length>1024*1024) throw new IOException("Invalid RTSP body length");
  byte[] body=new byte[length]; input.readFully(body); response.body=new String(body,StandardCharsets.UTF_8); return response;
 }
 private void rtp(byte[] packet) throws Exception {
  if(packet.length<12 || (packet[0]&0xc0)!=0x80) return;
  int offset=12+4*(packet[0]&15); if(offset>packet.length) return;
  if((packet[0]&16)!=0) { if(offset+4>packet.length) return; int words=((packet[offset+2]&255)<<8)|(packet[offset+3]&255); offset+=4+4*words; }
  int end=packet.length; if((packet[0]&32)!=0) { int padding=packet[end-1]&255; if(padding==0 || padding>end-offset) return; end-=padding; }
  if(offset>=end) return;
  int sequence=((packet[2]&255)<<8)|(packet[3]&255);
  senderSsrc=((packet[8]&255)<<24)|((packet[9]&255)<<16)|((packet[10]&255)<<8)|(packet[11]&255);
  if(lastSequence>60000 && sequence<5000) sequenceCycles+=65536;
  highestSequence=sequenceCycles+sequence;
  long stamp=((long)(packet[4]&255)<<24)|((long)(packet[5]&255)<<16)|((long)(packet[6]&255)<<8)|(packet[7]&255);
  if(timestamp!=stamp) { flush(); timestamp=stamp; damaged=false; fragment=null; }
  if(lastSequence!=-1 && sequence!=((lastSequence+1)&65535)) { damaged=true; fragment=null; }
  lastSequence=sequence;
  int type=packet[offset]&31;
  if(type>=1 && type<=23) nal(Arrays.copyOfRange(packet,offset,end));
  else if(type==24) {
   int p=offset+1; while(p+2<=end) { int n=((packet[p]&255)<<8)|(packet[p+1]&255); p+=2; if(n==0 || p+n>end) { damaged=true; break; } nal(Arrays.copyOfRange(packet,p,p+n)); p+=n; }
  } else if(type==28 && offset+2<end) {
   int flags=packet[offset+1]&255;
   if((flags&128)!=0) { fragment=new ByteArrayOutputStream(); fragment.write((packet[offset]&0xe0)|(flags&31)); }
   if(fragment!=null) { fragment.write(packet,offset+2,end-offset-2); if(fragment.size()>4*1024*1024) throw new IOException("H.264 fragment too large"); if((flags&64)!=0) { nal(fragment.toByteArray()); fragment=null; } }
   else damaged=true;
  }
  if((packet[1]&128)!=0) flush();
 }
 private void nal(byte[] bytes) throws IOException {
  int type=bytes[0]&31; if(type==7) sps=bytes; if(type==8) pps=bytes;
  if(accessUnit.size()+bytes.length>4*1024*1024) throw new IOException("H.264 frame too large");
  accessUnit.write(new byte[]{0,0,0,1}); accessUnit.write(bytes);
 }
 private void flush() throws Exception {
  byte[] bytes=accessUnit.toByteArray(); accessUnit.reset();
  if(bytes.length==0 || damaged || fragment!=null) return;
  if(codec==null) {
   if(sps==null || pps==null) return;
   MediaFormat format=MediaFormat.createVideoFormat("video/avc",1600,1200);
   format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
   format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4*1024*1024);
   format.setByteBuffer("csd-0",annex(sps)); format.setByteBuffer("csd-1",annex(pps));
   codec=MediaCodec.createDecoderByType("video/avc"); codec.configure(format,null,null,0); codec.start();
  }
  drain(); int index=codec.dequeueInputBuffer(10000);
  if(index<0) { drain(); index=codec.dequeueInputBuffer(10000); }
  if(index<0) throw new IOException("Video decoder input stalled");
  ByteBuffer buffer=codec.getInputBuffer(index); if(buffer==null || buffer.capacity()<bytes.length) throw new IOException("Video decoder input too small");
  buffer.clear(); buffer.put(bytes); codec.queueInputBuffer(index,0,bytes.length,SystemClock.elapsedRealtimeNanos()/1000,0); drain();
 }
 private static ByteBuffer annex(byte[] nal) { ByteBuffer buffer=ByteBuffer.allocate(nal.length+4); buffer.putInt(1); buffer.put(nal); buffer.flip(); return buffer; }
 private void drain() throws Exception {
  if(codec==null) return; MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
  while(!closed) {
   int index=codec.dequeueOutputBuffer(info,0);
   if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { android.util.Log.i("HeyCyanLive","Decoder format: "+codec.getOutputFormat()); continue; }
   if(index<0) return;
   try {
    long now=SystemClock.elapsedRealtime();
    if(info.size>0 && (lastPublished==0 || now-lastPublished>=1000)) {
     try(Image image=codec.getOutputImage(index)) {
      if(image==null) throw new IOException("Decoder does not expose YUV images");
      byte[] jpeg=jpeg(image); lastPublished=now; listener.frame(jpeg);
     }
    }
   } finally { codec.releaseOutputBuffer(index,false); }
  }
 }
 private static byte[] jpeg(Image image) throws IOException {
  Rect crop=image.getCropRect(); int w=crop.width(),h=crop.height(); if((w&1)!=0 || (h&1)!=0) throw new IOException("Odd YUV dimensions");
  byte[] nv21=new byte[w*h*3/2]; Image.Plane[] planes=image.getPlanes(); if(planes.length!=3) throw new IOException("Expected YUV420 planes");
  for(int p=0;p<3;p++) {
   int shift=p==0?0:1,pw=w>>shift,ph=h>>shift;
   ByteBuffer buffer=planes[p].getBuffer(); int stride=planes[p].getRowStride(),pixel=planes[p].getPixelStride();
   int origin=buffer.position()+(crop.top>>shift)*stride+(crop.left>>shift)*pixel;
   for(int y=0;y<ph;y++) for(int x=0;x<pw;x++) {
    int target=p==0?y*w+x:w*h+2*(y*pw+x)+(p==1?1:0);
    nv21[target]=buffer.get(origin+y*stride+x*pixel);
   }
  }
  YuvImage yuv=new YuvImage(nv21,ImageFormat.NV21,w,h,null); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  if(w==1600 && h==1200) {
   for(int quality=85;quality>=5;quality-=10) { bytes.reset(); if(!yuv.compressToJpeg(new Rect(0,0,w,h),quality,bytes)) throw new IOException("JPEG conversion failed"); if(bytes.size()<1000000) return bytes.toByteArray(); }
  } else {
   yuv.compressToJpeg(new Rect(0,0,w,h),90,bytes); byte[] raw=bytes.toByteArray(); Bitmap bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length); if(bitmap==null) throw new IOException("Frame resize failed");
   Bitmap scaled=Bitmap.createScaledBitmap(bitmap,1600,1200,true);
   try { for(int quality=85;quality>=5;quality-=10) { bytes.reset(); scaled.compress(Bitmap.CompressFormat.JPEG,quality,bytes); if(bytes.size()<1000000) return bytes.toByteArray(); } }
   finally { if(scaled!=bitmap) scaled.recycle(); bitmap.recycle(); }
  }
  throw new IOException("Could not compress frame below 1 MB");
 }
 @Override public void close() { closed=true; keepalive.shutdownNow(); Socket active=socket; if(active!=null) try { active.close(); } catch(IOException ignored) {} }
}
