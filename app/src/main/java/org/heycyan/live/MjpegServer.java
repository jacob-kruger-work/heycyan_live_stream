package org.heycyan.live;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only, latest-frame MJPEG. Call publish only after a successful complete save. */
final class MjpegServer implements Closeable {
 private static final String BOUNDARY="heycyan-frame";
 private final ServerSocket server=new ServerSocket();
 private final Set<Socket> clients=new HashSet<>();
 private final ExecutorService senders=Executors.newFixedThreadPool(4,r->{
  Thread thread=new Thread(r,"heycyan-mjpeg-client"); thread.setDaemon(true); return thread;
 });
 private final Object frames=new Object();
 private volatile boolean closed;
 private Frame latest;
 private long sequence;
 private static final class Frame {
  final long sequence;
  final byte[] jpeg;
  final String name;
  Frame(long sequence,byte[] jpeg,String name) { this.sequence=sequence; this.jpeg=jpeg; this.name=name; }
 }
 MjpegServer(int port) throws IOException {
  try {
   server.setReuseAddress(true);
   server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),port),4);
  } catch(IOException e) { server.close(); senders.shutdownNow(); throw e; }
  Thread accept=new Thread(this::accept,"heycyan-mjpeg-accept"); accept.setDaemon(true); accept.start();
 }
 int port() { return server.getLocalPort(); }
 boolean publish(byte[] jpeg,String name) {
  if(jpeg==null || jpeg.length==0 || name==null || !name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid saved frame");
  synchronized(frames) {
   if(closed) return false;
   latest=new Frame(++sequence,jpeg.clone(),name); frames.notifyAll(); return true;
  }
 }
 private void accept() {
  while(!closed) {
   Socket socket=null;
   try {
    socket=server.accept(); socket.setSoTimeout(5000); socket.setTcpNoDelay(true);
    synchronized(clients) {
     if(closed || clients.size()>=4) { socket.close(); continue; }
     clients.add(socket);
    }
    Socket accepted=socket;
    senders.execute(()->serve(accepted));
   } catch(IOException | RejectedExecutionException e) {
    if(socket!=null) { synchronized(clients) { clients.remove(socket); } try { socket.close(); } catch(IOException ignored) {} }
   }
  }
 }
 private static String request(InputStream input) throws IOException {
  ByteArrayOutputStream header=new ByteArrayOutputStream(); int tail=0;
  while(header.size()<8192) {
   int b=input.read(); if(b<0) throw new EOFException("Incomplete HTTP request");
   header.write(b); tail=(tail<<8)|(b&255);
   if(tail==0x0d0a0d0a) return header.toString(StandardCharsets.US_ASCII.name()).split("\r\n",2)[0];
  }
  throw new IOException("HTTP headers too large");
 }
 private void serve(Socket socket) {
  try(socket) {
   String[] line=request(socket.getInputStream()).split(" ");
   OutputStream out=socket.getOutputStream();
   boolean compatibility=line.length==3 && line[1].equals("/mjpeg-compatible");
   if(line.length!=3 || !line[0].equals("GET") || (!line[1].equals("/mjpeg") && !compatibility) || !line[2].startsWith("HTTP/1.")) {
    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush(); return;
   }
   out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary="+BOUNDARY+
    "\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
   long delivered=0,lastSent=0; boolean first=true;
   while(!closed) {
    Frame frame;
    synchronized(frames) {
     while(!closed && (latest==null || latest.sequence==delivered)) {
      if(compatibility && latest!=null) {
       long remaining=TimeUnit.SECONDS.toNanos(1)-(System.nanoTime()-lastSent);
       if(remaining<=0) break;
       TimeUnit.NANOSECONDS.timedWait(frames,remaining);
      } else frames.wait();
     }
     if(closed) return;
     frame=latest;
    }
    if(first) { out.write(("--"+BOUNDARY+"\r\n").getBytes(StandardCharsets.US_ASCII)); first=false; }
    out.write(("Content-Type: image/jpeg\r\nContent-Length: "+frame.jpeg.length+
     "\r\nX-Frame-Sequence: "+frame.sequence+"\r\nX-Snapshot-Name: "+frame.name+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(frame.jpeg); out.write(("\r\n--"+BOUNDARY+"\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush(); delivered=frame.sequence; lastSent=System.nanoTime();
   }
  } catch(IOException ignored) {
   // A local consumer disconnecting does not affect capture or other consumers.
  } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
  finally { synchronized(clients) { clients.remove(socket); } }
 }
 @Override public void close() {
  synchronized(frames) { if(closed) return; closed=true; latest=null; frames.notifyAll(); }
  try { server.close(); } catch(IOException ignored) {}
  synchronized(clients) { for(Socket client:clients) try { client.close(); } catch(IOException ignored) {} clients.clear(); }
  senders.shutdownNow();
 }
}

