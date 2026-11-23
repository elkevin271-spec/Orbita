import hn.orbita.SpatialEngine;
import hn.orbita.DemoKnocks;
public final class AudioEngineTest {
    private static void ok(boolean value, String name) {
        if(!value)throw new AssertionError(name);
        System.out.println("✓ " + name);
    }
    public static void main(String[] args) {
        int sr=48000;
        SpatialEngine e = new SpatialEngine(sr);
        short[] silence=new short[2048]; e.process(silence, silence.length);
        boolean allZero=true;for(short x:silence)if(x!=0)allZero=false;
        ok(allZero,"silencio no genera ruido espurio");
        e.configure(new SpatialEngine.Parameters(1f,.27f,.43f,1f,.3f,SpatialEngine.MUSIC));
        short[] tone = new short[48000];
        for(int n=0;n<tone.length/2;n++){
            short v=(short)(11000*Math.sin(2*Math.PI*440*n/sr));
            tone[2*n]=v; tone[2*n+1]=v;
        }
        e.process(tone,tone.length);
        long energy=0, difference=0;
        for(int n=0;n<tone.length/2;n++){
            energy+=Math.abs((int)tone[2*n])+Math.abs((int)tone[2*n+1]);
            difference+=Math.abs(tone[2*n]-tone[2*n+1]);
        }
        ok(energy>10000000,"el motor emite audio real");
        ok(difference>100000,"el procesamiento produce diferencias binaurales");
        DemoKnocks knocks=new DemoKnocks(sr);
        short[] mono=new short[1024], stereo=new short[2048];
        SpatialEngine demoEngine=new SpatialEngine(sr);
        long demoEnergy=0;
        for(int pos=0;pos<sr*13;pos+=1024){
            int count=Math.min(1024,sr*13-pos);
            knocks.generate(mono,pos,count);
            int group=DemoKnocks.groupAt((double)pos/sr);
            demoEngine.processObject(mono,count,stereo,DemoKnocks.AZIMUTHS[group],DemoKnocks.ELEVATIONS[group],DemoKnocks.DISTANCES[group]);
            for(int i=0;i<count*2;i++)demoEnergy+=Math.abs((int)stereo[i]);
        }
        ok(demoEnergy>100000,"golpes de puerta sintéticos audibles y espacializados");
        SpatialEngine balanceEngine=new SpatialEngine(sr);
        balanceEngine.configure(new SpatialEngine.Parameters(0f,.1f,0f,0f,0f,1f,SpatialEngine.NATURAL));
        short[] balanced=new short[20000];
        for(int k=0;k<balanced.length;k++)balanced[k]=10000;
        balanceEngine.process(balanced,balanced.length);
        ok(Math.abs(balanced[18000])<200 && balanced[18001]>1000,"control de balance atenúa el oído opuesto");
        try {e.process(new short[3],3);throw new AssertionError("Desalineación no detectada");}
        catch(IllegalArgumentException expected){System.out.println("✓ rechaza PCM estéreo incompleto");}
        System.out.println("TODAS LAS PRUEBAS DSP PASARON");
    }
}
