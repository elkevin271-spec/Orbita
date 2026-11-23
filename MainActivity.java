package hn.orbita;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Zero-library Android interface with explicit status and reliable 2nd-plane controls. */
public final class MainActivity extends Activity {
    private static final int PICK_AUDIO=901, CAPTURE_PERMISSION=902, MEDIA_PROJECTION=903, NOTIFICATION_PERMISSION=904;
    private static final int BG=0xff0A1220, PANEL=0xff142136, PANEL_ALT=0xff192A40;
    private static final int WHITE=0xffECF5FD, MUTED=0xffA8BCCF, MINT=0xff39E4B8;
    private final String[] controls={"motion","speed","room","mix","distance","balance"};
    private final SeekBar[] bars=new SeekBar[6];
    private final int[] defaultValues={42,20,25,82,30,50};
    private TextView status,progressText,currentTitle;
    private Button[] presetButtons=new Button[3];
    private SeekBar position;
    private Button originalButton;
    private int savedSpatialMix=82;
    private boolean comparisonMode;
    private SpatialStageView stage;
    private boolean playing,tracking;
    private long positionMs,durationMs;
    private int activePreset=SpatialEngine.NATURAL;
    private final BroadcastReceiver statusReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){refreshStatus(intent);}
    };

    private int px(float dp){return (int)(dp*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int background,int radius,int stroke){
        GradientDrawable d=new GradientDrawable();
        d.setColor(background);d.setCornerRadius(px(radius));
        if(stroke!=0)d.setStroke(px(1),stroke);
        return d;
    }
    private TextView label(String text,int sp,int color,boolean bold) {
        TextView view=new TextView(this);
        view.setText(text);view.setTextSize(sp);view.setTextColor(color);
        if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }
    private LinearLayout column() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void gap(LinearLayout root,int dp){View v=new View(this);root.addView(v,new LinearLayout.LayoutParams(1,px(dp)));}
    private void section(LinearLayout parent,String title,String subtitle){
        TextView h=label(title,20,WHITE,true);parent.addView(h);
        if(subtitle!=null){TextView t=label(subtitle,13,MUTED,false);t.setPadding(0,px(6),0,0);parent.addView(t);}
        gap(parent,12);
    }
    private LinearLayout card(LinearLayout outer){
        LinearLayout c=column();c.setPadding(px(18),px(17),px(18),px(19));
        c.setBackground(shape(PANEL,21,0xff253A50));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=px(15);outer.addView(c,lp);return c;
    }
    private Button button(String title,boolean primary,View.OnClickListener handler) {
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(title);b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setMinHeight(px(48));b.setTextColor(primary?BG:WHITE);
        b.setBackground(shape(primary?MINT:PANEL_ALT,13,primary?0:0xff36516B));
        b.setOnClickListener(handler);
        return b;
    }
    private void wideButton(LinearLayout parent,String title,boolean primary,View.OnClickListener action){
        Button b=button(title,primary,action);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,px(52));
        p.bottomMargin=px(10);parent.addView(b,p);
    }
    private void halfButton(LinearLayout parent,String title,View.OnClickListener action){
        Button b=button(title,false,action);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,px(46),1);
        lp.setMargins(px(3),0,px(3),0);parent.addView(b,lp);
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        SharedPreferences pref=getSharedPreferences("dsp",MODE_PRIVATE);
        activePreset=pref.getInt("preset",SpatialEngine.NATURAL);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root=column();root.setPadding(px(19),px(26),px(19),px(29));
        scroll.addView(root);
        TextView logo=label("◉  ÓRBITA",32,MINT,true);root.addView(logo);
        TextView subtitle=label("SPATIAL AUDIO 1.0  ·  BINAURAL ENGINE",12,MUTED,true);
        subtitle.setLetterSpacing(.08f);root.addView(subtitle);gap(root,19);

        stage=new SpatialStageView(this);
        root.addView(stage,new LinearLayout.LayoutParams(-1,px(260)));
        gap(root,16);

        LinearLayout info=card(root);
        currentTitle=label("Escoge un audio para empezar",16,WHITE,true);info.addView(currentTitle);
        gap(info,7);
        status=label("Motor listo. El procesamiento comienza al reproducir.",13,MUTED,false);info.addView(status);
        gap(info,12);
        position=new SeekBar(this);
        position.setMax(1000);position.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
        position.setThumbTintList(android.content.res.ColorStateList.valueOf(MINT));
        position.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){}
            public void onStartTrackingTouch(SeekBar bar){tracking=true;}
            public void onStopTrackingTouch(SeekBar bar){
                tracking=false;
                if(durationMs>0){
                    long target=(long)bar.getProgress()*durationMs/1000L;
                    Intent seek=new Intent(MainActivity.this,OrbitService.class).setAction(OrbitService.SEEK);
                    seek.putExtra("ms",target);
                    startService(seek);
                }
            }
        });
        info.addView(position,new LinearLayout.LayoutParams(-1,px(32)));
        gap(info,9);
        progressText=label("00:00 / 00:00",12,MUTED,false);info.addView(progressText);
        LinearLayout transport=row();
        halfButton(transport,"⏸ Pausar",v->send(OrbitService.PAUSE));
        halfButton(transport,"▶ Seguir",v->send(OrbitService.RESUME));
        halfButton(transport,"■ Detener",v->send(OrbitService.STOP));
        gap(info,12);info.addView(transport);

        LinearLayout sources=card(root);
        section(sources,"Reproducción","Sonido procesado antes de llegar a tus auriculares.");
        wideButton(sources,"▶  Probar golpes binaurales 3D",true,v->startPlayback(OrbitService.PLAY_DEMO,null,null));
        wideButton(sources,"♫  Abrir música / audio del teléfono",false,v->selectAudio());
        TextView format=label("MP3 · AAC · M4A · OGG · FLAC · WAV y otros formatos admitidos por Android",12,MUTED,false);
        sources.addView(format);

        LinearLayout presets=card(root);
        section(presets,"Perfiles espaciales","Selecciona cómo se distribuirá la escena estéreo.");
        LinearLayout presetRow=row();
        String[] names={"Órbita 8D","Cine 3D","Realismo"};
        for(int i=0;i<3;i++){
            final int index=i;
            Button b=button(names[i],i==activePreset,v->choosePreset(index));
            presetButtons[i]=b;
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,px(46),1);
            lp.setMargins(px(2),0,px(2),0);presetRow.addView(b,lp);
        }
        presets.addView(presetRow);
        gap(presets,12);
        presets.addView(label("Realismo reduce el movimiento artificial y prioriza una escena estable. El modo 8D aumenta el desplazamiento.",13,MUTED,false));

        LinearLayout controlCard=card(root);
        section(controlCard,"Ingeniería acústica","Ajustes en tiempo real del motor binaural.");
        String[] titles={"Movimiento de las fuentes","Velocidad del recorrido","Reflexiones de la sala","Cantidad de efecto espacial","Distancia aparente","Balance izquierda / derecha"};
        for(int i=0;i<controls.length;i++){
            final int index=i;
            LinearLayout heading=row();
            TextView name=label(titles[i],14,WHITE,false);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            TextView pct=label(i==5?balanceName(pref.getInt(controls[i],defaultValues[i])):pref.getInt(controls[i],defaultValues[i])+"%",13,MINT,true);heading.addView(pct);
            controlCard.addView(heading);
            SeekBar bar=new SeekBar(this);bar.setMax(100);bar.setProgress(pref.getInt(controls[i],defaultValues[i]));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(MINT));
            bars[i]=bar;controlCard.addView(bar);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar b,int value,boolean fromUser){
                    pct.setText(index==5?balanceName(value):value+"%");if(fromUser&&stage!=null)
                        stage.setScene(bars[0].getProgress()/100f,bars[1].getProgress()/100f,playing);
                }
                public void onStartTrackingTouch(SeekBar b){}
                public void onStopTrackingTouch(SeekBar b){saveSettings();send(OrbitService.UPDATE);}
            });
            gap(controlCard,i==controls.length-1?0:9);
        }
        originalButton=button("Comparar audio original (A/B)",false,v->toggleComparison());
        LinearLayout.LayoutParams compareLp=new LinearLayout.LayoutParams(-1,px(50));
        compareLp.bottomMargin=px(9);controlCard.addView(originalButton,compareLp);
        wideButton(controlCard,"Restablecer ajuste equilibrado",false,v->restoreDefaults());

        LinearLayout external=card(root);
        section(external,"Otras aplicaciones · experimental","Captura solo audio permitido por Android, con autorización visible.");
        wideButton(external,"◎  Autorizar captura compatible",false,v->requestCapture());
        external.addView(label("IMPORTANTE: YouTube y otras apps pueden impedir la captura. Android no permite sustituir universalmente su audio; además, podría oírse el original junto al procesado. Para resultados fiables usa el reproductor interno.",13,MUTED,false));
        gap(external,14);
        wideButton(external,"Abrir YouTube",false,v->{
            Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/"));
            try{startActivity(i);}catch(Exception ex){status.setText("No se pudo abrir YouTube");}
        });

        TextView foot=label("Audio binaural diseñado para auriculares estéreo. Modelo HRTF paramétrico, no mediciones personalizadas de tus oídos. Con el teléfono bloqueado sigue sonando mediante un servicio y notificación permanente.",12,MUTED,false);
        root.addView(foot);
        setContentView(scroll);
        stage.setScene(pref.getInt("motion",42)/100f,pref.getInt("speed",20)/100f,false);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);
        refreshFromPreferences();
    }
    private void choosePreset(int profile) {
        activePreset=profile;saveSettings();send(OrbitService.UPDATE);
        for(int i=0;i<presetButtons.length;i++){
            boolean selected=i==profile;
            presetButtons[i].setBackground(shape(selected?MINT:PANEL_ALT,13,selected?0:0xff36516B));
            presetButtons[i].setTextColor(selected?BG:WHITE);
        }
    }
    private static String balanceName(int value){
        if(value==50)return "Centro";
        return (value<50?"I ":"D ")+Math.abs(value-50)*2+"%";
    }
    private void toggleComparison(){
        if(!comparisonMode){savedSpatialMix=bars[3].getProgress();bars[3].setProgress(0);comparisonMode=true;
            originalButton.setText("Volver al audio espacial");
        }else{bars[3].setProgress(savedSpatialMix);comparisonMode=false;
            originalButton.setText("Comparar audio original (A/B)");
        }
        saveSettings();send(OrbitService.UPDATE);
    }
    private void restoreDefaults() {
        comparisonMode=false;if(originalButton!=null)originalButton.setText("Comparar audio original (A/B)");
        for(int i=0;i<bars.length;i++)bars[i].setProgress(defaultValues[i]);
        choosePreset(SpatialEngine.NATURAL);
        stage.setScene(defaultValues[0]/100f,defaultValues[1]/100f,playing);
    }
    private void saveSettings() {
        SharedPreferences.Editor edit=getSharedPreferences("dsp",MODE_PRIVATE).edit();
        for(int i=0;i<controls.length;i++)if(bars[i]!=null)edit.putInt(controls[i],bars[i].getProgress());
        edit.putInt("preset",activePreset).apply();
    }
    private void send(String action) {
        try {startService(new Intent(this,OrbitService.class).setAction(action));}
        catch(Exception ex){status.setText("No se pudo enviar el control: "+ex.getMessage());}
    }
    private void startPlayback(String action,Uri uri,String label) {
        saveSettings();
        Intent i=new Intent(this,OrbitService.class).setAction(action);
        if(uri!=null)i.setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(label!=null)i.putExtra(OrbitService.EXTRA_LABEL,label);
        try {
            startForegroundService(i);
            status.setText("Solicitando reproducción espacial…");
        }catch(Exception e){status.setText("No se pudo iniciar: "+e.getMessage());}
    }
    private void selectAudio() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,PICK_AUDIO);
    }
    private void requestCapture() {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},CAPTURE_PERMISSION);return;
        }
        MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(),MEDIA_PROJECTION);
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if(requestCode==CAPTURE_PERMISSION) {
            if(grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)requestCapture();
            else status.setText("La captura externa requiere permiso de Android");
        }
    }
    private String filename(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "Archivo de audio";
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK || data==null){status.setText("Operación cancelada");return;}
        if(requestCode==PICK_AUDIO && data.getData()!=null) {
            Uri uri=data.getData();
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            startPlayback(OrbitService.PLAY_FILE,uri,filename(uri));
        }else if(requestCode==MEDIA_PROJECTION){
            saveSettings();
            Intent service=new Intent(this,OrbitService.class).setAction(OrbitService.CAPTURE);
            service.putExtra(OrbitService.EXTRA_CODE,resultCode);
            service.putExtra(OrbitService.EXTRA_DATA,data);
            try{startForegroundService(service);status.setText("Captura autorizada · esperando audio compatible");}
            catch(Exception e){status.setText("No se pudo iniciar captura: "+e.getMessage());}
        }
    }
    @Override protected void onStart(){
        super.onStart();
        IntentFilter f=new IntentFilter(OrbitService.STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(statusReceiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver,f);
        refreshFromPreferences();
    }
    @Override protected void onStop(){
        try{unregisterReceiver(statusReceiver);}catch(IllegalArgumentException ignored){}
        super.onStop();
    }
    @Override protected void onResume(){super.onResume();refreshFromPreferences();}
    private void refreshFromPreferences(){
        if(status==null)return;
        SharedPreferences prefs=getSharedPreferences("playback",MODE_PRIVATE);
        updateStatus(prefs.getString("state","idle"),
            prefs.getString("message","Motor listo. Abre un audio para empezar."),
            prefs.getLong("position",0),prefs.getLong("duration",0),
            prefs.getString("title","Órbita Spatial"));
    }
    private void refreshStatus(Intent i){
        updateStatus(i.getStringExtra("state"),i.getStringExtra("message"),
            i.getLongExtra("position",0),i.getLongExtra("duration",0),i.getStringExtra("title"));
    }
    private void updateStatus(String state,String message,long pos,long duration,String title){
        if(state==null)state="idle";
        playing="playing".equals(state)||"starting".equals(state)||"warning".equals(state);
        positionMs=pos;durationMs=duration;
        status.setText(message==null?"Motor listo":message);
        status.setTextColor("error".equals(state)?0xffff8d98:"warning".equals(state)?0xffffd27a:MUTED);
        currentTitle.setText(title==null||title.isEmpty()?"Órbita Spatial":title);
        if(!tracking){position.setProgress(duration>0?(int)Math.min(1000,pos*1000L/duration):0);}
        position.setEnabled(duration>0);
        progressText.setText(time(pos)+" / "+(duration>0?time(duration):"--:--"));
        if(stage!=null && bars[0]!=null)
            stage.setScene(bars[0].getProgress()/100f,bars[1].getProgress()/100f,playing);
    }
    private static String time(long millis){
        long sec=Math.max(0,millis)/1000;
        return String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60);
    }
}
