package hn.orbita;

/**
 * ÓRBITA SPATIAL – binaural parametric renderer, pure Java (no permissions or network).
 *
 * Engineering note: this is a LOW-LATENCY HEAD MODEL, not a measured SOFA/HRIR data set.
 * It models interaural time (ITD), interaural level (ILD), head shadow filtering,
 * front/back pinna-comb cues, distance, early reflections and controlled cross-room tails.
 * For a research-grade measured HRTF, replace VirtualSpeaker with SOFA HRIR convolution.
 * A 16-bit stereo PCM stream is the only source; it cannot magically isolate instruments.
 */
public final class SpatialEngine {
    public static final int MUSIC = 0, CINEMA = 1, NATURAL = 2;

    public static final class Parameters {
        public final float motion, speed, room, mix, distance, balance;
        public final int preset;
        public Parameters(float motion, float speed, float room, float mix, float distance, int preset) {
            this(motion,speed,room,mix,distance,0f,preset);
        }
        public Parameters(float motion, float speed, float room, float mix, float distance, float balance, int preset) {
            this.motion = clamp01(motion);
            this.speed = clamp01(speed);
            this.room = clamp01(room);
            this.mix = clamp01(mix);
            this.distance = clamp01(distance);
            this.balance = Float.isFinite(balance) ? Math.max(-1f,Math.min(1f,balance)):0f;
            this.preset = preset >= 0 && preset <= 2 ? preset : MUSIC;
        }
    }

    private final int sampleRate;
    private final VirtualSpeaker speakerA, speakerB, demoSpeaker;
    private final float[] roomLeft, roomRight;
    private int roomPosL, roomPosR;
    private volatile Parameters parameters = new Parameters(.42f, .2f, .25f, .82f, .30f, NATURAL);
    private double orbitPhase;
    private float smoothMix=.82f, smoothRoom=.25f, smoothDistance=.30f, smoothMotion=.42f, smoothBalance=0;

    public SpatialEngine(int sampleRate) {
        if (sampleRate < 8000 || sampleRate > 192000) throw new IllegalArgumentException("Frecuencia PCM fuera de rango");
        this.sampleRate = sampleRate;
        speakerA = new VirtualSpeaker(sampleRate);
        speakerB = new VirtualSpeaker(sampleRate);
        demoSpeaker = new VirtualSpeaker(sampleRate);
        roomLeft = new float[Math.max(128, (int)(sampleRate * .0397))];
        roomRight = new float[Math.max(128, (int)(sampleRate * .0563))];
    }

    public void configure(Parameters newParameters) {
        if(newParameters != null) parameters = newParameters;
    }

    private static float clamp01(float x) {
        return Float.isFinite(x) ? Math.min(1f, Math.max(0f, x)) : 0f;
    }
    private static short toShort(float x) {
        // Fixed headroom plus a gentle peak limiter for large transients.
        float v = x * .89f;
        if (v > .94f) v = .94f + .06f * ((v-.94f)/(1f+Math.abs(v-.94f)));
        if (v < -.94f) v = -.94f + .06f * ((v+.94f)/(1f+Math.abs(v+.94f)));
        return (short)Math.round(Math.max(-1f,Math.min(1f,v)) * 32767f);
    }

    public void process(short[] pcm, int sampleCount) {
        if (sampleCount < 0 || sampleCount > pcm.length || (sampleCount & 1) != 0)
            throw new IllegalArgumentException("Audio PCM estéreo desalineado");
        Parameters p = parameters;
        final double rate = 2.0 * Math.PI * (.022 + .33 * p.speed) / sampleRate;
        for (int i=0; i<sampleCount; i+=2) {
            smoothMix += (p.mix-smoothMix)*.0018f;
            smoothRoom += (p.room-smoothRoom)*.0018f;
            smoothMotion += (p.motion-smoothMotion)*.0018f;
            smoothDistance += (p.distance-smoothDistance)*.0018f;
            smoothBalance += (p.balance-smoothBalance)*.0018f;
            float l = pcm[i] / 32768f;
            float r = pcm[i+1] / 32768f;

            // Two true stereo sources are retained, rather than collapsing everything to mono.
            // Natural preset keeps their virtual stage almost stationary for externalization.
            float azL, azR, elevation=0;
            if (p.preset == CINEMA) {
                azL = -68f + 95f*smoothMotion*(float)Math.sin(orbitPhase);
                azR =  68f + 95f*smoothMotion*(float)Math.sin(orbitPhase+.60);
                elevation = 7f;
            } else if (p.preset == NATURAL) {
                azL = -38f + 24f*smoothMotion*(float)Math.sin(orbitPhase);
                azR =  38f + 24f*smoothMotion*(float)Math.sin(orbitPhase+.5);
            } else {
                azL = -47f + 155f*smoothMotion*(float)Math.sin(orbitPhase);
                azR =  47f + 155f*smoothMotion*(float)Math.sin(orbitPhase+1.05);
            }
            float distanceMeters = .6f + 3.4f*smoothDistance;
            speakerA.render(l,azL,elevation,distanceMeters);
            speakerB.render(r,azR,elevation,distanceMeters);
            float wetL = .72f*(speakerA.outLeft + speakerB.outLeft);
            float wetR = .72f*(speakerA.outRight + speakerB.outRight);
            float[] a = spatialRoom(wetL,wetR,smoothRoom);
            float finalL = l*(1f-smoothMix) + smoothMix*a[0];
            float finalR = r*(1f-smoothMix) + smoothMix*a[1];
            pcm[i] = toShort(finalL*(1f-Math.max(0f,smoothBalance)));
            pcm[i+1] = toShort(finalR*(1f+Math.min(0f,smoothBalance)));
            orbitPhase += rate;
            if (orbitPhase >= Math.PI*2) orbitPhase -= Math.PI*2;
        }
    }

    // External 3D source for realistic localized demo (door knocks at stated positions).
    public void processObject(short[] mono, int frames, short[] stereo, float azimuthDeg,
                              float elevationDeg, float distanceMeters) {
        if(frames < 0 || frames > mono.length || frames*2 > stereo.length)
            throw new IllegalArgumentException("Buffer incorrecto");
        Parameters p = parameters;
        for (int i=0; i<frames; i++) {
            smoothRoom += (p.room-smoothRoom)*.0018f;
            smoothMix += (p.mix-smoothMix)*.0018f;
            smoothDistance += (p.distance-smoothDistance)*.0018f;
            smoothBalance += (p.balance-smoothBalance)*.0018f;
            float demoDistance=Math.max(.3f,distanceMeters+(smoothDistance-.30f)*2f);
            demoSpeaker.render(mono[i]/32768f,azimuthDeg,elevationDeg,demoDistance);
            float[] w = spatialRoom(demoSpeaker.outLeft,demoSpeaker.outRight,smoothRoom);
            float dry=(mono[i]/32768f)*.48f;
            stereo[2*i] = toShort((w[0]*smoothMix +dry*(1f-smoothMix))*(1f-Math.max(0f,smoothBalance)));
            stereo[2*i+1] = toShort((w[1]*smoothMix +dry*(1f-smoothMix))*(1f+Math.min(0f,smoothBalance)));
        }
    }

    // Reusable tiny array avoids per-sample allocation on the real-time audio thread.
    private final float[] roomResult=new float[2];
    private float[] spatialRoom(float left,float right,float amount) {
        float delayedL = roomLeft[roomPosL];
        float delayedR = roomRight[roomPosR];
        roomLeft[roomPosL] = .24f*left + .31f*delayedR;
        roomRight[roomPosR] = .24f*right + .31f*delayedL;
        if(++roomPosL == roomLeft.length) roomPosL=0;
        if(++roomPosR == roomRight.length) roomPosR=0;
        roomResult[0]=left+.42f*amount*(delayedL+.35f*delayedR);
        roomResult[1]=right+.42f*amount*(delayedR+.35f*delayedL);
        return roomResult;
    }

    /** A compact virtual ear model with continuous fractional ITD and spectral filtering. */
    private static final class VirtualSpeaker {
        private final int rate, mask;
        private final float[] history;
        private int write;
        private float leftLow, rightLow;
        private float smoothAz=0, smoothDistance=1;
        float outLeft, outRight;
        VirtualSpeaker(int sampleRate) {
            rate=sampleRate;
            int len=1;
            while(len<sampleRate*.050f+256)len <<= 1;
            history=new float[len];mask=len-1;
        }
        private float delayed(float offset) {
            float where=write-offset;
            int base=(int)Math.floor(where);
            float frac=where-base;
            return history[base & mask]*(1f-frac)+history[(base+1) & mask]*frac;
        }
        void render(float mono,float targetAz,float elevation,float targetDistance) {
            // Smooth abrupt preset / source transitions; shortest-angle interpolation.
            float delta=targetAz-smoothAz;
            while(delta>180)delta-=360;
            while(delta< -180)delta+=360;
            smoothAz += .006f*delta;
            smoothDistance += .0025f*(Math.max(.3f,Math.min(8f,targetDistance))-smoothDistance);
            history[write]=mono;
            float a=(float)(smoothAz*Math.PI/180.0);
            float lateral=(float)Math.sin(a);
            float rear=Math.max(0f,-(float)Math.cos(a));
            float lateralAbs=Math.abs(lateral);
            float deltaSamples=.00065f*rate*lateral;
            float airDelay=Math.max(0f,(smoothDistance-.5f))*.0020f*rate;
            float dl=airDelay + Math.max(0f, deltaSamples);
            float dr=airDelay + Math.max(0f,-deltaSamples);
            float earL=delayed(dl), earR=delayed(dr);
            // A small pinna-related comb notch helps distinguish rear and elevation,
            // but this is not individualized / measured HRTF anatomy.
            float notchSamples=(.00014f+Math.abs(elevation)*.0000022f)*rate;
            float notch=.07f+.19f*rear+.09f*Math.min(1f,Math.abs(elevation)/75f);
            earL-=notch*delayed(dl+notchSamples);
            earR-=notch*delayed(dr+notchSamples);
            float shadowL=Math.max(0f,lateral);
            float shadowR=Math.max(0f,-lateral);
            // Lower cut-off / attenuation for the contralateral ear.
            float coeffL=.80f-.64f*shadowL-.14f*rear;
            float coeffR=.80f-.64f*shadowR-.14f*rear;
            leftLow += Math.max(.08f,coeffL)*(earL-leftLow);
            rightLow+= Math.max(.08f,coeffR)*(earR-rightLow);
            float gainL=1f-.42f*shadowL;
            float gainR=1f-.42f*shadowR;
            float attenuation=1f/(.70f+.31f*smoothDistance);
            outLeft =leftLow*gainL*attenuation;
            outRight=rightLow*gainR*attenuation;
            write=(write+1)&mask;
        }
    }
}
