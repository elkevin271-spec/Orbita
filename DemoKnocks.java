package hn.orbita;

/** Reproducible and original dry wood-door knock synthesis for a spatial listening test. */
public final class DemoKnocks {
    public static final int RATE=48000;
    public static final float LENGTH_SECONDS=13f;
    private static final float[] ONSETS={.7f,1.00f,1.32f,3.25f,3.58f,5.75f,6.10f,8.30f,8.64f,10.65f,11.00f};
    private static final int[] GROUPS={0,0,0,1,1,2,2,3,3,4,4};
    // Front left, behind right, directly behind, far front right, near left.
    public static final float[] AZIMUTHS={-62,125,180,38,-95};
    public static final float[] DISTANCES={1.3f,1.0f,.85f,4.2f,.55f};
    public static final float[] ELEVATIONS={0,0,0,13,-6};
    private int state=0x5107db91;
    private float noiseMemory;
    private final int rate;
    public DemoKnocks(int rate){this.rate=rate;}
    public static int groupAt(double seconds) {
        int group=0;
        for(int i=0;i<ONSETS.length;i++)if(seconds+0.00001>=ONSETS[i])group=GROUPS[i];
        return group;
    }
    public void generate(short[] mono,int startFrame,int frames) {
        for(int i=0;i<frames;i++){
            double t=(startFrame+i)/(double)rate;
            float sample=0f;
            for(int k=0;k<ONSETS.length;k++){
                double age=t-ONSETS[k];
                if(age<0||age>.34)continue;
                // Resonances of a small wooden door plus a low thump and noisy attack.
                double a=Math.exp(-age*30.5);
                double wood=(Math.sin(2*Math.PI*155*age)*.56
                            +Math.sin(2*Math.PI*302*age)*.33
                            +Math.sin(2*Math.PI*578*age)*.14
                            +Math.sin(2*Math.PI*1110*age)*.07);
                sample += (float)(wood*a*.60);
                state^=state<<13;state^=state>>>17;state^=state<<5;
                float random=(state&0xffff)/32767.5f-1f;
                noiseMemory=.5f*noiseMemory+.5f*random;
                sample+=(float)(noiseMemory*.28*Math.exp(-age*170));
            }
            sample=Math.max(-.96f,Math.min(.96f,sample));
            mono[i]=(short)Math.round(sample*32767f);
        }
    }
}
