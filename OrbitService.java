package hn.orbita;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Foreground audio renderer. No background capture without explicit Android permission. */
public final class OrbitService extends Service {
    public static final String PLAY_DEMO="hn.orbita.PLAY_DEMO";
    public static final String PLAY_FILE="hn.orbita.PLAY_FILE";
    public static final String CAPTURE="hn.orbita.CAPTURE";
    public static final String STOP="hn.orbita.STOP";
    public static final String PAUSE="hn.orbita.PAUSE";
    public static final String RESUME="hn.orbita.RESUME";
    public static final String UPDATE="hn.orbita.UPDATE";
    public static final String SEEK="hn.orbita.SEEK";
    public static final String STATUS="hn.orbita.STATUS";
    public static final String EXTRA_CODE="capture_code";
    public static final String EXTRA_DATA="capture_data";
    public static final String EXTRA_LABEL="media_label";
    private static final String CHANNEL_ID="orbita_spatial_playback";
    private static final int NOTIFICATION_ID=410;
    private static final String TAG="OrbitaSpatial";
    private volatile Session current;
    private int generation;
    private AudioManager audioManager;
    private final BroadcastReceiver disconnectReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent) {
            if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Session s=current;
                if(s!=null&&!s.capture)pause(s,"Auriculares desconectados · audio pausado");
            }
        }
    };
    private static final class Session {
        final int id;
        final String action, title;
        final boolean capture;
        volatile boolean active=true, paused=false, pausedByFocus=false;
        volatile AudioTrack track;
        volatile AudioRecord record;
        volatile MediaProjection projection;
        volatile MediaProjection.Callback projectionCallback;
        volatile SpatialEngine engine;
        volatile AudioFocusRequest focus;
        volatile PowerManager.WakeLock wakeLock;
        volatile long positionMs, durationMs;
        final AtomicLong seekMs=new AtomicLong(-1);
        volatile long lastStatusMs;
        Thread thread;
        Session(int id,String action,String title) {
            this.id=id;this.action=action;this.title=title;
            capture=CAPTURE.equals(action);
        }
        void cancel() {
            active=false;paused=false;
            AudioRecord r=record;
            if(r!=null)try{r.stop();}catch(Exception ignored){}
            AudioTrack t=track;
            if(t!=null)try{t.pause();t.flush();}catch(Exception ignored){}
            Thread w=thread;
            if(w!=null)w.interrupt();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        NotificationChannel c=new NotificationChannel(CHANNEL_ID,"Órbita · Audio espacial",NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Reproducción activa y controles en segundo plano");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        IntentFilter noisyFilter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(disconnectReceiver,noisyFilter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(disconnectReceiver,noisyFilter);
    }

    private PendingIntent command(String action,int requestCode) {
        Intent i=new Intent(this,OrbitService.class).setAction(action);
        return PendingIntent.getService(this,requestCode,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification notification(Session s,String message) {
        Intent home=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent homeIntent=PendingIntent.getActivity(this,0,home,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        boolean paused=s!=null&&s.paused;
        return new Notification.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Órbita Spatial 1.0" )
            .setContentText(message)
            .setContentIntent(homeIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(paused?android.R.drawable.ic_media_play:android.R.drawable.ic_media_pause,
                paused?"Continuar":"Pausar",command(paused?RESUME:PAUSE,100))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Detener",command(STOP,101))
            .build();
    }
    private void updateNotification(Session s,String message) {
        if(current!=s)return;
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID,notification(s,message));
    }
    private void report(Session s,String state,String message) {
        if(current!=s)return;
        getSharedPreferences("playback",MODE_PRIVATE).edit()
            .putString("state",state).putString("message",message)
            .putLong("position",s.positionMs).putLong("duration",s.durationMs)
            .putString("title",s.title).apply();
        Intent i=new Intent(STATUS).setPackage(getPackageName());
        i.putExtra("state",state).putExtra("message",message);
        i.putExtra("position",s.positionMs).putExtra("duration",s.durationMs);
        i.putExtra("title",s.title);
        sendBroadcast(i);
    }
    private void reportProgress(Session s) {
        long now=SystemClock.elapsedRealtime();
        if(now-s.lastStatusMs>650) {
            s.lastStatusMs=now;
            report(s,s.paused?"paused":"playing",s.capture?"Captura compatible activa":s.title);
        }
    }
    private void saveIdle(String state,String msg) {
        getSharedPreferences("playback",MODE_PRIVATE).edit()
            .putString("state",state).putString("message",msg)
            .putLong("position",0).putLong("duration",0).apply();
        sendBroadcast(new Intent(STATUS).setPackage(getPackageName())
            .putExtra("state",state).putExtra("message",msg));
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();
        Session s=current;
        // Avoid keeping an otherwise empty started Service alive for a stale control.
        if(s==null && (PAUSE.equals(action)||RESUME.equals(action)||UPDATE.equals(action)||SEEK.equals(action))){
            stopSelf(startId);return START_NOT_STICKY;
        }
        if(STOP.equals(action)) {
            if(s!=null){current=null;s.cancel();}
            saveIdle("idle","Audio detenido");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if(PAUSE.equals(action)) {if(s!=null)pause(s,"Audio pausado");return START_NOT_STICKY;}
        if(RESUME.equals(action)) {if(s!=null)resume(s);return START_NOT_STICKY;}
        if(UPDATE.equals(action)) {if(s!=null)configureEngine(s);return START_NOT_STICKY;}
        if(SEEK.equals(action)) {
            if(s!=null && !s.capture)s.seekMs.set(Math.max(0,intent.getLongExtra("ms",0L)));
            return START_NOT_STICKY;
        }
        if(!PLAY_DEMO.equals(action) && !PLAY_FILE.equals(action) && !CAPTURE.equals(action))return START_NOT_STICKY;
        if(s!=null)s.cancel();
        String label=intent.getStringExtra(EXTRA_LABEL);
        if(label==null||label.trim().isEmpty())label=PLAY_DEMO.equals(action)?"Demostración de golpes binaurales":
            CAPTURE.equals(action)?"Captura de apps compatibles":"Tu archivo de audio";
        Session next=new Session(++generation,action,label);
        current=next;
        int fgType=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        if(next.capture)fgType|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        try {
            startForeground(NOTIFICATION_ID,notification(next,"Preparando sonido espacial"),fgType);
        }catch(Exception ex) {
            current=null;
            saveIdle("error","Android no permitió iniciar servicio: "+safeError(ex));
            stopSelf();
            return START_NOT_STICKY;
        }
        final Intent source=new Intent(intent);
        next.thread=new Thread(()->runSession(next,source),"Orbita-DSP-"+next.id);
        next.thread.setPriority(Thread.NORM_PRIORITY+1);
        next.thread.start();
        report(next,"starting","Iniciando motor binaural");
        return START_NOT_STICKY;
    }
    private String safeError(Exception e) {
        String text=e.getMessage();
        return text==null?e.getClass().getSimpleName():text;
    }
    private void pause(Session s,String message) {
        s.paused=true;s.pausedByFocus=false;
        AudioTrack t=s.track;
        if(t!=null)try{t.pause();}catch(Exception ignored){}
        report(s,"paused",message);updateNotification(s,message);
    }
    private void resume(Session s) {
        s.paused=false;s.pausedByFocus=false;
        AudioTrack t=s.track;
        if(t!=null)try{t.play();}catch(Exception ignored){}
        report(s,"playing","Reproduciendo audio binaural");updateNotification(s,"Audio binaural en segundo plano");
    }
    private void configureEngine(Session s) {
        SpatialEngine e=s.engine;
        if(e==null)return;
        SharedPreferences p=getSharedPreferences("dsp",MODE_PRIVATE);
        e.configure(new SpatialEngine.Parameters(
            p.getInt("motion",42)/100f,p.getInt("speed",20)/100f,
            p.getInt("room",25)/100f,p.getInt("mix",82)/100f,
            p.getInt("distance",30)/100f,
            (p.getInt("balance",50)-50)/50f,p.getInt("preset",SpatialEngine.NATURAL)));
    }
    private void acquireWakeLock(Session s) {
        PowerManager p=(PowerManager)getSystemService(POWER_SERVICE);
        s.wakeLock=p.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OrbitaSpatial:AudioRender");
        s.wakeLock.setReferenceCounted(false);
        s.wakeLock.acquire();
    }
    private void focusForPlayback(Session s) throws IOException {
        if(s.capture)return; // Captured app must keep playing; do not steal its audio focus.
        AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        s.focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(change->{
                if(!s.active)return;
                if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                   change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if(!s.paused){s.paused=true;s.pausedByFocus=true;
                        AudioTrack t=s.track;if(t!=null)try{t.pause();}catch(Exception ignored){}
                        report(s,"paused","Pausado por otra aplicación de audio");}
                } else if(change==AudioManager.AUDIOFOCUS_GAIN && s.pausedByFocus)resume(s);
            }).build();
        int focus=audioManager.requestAudioFocus(s.focus);
        if(focus!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IOException("Otra aplicación tiene prioridad de audio; inténtalo nuevamente");
    }
    private void runSession(Session s,Intent source) {
        String failure=null;
        try {
            acquireWakeLock(s);
            focusForPlayback(s);
            if(PLAY_DEMO.equals(s.action))runDemo(s);
            else if(PLAY_FILE.equals(s.action)){
                Uri uri=source.getData();if(uri==null)throw new IOException("Ningún archivo seleccionado");
                runMedia(s,uri);
            } else runCapture(s,source);
        }catch(InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }catch(Exception ex) {
            if(s.active){failure=safeError(ex);Log.e(TAG,"Error de audio",ex);}
        } finally {
            s.active=false;
            releaseResources(s);
            if(current==s){
                current=null;
                saveIdle(failure==null?"idle":"error",failure==null?"Reproducción finalizada":"Error: "+failure);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }
    private void releaseResources(Session s) {
        AudioRecord r=s.record;s.record=null;
        if(r!=null){try{r.stop();}catch(Exception ignored){}try{r.release();}catch(Exception ignored){}}
        AudioTrack t=s.track;s.track=null;
        if(t!=null){try{t.pause();t.flush();}catch(Exception ignored){}try{t.release();}catch(Exception ignored){}}
        MediaProjection m=s.projection;s.projection=null;
        if(m!=null){
            try{if(s.projectionCallback!=null)m.unregisterCallback(s.projectionCallback);}catch(Exception ignored){}
            try{m.stop();}catch(Exception ignored){}
        }
        if(s.focus!=null){try{audioManager.abandonAudioFocusRequest(s.focus);}catch(Exception ignored){}}
        PowerManager.WakeLock w=s.wakeLock;
        if(w!=null && w.isHeld()){try{w.release();}catch(Exception ignored){}}
    }
    private boolean awaitPlaying(Session s) throws InterruptedException {
        while(s.active&&s.paused)Thread.sleep(45);
        return s.active;
    }
    private AudioTrack openOutput(Session s,int rate) throws IOException {
        int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IOException("Frecuencia de salida no admitida: "+rate);
        AudioTrack result=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(Math.max(min*2,16384)).build();
        if(result.getState()!=AudioTrack.STATE_INITIALIZED){result.release();throw new IOException("Salida AudioTrack no disponible");}
        s.track=result;
        if(!s.paused)result.play();
        return result;
    }
    private void write(Session s,short[] data,int length) throws Exception {
        int pos=0;
        while(pos<length&&s.active) {
            if(!awaitPlaying(s))break;
            AudioTrack t=s.track;
            if(t==null)throw new IOException("Dispositivo de salida desconectado");
            int wrote=t.write(data,pos,length-pos,AudioTrack.WRITE_BLOCKING);
            if(wrote==AudioTrack.ERROR_DEAD_OBJECT)throw new IOException("La salida de audio cambió o se desconectó");
            if(wrote<=0)throw new IOException("La reproducción de audio devolvió código "+wrote);
            pos+=wrote;
        }
    }
    private void initializeDsp(Session s,int rate) {
        s.engine=new SpatialEngine(rate);configureEngine(s);
    }
    private void runDemo(Session s) throws Exception {
        int rate=DemoKnocks.RATE;
        initializeDsp(s,rate);
        openOutput(s,rate);
        s.durationMs=(long)(DemoKnocks.LENGTH_SECONDS*1000);
        DemoKnocks generator=new DemoKnocks(rate);
        short[] mono=new short[1024],stereo=new short[2048];
        report(s,"playing","Prueba de puerta: escucha izquierda, atrás y cerca");
        updateNotification(s,"Demo binaural · golpes alrededor de ti");
        int total=(int)(DemoKnocks.LENGTH_SECONDS*rate);
        for(int frame=0;frame<total&&s.active;) {
            if(!awaitPlaying(s))break;
            long seek=s.seekMs.getAndSet(-1);
            if(seek>=0) {
                frame=(int)Math.min(total,seek*rate/1000L);
                AudioTrack t=s.track;if(t!=null){t.pause();t.flush();t.play();}
                initializeDsp(s,rate);
                generator=new DemoKnocks(rate);
            }
            int n=Math.min(mono.length,total-frame);
            generator.generate(mono,frame,n);
            int group=DemoKnocks.groupAt((double)frame/rate);
            s.engine.processObject(mono,n,stereo,DemoKnocks.AZIMUTHS[group],
                DemoKnocks.ELEVATIONS[group],DemoKnocks.DISTANCES[group]);
            write(s,stereo,n*2);
            frame+=n;s.positionMs=(long)frame*1000/rate;reportProgress(s);
        }
        drainOutput(s,rate);
    }
    private void drainOutput(Session s,int rate) throws Exception {
        // Render a short quiet tail so early room reflections are not abruptly truncated.
        SpatialEngine engine=s.engine;
        if(engine!=null&&s.active&&!s.capture) {
            short[] zeros=new short[2048];
            for(int i=0;i<10&&s.active;i++) {
                if(!awaitPlaying(s))break;
                engine.process(zeros,zeros.length);
                write(s,zeros,zeros.length);
                Arrays.fill(zeros,(short)0);
            }
        }
        if(s.active)Thread.sleep(90);
    }

    private void runMedia(Session s,Uri uri) throws Exception {
        MediaExtractor extractor=new MediaExtractor();
        MediaCodec codec=null;
        try {
            extractor.setDataSource(this,uri,null);
            MediaFormat inputFormat=null;
            int index=-1;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){index=i;inputFormat=f;break;}
            }
            if(index<0||inputFormat==null)throw new IOException("El archivo no tiene una pista de audio compatible");
            extractor.selectTrack(index);
            if(inputFormat.containsKey(MediaFormat.KEY_DURATION))
                s.durationMs=inputFormat.getLong(MediaFormat.KEY_DURATION)/1000L;
            String mime=inputFormat.getString(MediaFormat.KEY_MIME);
            codec=MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat,null,null,0);
            codec.start();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            boolean inputEnd=false,outputEnd=false;
            int rate=inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels=inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding=AudioFormat.ENCODING_PCM_16BIT;
            // The actual PCM format comes from the decoder output, not from a guessed input codec.
            boolean outputReady=false;
            short[] decodedStereo=new short[16384];
            report(s,"starting","Decodificando audio y aplicando motor espacial");
            while(s.active&&!outputEnd) {
                if(!awaitPlaying(s))break;
                long seek=s.seekMs.getAndSet(-1);
                if(seek>=0) {
                    extractor.seekTo(seek*1000L,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    codec.flush();inputEnd=false;outputEnd=false;
                    s.positionMs=seek;
                    if(s.track!=null){s.track.pause();s.track.flush();if(!s.paused)s.track.play();}
                    if(outputReady)initializeDsp(s,rate);
                }
                if(!inputEnd){
                    int in=codec.dequeueInputBuffer(12000);
                    if(in>=0){
                        ByteBuffer b=codec.getInputBuffer(in);
                        if(b==null)throw new IOException("El decodificador no devolvió buffer de entrada");
                        b.clear();
                        int len=extractor.readSampleData(b,0);
                        if(len<0){codec.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true;}
                        else{codec.queueInputBuffer(in,0,len,extractor.getSampleTime(),0);extractor.advance();}
                    }
                }
                int result=codec.dequeueOutputBuffer(info,12000);
                if(result==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat output=codec.getOutputFormat();
                    int newRate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int newChannels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int newEncoding=output.containsKey(MediaFormat.KEY_PCM_ENCODING)?
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    if(newChannels<1||newChannels>8)throw new IOException("Canales PCM no soportados: "+newChannels);
                    if(newEncoding!=AudioFormat.ENCODING_PCM_16BIT && newEncoding!=AudioFormat.ENCODING_PCM_FLOAT)
                        throw new IOException("El decodificador devolvió una codificación PCM no soportada: "+newEncoding);
                    channels=newChannels;encoding=newEncoding;
                    if(s.track!=null&&rate!=newRate){
                        AudioTrack old=s.track;s.track=null;
                        old.pause();old.flush();old.release();
                    }
                    rate=newRate;
                    if(s.track==null)openOutput(s,rate);
                    initializeDsp(s,rate);
                    outputReady=true;
                    report(s,"playing","Procesando: "+s.title);
                    updateNotification(s,"Archivo espacial · "+s.title);
                } else if(result>=0) {
                    try {
                        ByteBuffer output=codec.getOutputBuffer(result);
                        if(output!=null&&info.size>0){
                            if(!outputReady) {
                                MediaFormat f=codec.getOutputFormat();
                                rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                                channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                                encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                                if(channels<1||channels>8||
                                   (encoding!=AudioFormat.ENCODING_PCM_16BIT&&encoding!=AudioFormat.ENCODING_PCM_FLOAT))
                                    throw new IOException("Formato PCM no soportado");
                                openOutput(s,rate);initializeDsp(s,rate);outputReady=true;
                            }
                            output.order(ByteOrder.LITTLE_ENDIAN);
                            output.position(info.offset);output.limit(info.offset+info.size);
                            int sampleBytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;
                            int frames=info.size/(channels*sampleBytes);
                            while(frames>0&&s.active){
                                int n=Math.min(frames,decodedStereo.length/2);
                                for(int j=0;j<n;j++) {
                                    float left=readSample(output,encoding);
                                    float right=channels>1?readSample(output,encoding):left;
                                    if(channels>=3){
                                        float center=readSample(output,encoding)*.707f;
                                        left+=center;right+=center;
                                    }
                                    if(channels>=4){float lfe=readSample(output,encoding)*.20f;left+=lfe;right+=lfe;}
                                    if(channels>=5)left+=readSample(output,encoding)*.65f;
                                    if(channels>=6)right+=readSample(output,encoding)*.65f;
                                    if(channels>=7)left+=readSample(output,encoding)*.35f;
                                    if(channels>=8)right+=readSample(output,encoding)*.35f;
                                    // Headroom for channel downmix, preserving the stereo track's relative balance.
                                    float scale=channels>2?.68f:1f;
                                    decodedStereo[2*j]=pcm16(left*scale);
                                    decodedStereo[2*j+1]=pcm16(right*scale);
                                }
                                s.engine.process(decodedStereo,n*2);
                                write(s,decodedStereo,n*2);
                                frames-=n;
                            }
                            s.positionMs=Math.max(0,info.presentationTimeUs/1000L);
                            reportProgress(s);
                        }
                    } finally {
                        codec.releaseOutputBuffer(result,false);
                    }
                    outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                }
            }
            if(outputReady)drainOutput(s,rate);
        } finally {
            if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}
            extractor.release();
        }
    }
    private static float readSample(ByteBuffer b,int encoding){
        if(encoding==AudioFormat.ENCODING_PCM_FLOAT)return b.getFloat();
        return b.getShort()/32768f;
    }
    private static short pcm16(float v) {
        if(!Float.isFinite(v))return 0;
        return (short)Math.round(Math.max(-1f,Math.min(1f,v))*32767f);
    }

    private void runCapture(Session s,Intent intent) throws Exception {
        int result=intent.getIntExtra(EXTRA_CODE,Activity.RESULT_CANCELED);
        Intent consent=Build.VERSION.SDK_INT>=33?
            intent.getParcelableExtra(EXTRA_DATA,Intent.class):intent.getParcelableExtra(EXTRA_DATA);
        if(result!=Activity.RESULT_OK||consent==null)
            throw new SecurityException("Falta autorización de captura de Android");
        MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        MediaProjection projection=manager.getMediaProjection(result,consent);
        s.projection=projection;
        s.projectionCallback=new MediaProjection.Callback(){
            @Override public void onStop() {
                if(s.active){s.cancel();report(s,"idle","Android finalizó la autorización de captura");}
            }
        };
        projection.registerCallback(s.projectionCallback,new Handler(Looper.getMainLooper()));
        AudioPlaybackCaptureConfiguration conf=new AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build();
        int rate=48000;
        int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IOException("Captura PCM estéreo no soportada por el dispositivo");
        s.record=new AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(conf)
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .setBufferSizeInBytes(Math.max(min*2,16384)).build();
        if(s.record.getState()!=AudioRecord.STATE_INITIALIZED)
            throw new IOException("El dispositivo no habilitó AudioRecord para captura");
        initializeDsp(s,rate);openOutput(s,rate);
        s.record.startRecording();
        report(s,"playing","Captura autorizada. Puede coexistir el audio original.");
        updateNotification(s,"Captura compatible · Android controla el permiso");
        short[] buffer=new short[4096];
        long nonzero=0,observed=0;
        while(s.active) {
            if(!awaitPlaying(s))break;
            int count=s.record.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
            if(count==AudioRecord.ERROR_DEAD_OBJECT)throw new IOException("La aplicación dejó de ofrecer captura");
            if(count<0)throw new IOException("Error de captura AudioRecord: "+count);
            if(count==0)continue;
            count-=count%2;
            for(int i=0;i<count;i++){if(buffer[i]!=0)nonzero++;observed++;}
            s.engine.process(buffer,count);
            write(s,buffer,count);
            s.positionMs+=count*1000L/(2*rate);
            if(observed>rate*2L*4L && nonzero==0){
                report(s,"warning","Sin audio capturable. La otra app podría bloquear la captura.");
                observed=0;
            }else if(observed>rate*2L*4L){observed=0;nonzero=0;}
            reportProgress(s);
        }
    }
    @Override public void onDestroy() {
        Session s=current;current=null;
        if(s!=null)s.cancel();
        try{unregisterReceiver(disconnectReceiver);}catch(Exception ignored){}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
