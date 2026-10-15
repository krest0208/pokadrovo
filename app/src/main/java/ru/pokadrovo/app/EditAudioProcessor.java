package ru.pokadrovo.app;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Per-clip volume, stereo balance and linear fade for 16-bit PCM. */
@androidx.media3.common.util.UnstableApi
public final class EditAudioProcessor extends BaseAudioProcessor {
    private final EditProject.Clip clip;
    private long frames;
    public EditAudioProcessor(EditProject.Clip c){clip=c;}
    @Override protected AudioFormat onConfigure(AudioFormat input) throws AudioProcessor.UnhandledAudioFormatException {
        if(input.encoding!=C.ENCODING_PCM_16BIT)throw new AudioProcessor.UnhandledAudioFormatException(input);
        return input;
    }
    @Override protected void onFlush(){frames=0;}
    @Override public void queueInput(ByteBuffer input){
        int length=input.remaining(),channels=inputAudioFormat.channelCount;
        ByteBuffer output=replaceOutputBuffer(length).order(ByteOrder.LITTLE_ENDIAN);
        input.order(ByteOrder.LITTLE_ENDIAN);
        long totalFrames=(long)(clip.duration()/1000.0*inputAudioFormat.sampleRate);
        long fadeInFrames=(long)(clip.fadeIn*inputAudioFormat.sampleRate);
        long fadeOutFrames=(long)(clip.fadeOut*inputAudioFormat.sampleRate);
        while(input.remaining()>=channels*2){
            float vol=clip.volume;
            if(fadeInFrames>0)vol*=Math.min(1,(float)frames/fadeInFrames);
            if(fadeOutFrames>0)vol*=Math.min(1,Math.max(0,(float)(totalFrames-frames)/fadeOutFrames));
            for(int ch=0;ch<channels;ch++){
                float gain=vol;
                if(channels==2){
                    if(ch==0)gain*=Math.min(1f,1f-clip.pan); else gain*=Math.min(1f,1f+clip.pan);
                }
                int sample=input.getShort();int value=Math.round(sample*gain);
                output.putShort((short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,value)));
            }
            frames++;
        }
        while(input.hasRemaining())output.put(input.get());
        output.flip();
    }
}
