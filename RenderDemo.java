import hn.orbita.SpatialEngine;
import hn.orbita.DemoKnocks;
import java.io.FileOutputStream;
import java.io.IOException;

/** Offline proof-of-audio renderer. Produces a real stereo PCM WAV from the same Java DSP. */
public final class RenderDemo {
    private static void le16(FileOutputStream out,int value)throws IOException {
        out.write(value&255);out.write((value>>8)&255);
    }
    private static void le32(FileOutputStream out,long value)throws IOException {
        le16(out,(int)value&0xffff);le16(out,(int)(value>>16)&0xffff);
    }
    public static void main(String[] args)throws IOException {
        if(args.length!=1)throw new IllegalArgumentException("Salida WAV requerida");
        int rate=DemoKnocks.RATE,frames=(int)(DemoKnocks.LENGTH_SECONDS*rate);
        DemoKnocks knocks=new DemoKnocks(rate);
        SpatialEngine engine=new SpatialEngine(rate);
        engine.configure(new SpatialEngine.Parameters(.42f,.2f,.34f,1f,.30f,SpatialEngine.NATURAL));
        try(FileOutputStream output=new FileOutputStream(args[0])) {
            output.write(new byte[]{'R','I','F','F'});le32(output,36L+frames*4L);
            output.write(new byte[]{'W','A','V','E','f','m','t',' '});
            le32(output,16);le16(output,1);le16(output,2);le32(output,rate);
            le32(output,rate*4L);le16(output,4);le16(output,16);
            output.write(new byte[]{'d','a','t','a'});le32(output,frames*4L);
            short[] mono=new short[512],stereo=new short[1024];
            for(int frame=0;frame<frames;) {
                int count=Math.min(mono.length,frames-frame);
                knocks.generate(mono,frame,count);
                int group=DemoKnocks.groupAt((double)frame/rate);
                engine.processObject(mono,count,stereo,DemoKnocks.AZIMUTHS[group],
                    DemoKnocks.ELEVATIONS[group],DemoKnocks.DISTANCES[group]);
                for(int i=0;i<count*2;i++){short x=stereo[i];output.write(x&255);output.write((x>>>8)&255);}
                frame+=count;
            }
        }
    }
}
