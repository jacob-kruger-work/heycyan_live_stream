package org.heycyan.live;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MjpegServerTest {
 static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
 static byte[] jpeg(int color) throws IOException {
  BufferedImage image=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB);
  for(int y=0;y<16;y++) for(int x=0;x<16;x++) image.setRGB(x,y,color);
  ByteArrayOutputStream bytes=new ByteArrayOutputStream(); ImageIO.write(image,"jpg",bytes); return bytes.toByteArray();
 }
 static String line(InputStream input) throws IOException {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int b;
  while((b=input.read())!=-1) {
   if(b==10) return bytes.toString(StandardCharsets.US_ASCII.name()).replace("\r","");
   bytes.write(b);
  }
  throw new EOFException();
 }
 // Reproduce the installed vOICe reader's marker synchronization and growing
 // 100 KB buffer, rather than assuming a conventional multipart parser.
 static byte[] voiceFrame(InputStream input) throws IOException {
  int capacity=100000,headerLimit=256;
  for(int attempt=0;attempt<60;attempt++) {
   int scanned=0;
   while(scanned<capacity && (input.read()!=255 || input.read()!=217)) scanned++;
   if(scanned==capacity) continue;
   scanned=0;
   while(scanned<headerLimit && (input.read()!=255 || input.read()!=216)) scanned++;
   if(scanned==headerLimit) { if(headerLimit<2048) headerLimit*=2; continue; }
   byte[] frame=new byte[capacity]; frame[0]=(byte)255; frame[1]=(byte)216;
   frame[2]=(byte)input.read(); frame[3]=(byte)input.read();
   int size=4; boolean ended=false;
   while(size<capacity-1) {
    int value=input.read(); if(value<0) throw new EOFException();
    frame[size++]=(byte)value;
    if(value==255) {
     int next=input.read(); if(next<0) throw new EOFException();
     frame[size++]=(byte)next;
     if(next==217) { ended=true; break; }
    }
   }
   if(!ended) { capacity*=2; continue; }
   return Arrays.copyOf(frame,size);
  }
  throw new AssertionError("vOICe-style reader failed to synchronize and grow its buffer");
 }
 static final class Client implements AutoCloseable {
  final Socket socket;
  final InputStream input;
  boolean first=true;
  Client(int port) throws IOException {
   this(port,"/mjpeg");
  }
  Client(int port,String path) throws IOException {
   socket=new Socket("127.0.0.1",port); socket.setSoTimeout(2000); input=socket.getInputStream();
   socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
   check(line(input).equals("HTTP/1.1 200 OK"),"HTTP status");
   String header; boolean multipart=false,noCache=false;
   while(!(header=line(input)).isEmpty()) { multipart|=header.contains("multipart/x-mixed-replace; boundary=heycyan-frame"); noCache|=header.contains("no-store"); }
   check(multipart && noCache,"Multipart and cache headers");
  }
  byte[] frame(long sequence,String name) throws IOException {
   if(first) { check(line(input).equals("--heycyan-frame"),"Initial boundary"); first=false; }
   Map<String,String> headers=new HashMap<>(); String header;
   while(!(header=line(input)).isEmpty()) { int colon=header.indexOf(':'); headers.put(header.substring(0,colon),header.substring(colon+1).trim()); }
   check(headers.get("Content-Type").equals("image/jpeg"),"JPEG MIME type");
   check(headers.get("X-Frame-Sequence").equals(Long.toString(sequence)),"Sequence only advances after publish");
   check(headers.get("X-Snapshot-Name").equals(name),"Saved filename");
   int size=Integer.parseInt(headers.get("Content-Length")); byte[] bytes=input.readNBytes(size);
   check(bytes.length==size,"Complete JPEG length"); check(ImageIO.read(new ByteArrayInputStream(bytes))!=null,"JPEG decodes");
   check(line(input).isEmpty(),"JPEG terminator");
   check(line(input).equals("--heycyan-frame"),"Immediate terminating boundary renders frame without needing another save");
   return bytes;
  }
  void idle() throws IOException {
   socket.setSoTimeout(150);
   try { input.read(); throw new AssertionError("No unsolicited or duplicated frame"); }
   catch(SocketTimeoutException expected) {} finally { socket.setSoTimeout(2000); }
  }
  public void close() throws IOException { socket.close(); }
 }
 static final class Response {
  String status;
  final Map<String,String> headers=new HashMap<>();
  byte[] body;
 }
 static Response snapshot(int port,String method,String headers) throws IOException {
  try(Socket socket=new Socket("127.0.0.1",port)) {
   socket.setSoTimeout(2000);
   socket.getOutputStream().write((method+" /snapshot HTTP/1.1\r\nHost: localhost\r\n"+headers+"\r\n").getBytes(StandardCharsets.US_ASCII));
   InputStream input=socket.getInputStream(); Response response=new Response(); response.status=line(input);
   String header;
   while(!(header=line(input)).isEmpty()) { int colon=header.indexOf(':'); response.headers.put(header.substring(0,colon).toLowerCase(Locale.US),header.substring(colon+1).trim()); }
   int length=Integer.parseInt(response.headers.get("content-length")); response.body=input.readNBytes(length);
   check(response.body.length==length,"Snapshot complete Content-Length");
   check(input.read()==-1,"Snapshot response closes its own connection");
   check("no-store".equals(response.headers.get("cache-control")),"Snapshot never cached");
   check("Origin".equals(response.headers.get("vary")),"Snapshot CORS varies by origin");
   return response;
  }
 }
 static void snapshots(byte[] first,byte[] second) throws Exception {
  try(MjpegServer server=new MjpegServer(0); Client primary=new Client(server.port()); Client other=new Client(server.port())) {
   Response empty=snapshot(server.port(),"GET","Origin: https://blindza.com\r\n");
   check(empty.status.equals("HTTP/1.1 503 Service Unavailable"),"Snapshot before first frame returns 503 immediately");
   check(new String(empty.body,StandardCharsets.UTF_8).contains("No frame available"),"503 explains missing frame");
   check("https://blindza.com".equals(empty.headers.get("access-control-allow-origin")),"Allowed browser can read 503");
   primary.idle(); other.idle();
   for(String origin:new String[]{"https://blindza.com","http://localhost"}) {
    Response options=snapshot(server.port(),"OPTIONS","Origin: "+origin+"\r\nAccess-Control-Request-Method: GET\r\nAccess-Control-Request-Headers: content-type\r\nAccess-Control-Request-Private-Network: true\r\n");
    check(options.status.equals("HTTP/1.1 204 No Content") && options.body.length==0,"Preflight succeeds without a frame");
    check(origin.equals(options.headers.get("access-control-allow-origin")),"Preflight reflects exact allowed origin");
    check(options.headers.get("access-control-allow-methods").contains("GET"),"Preflight permits GET");
    check("Content-Type".equals(options.headers.get("access-control-allow-headers")),"Preflight permits requested content type");
    check("true".equals(options.headers.get("access-control-allow-private-network")),"Allowed private-network preflight");
   }
   BlockingQueue<Integer> consumed=new LinkedBlockingQueue<>();
   CompletableFuture<Void> reading=CompletableFuture.runAsync(()->{
    try {
     for(int sequence=1;sequence<=12;sequence++) {
      byte[] expected=sequence%2==0?second:first; String name="frame-"+sequence+".jpg";
      check(Arrays.equals(primary.frame(sequence,name),expected),"Primary MJPEG receives each new frame during snapshot requests");
      check(Arrays.equals(other.frame(sequence,name),expected),"Second MJPEG consumer is unaffected");
      consumed.add(sequence);
     }
    } catch(IOException e) { throw new UncheckedIOException(e); }
   });
   for(int sequence=1;sequence<=12;sequence++) {
    byte[] expected=sequence%2==0?second:first; String name="frame-"+sequence+".jpg";
    byte[] offered=expected.clone(); server.publish(offered,name); Arrays.fill(offered,(byte)0);
    for(String origin:new String[]{"https://blindza.com","http://localhost","https://blindza.com.evil.test",""}) {
     Response response=snapshot(server.port(),"GET",origin.isEmpty()?"":"oRiGiN: "+origin+"\r\n");
     check(response.status.equals("HTTP/1.1 200 OK"),"Snapshot GET succeeds repeatedly");
     check("image/jpeg".equals(response.headers.get("content-type")),"Snapshot JPEG MIME type");
     check(Arrays.equals(response.body,expected),"Snapshot reuses latest immutable complete JPEG");
     check(ImageIO.read(new ByteArrayInputStream(response.body))!=null,"Snapshot is a valid decodable JPEG");
     if(origin.equals("https://blindza.com") || origin.equals("http://localhost")) check(origin.equals(response.headers.get("access-control-allow-origin")),"GET permits exact allowed origin");
     else check(!response.headers.containsKey("access-control-allow-origin"),"Other origins receive no CORS permission");
    }
    Integer delivered=consumed.poll(2,TimeUnit.SECONDS);
    check(delivered!=null && delivered==sequence,"Concurrent consumers continue receiving during repeated snapshot requests");
   }
   reading.get(2,TimeUnit.SECONDS);
   primary.idle(); other.idle();
   Response denied=snapshot(server.port(),"OPTIONS","Origin: https://example.com\r\nAccess-Control-Request-Private-Network: true\r\n");
   check(!denied.headers.containsKey("access-control-allow-origin") && !denied.headers.containsKey("access-control-allow-private-network"),"Disallowed preflight grants no browser access");
   server.close(); check(primary.input.read()==-1,"Stop closes stream after snapshot traffic");
  }
  System.out.println("PASS: snapshot 503, valid latest JPEG, repeated requests alongside two MJPEG clients, exact CORS origins and OPTIONS/private-network preflight");
 }
 public static void main(String[] args) throws Exception {
  byte[] first=jpeg(0xff0000),second=jpeg(0x00ff00);
  snapshots(first,second);
  MjpegServer server=new MjpegServer(0); int port=server.port();
  try(Client a=new Client(port); Client b=new Client(port)) {
   a.idle(); b.idle(); // Header alone cannot fabricate an initial frame.
   byte[] offered=first.clone(); check(server.publish(offered,"first.jpg"),"Publish saved frame"); Arrays.fill(offered,(byte)0);
   check(Arrays.equals(a.frame(1,"first.jpg"),first),"Frame immutable after caller changes buffer");
   check(Arrays.equals(b.frame(1,"first.jpg"),first),"Multiple consumers receive identical full frame");
   a.idle(); b.idle();
   check(server.publish(second,"second.jpg"),"Publish second completed save");
   check(Arrays.equals(a.frame(2,"second.jpg"),second),"Same connection receives replacement JPEG");
   check(Arrays.equals(b.frame(2,"second.jpg"),second),"Second consumer updates independently");
   try(Client reconnect=new Client(port)) { check(Arrays.equals(reconnect.frame(2,"second.jpg"),second),"New consumer gets latest saved frame immediately"); }
   try(Socket wrong=new Socket("127.0.0.1",port)) {
    wrong.setSoTimeout(2000); wrong.getOutputStream().write("GET /other HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    check(line(wrong.getInputStream()).equals("HTTP/1.1 404 Not Found"),"Other paths cannot access files");
   }
   server.close(); check(a.input.read()==-1,"Stop disconnects clients");
   check(!server.publish(first,"late.jpg"),"A write completing after Stop cannot revive stream");
  } finally { server.close(); }
  try(MjpegServer restarted=new MjpegServer(port)) { check(restarted.port()==port,"Stop releases stable endpoint for next session"); }
  try(MjpegServer compatible=new MjpegServer(0); Client client=new Client(compatible.port(),"/mjpeg-compatible"); Client original=new Client(compatible.port())) {
   client.idle(); original.idle();
   compatible.publish(first,"first.jpg");
   check(Arrays.equals(client.frame(1,"first.jpg"),first),"Compatibility first completed full JPEG");
   original.frame(1,"first.jpg"); original.idle();
   long started=System.nanoTime();
   check(Arrays.equals(client.frame(1,"first.jpg"),first),"Compatibility repeats exact bytes without incrementing saved sequence");
   check(System.nanoTime()-started>500_000_000L,"Repetition is paced, not a busy loop");
   original.idle();
   compatible.publish(second,"second.jpg");
   check(Arrays.equals(client.frame(2,"second.jpg"),second),"New save immediately replaces compatibility frame");
   original.frame(2,"second.jpg"); original.idle();
   compatible.close(); check(client.input.read()==-1,"Stop closes repeating consumer");
  }
  BufferedImage noisy=new BufferedImage(1024,768,BufferedImage.TYPE_INT_RGB);
  Random random=new Random(1);
  for(int y=0;y<noisy.getHeight();y++) for(int x=0;x<noisy.getWidth();x++) noisy.setRGB(x,y,random.nextInt());
  ByteArrayOutputStream largeBytes=new ByteArrayOutputStream(); ImageIO.write(noisy,"jpg",largeBytes);
  byte[] large=largeBytes.toByteArray(); check(large.length>400000,"Fixture exercises multiple vOICe buffer expansions");
  try(MjpegServer compatible=new MjpegServer(0); Client client=new Client(compatible.port(),"/mjpeg-compatible")) {
   compatible.publish(large,"full.jpg");
   check(Arrays.equals(voiceFrame(client.input),large),"vOICe marker reader decodes exact large original after buffer growth");
  }
  System.out.println("PASS: local MJPEG complete frames, original endpoint idle, compatibility repetition, simultaneous consumers, reconnect, stop/restart");
 }
}

