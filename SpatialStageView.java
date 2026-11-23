package hn.orbita;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Lightweight illustration of a virtual soundstage; not a device-position sensor. */
public final class SpatialStageView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float motion=.3f, speed=.2f;
    private boolean playing;
    private long base;
    private final int green=0xff39E4B8, sky=0xff79AFFF;
    public SpatialStageView(Context context) {super(context);base=SystemClock.elapsedRealtime();setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    public void setScene(float motion,float speed,boolean playing) {
        this.motion=motion;this.speed=speed;
        if(playing&&!this.playing)base=SystemClock.elapsedRealtime();
        this.playing=playing;invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w=getWidth(),h=getHeight(),cx=w*.5f,cy=h*.50f;
        float radius=Math.min(w*.42f,h*.43f);
        p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,0,w,h,
            new int[]{0xff14243A,0xff0D1B2D,0xff152337},null,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(0,0,w,h),25,25,p);p.setShader(null);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);
        for(int i=1;i<=3;i++){
            p.setColor(i==3?0x6676B8D0:0x334AB19B);
            canvas.drawCircle(cx,cy,radius*i/3f,p);
        }
        p.setColor(0x4479AFFF);
        canvas.drawLine(cx-radius,cy,cx+radius,cy,p);
        canvas.drawLine(cx,cy-radius,cx,cy+radius,p);
        p.setColor(0xff243A52);p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx,cy,22,p);
        p.setColor(green);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4f);
        canvas.drawArc(new RectF(cx-25,cy-25,cx+25,cy+25),196,145,false,p);
        p.setStrokeWidth(2f);
        canvas.drawLine(cx-27,cy-5,cx-27,cy+9,p);
        canvas.drawLine(cx+27,cy-5,cx+27,cy+9,p);
        float elapsed=(SystemClock.elapsedRealtime()-base)/1000f;
        double phase=playing?elapsed*(.15+.50*speed):-.2;
        double az1=-Math.PI*.30 + motion*Math.sin(phase)*1.9;
        double az2=Math.PI*.30 + motion*Math.sin(phase+.9)*1.9;
        renderPoint(canvas,cx,cy,radius,az1,green);
        renderPoint(canvas,cx,cy,radius,az2,sky);
        p.setStyle(Paint.Style.FILL);p.setColor(0xffC5D7E6);p.setTextSize(14);
        p.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("ESCENA VIRTUAL 360°",cx,28,p);
        p.setColor(0xff8B9FB4);p.setTextSize(12);
        canvas.drawText(playing?"Simulación de posición · reproduciendo":"Auriculares estéreo recomendados",cx,h-19,p);
        if(playing)postInvalidateDelayed(45);
    }
    private void renderPoint(Canvas canvas,float cx,float cy,float radius,double az,int color){
        float x=cx+(float)Math.sin(az)*radius*.83f;
        float y=cy-(float)Math.cos(az)*radius*.83f;
        p.setStyle(Paint.Style.FILL);p.setColor(color & 0x33ffffff);canvas.drawCircle(x,y,17,p);
        p.setColor(color);canvas.drawCircle(x,y,6,p);
    }
}
