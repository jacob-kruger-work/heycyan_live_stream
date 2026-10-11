package org.heycyan.live;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

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
 public static void main(String[] args) throws Exception {
  byte[] first=jpeg(0xff0000),second=jpeg(0x00ff00);
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

