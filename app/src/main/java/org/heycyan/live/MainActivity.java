package org.heycyan.live;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Standard Android widgets expose their labels, selection and state to TalkBack. */
public class MainActivity extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final List<BluetoothDevice> devices=new ArrayList<>();
 private Spinner selector;
 private Button toggle,refresh;
 private TextView status,metrics;
 private String address="";
 private boolean pendingStart;
 private final Runnable poll=new Runnable() { public void run() {
  toggle.setText(LiveService.running ? "Stop live stream" : "Start live stream");
  selector.setEnabled(!LiveService.running); refresh.setEnabled(!LiveService.running);
  if(!LiveService.status.contentEquals(status.getText())) status.setText(LiveService.status);
  if(!LiveService.metrics.contentEquals(metrics.getText())) metrics.setText(LiveService.metrics);
  handler.postDelayed(this,500);
 }};
 @Override public void onCreate(Bundle state) {
  super.onCreate(state); address=getPreferences(0).getString("device","");
  LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
  int padding=(int)(24*getResources().getDisplayMetrics().density); layout.setPadding(padding,padding,padding,padding);
  TextView title=text(layout,"HeyCyan Live",26); title.setAccessibilityHeading(true);
  TextView label=text(layout,"Bluetooth glasses",20);
  selector=new Spinner(this); selector.setId(View.generateViewId()); label.setLabelFor(selector.getId()); selector.setContentDescription("Bluetooth glasses"); selector.setMinimumHeight(padding*2); layout.addView(selector);
  selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
   public void onNothingSelected(AdapterView<?> parent) {}
   public void onItemSelected(AdapterView<?> parent,View v,int position,long id) {
    if(position>0 && position<=devices.size()) { address=devices.get(position-1).getAddress(); getPreferences(0).edit().putString("device",address).apply(); }
   }
  });
  refresh=button(layout,"Refresh paired devices"); refresh.setOnClickListener(v->{ pendingStart=false; permissions(); });
  button(layout,"Open Bluetooth pairing settings").setOnClickListener(v->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
  toggle=button(layout,"Start live stream"); toggle.setOnClickListener(v->{
   if(LiveService.running) startService(new Intent(this,LiveService.class).setAction("STOP"));
   else { pendingStart=true; permissions(); }
  });
  CheckBox announcements=new CheckBox(this); announcements.setText("Announce status changes"); announcements.setChecked(getPreferences(0).getBoolean("announce",true)); layout.addView(announcements);
  status=text(layout,LiveService.status,20); status.setAccessibilityLiveRegion(announcements.isChecked()?View.ACCESSIBILITY_LIVE_REGION_POLITE:View.ACCESSIBILITY_LIVE_REGION_NONE);
  announcements.setOnCheckedChangeListener((v,checked)->{ getPreferences(0).edit().putBoolean("announce",checked).apply(); status.setAccessibilityLiveRegion(checked?View.ACCESSIBILITY_LIVE_REGION_POLITE:View.ACCESSIBILITY_LIVE_REGION_NONE); });
  metrics=text(layout,LiveService.metrics,18);
  TextView url=text(layout,"Local stream: http://127.0.0.1:8080/mjpeg",18); url.setTextIsSelectable(true);
  button(layout,"Copy stream address").setOnClickListener(v->{ getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("MJPEG stream","http://127.0.0.1:8080/mjpeg")); Toast.makeText(this,"Stream address copied",Toast.LENGTH_SHORT).show(); });
  text(layout,"Turn on Bluetooth, Wi-Fi and Location. Close other glasses apps, select your paired glasses, then start. Approve Android's Wi-Fi Direct connection prompt if shown. Once frames arrive, press Home to use the stream in another app. The stream continues in the background, with a Stop action in its notification. Target: one fresh JPEG per second, 1600 by 1200 pixels, under 1 MB per frame.",18);
  ScrollView scroll=new ScrollView(this); scroll.addView(layout); setContentView(scroll);
 }
 private TextView text(LinearLayout layout,String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setPadding(0,12,0,12); layout.addView(t); return t; }
 private Button button(LinearLayout layout,String value) { Button b=new Button(this); b.setText(value); b.setMinHeight((int)(56*getResources().getDisplayMetrics().density)); layout.addView(b); return b; }
 private String[] required() { return Build.VERSION.SDK_INT>=33 ? new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.NEARBY_WIFI_DEVICES,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION} : Build.VERSION.SDK_INT>=31 ? new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION} : new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION}; }
 private void permissions() {
  List<String> missing=new ArrayList<>(); for(String p:required()) if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) missing.add(p);
  if(!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]),1); return; }
  load(); if(!pendingStart) return;
  if(address.isEmpty()) { LiveService.status="Select your glasses first."; return; }
  pendingStart=false;
  if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);
  startForegroundService(new Intent(this,LiveService.class).putExtra("address",address));
 }
 private void load() {
  if(checkSelfPermission(Build.VERSION.SDK_INT>=31?Manifest.permission.BLUETOOTH_CONNECT:Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return;
  try {
   BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();
   if(adapter==null || !adapter.isEnabled()) { LiveService.status="Turn on Bluetooth, then refresh paired devices."; return; }
   devices.clear(); devices.addAll(adapter.getBondedDevices()); devices.sort(Comparator.comparing(d->d.getName()==null?"":d.getName()));
   List<String> names=new ArrayList<>(); names.add("Select your glasses"); int selected=0;
   for(int i=0;i<devices.size();i++) { BluetoothDevice d=devices.get(i); names.add((d.getName()==null?"Unnamed device":d.getName())+", "+d.getAddress()); if(d.getAddress().equals(address)) selected=i+1; }
   ArrayAdapter<String> choices=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names); choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); selector.setAdapter(choices); selector.setSelection(selected);
  } catch(SecurityException e) { LiveService.status="Allow Nearby devices in Android app settings."; }
 }
 @Override public void onResume() { super.onResume(); if(!LiveService.running) load(); handler.post(poll); }
 @Override public void onPause() { handler.removeCallbacks(poll); super.onPause(); }
 @Override public void onRequestPermissionsResult(int code,String[] p,int[] results) { super.onRequestPermissionsResult(code,p,results); if(code==1) { for(int r:results) if(r!=PackageManager.PERMISSION_GRANTED) { LiveService.status="Allow Nearby devices and precise Location, then retry."; return; } permissions(); } }
}
