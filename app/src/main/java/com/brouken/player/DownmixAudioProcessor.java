package com.brouken.player;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * Folds a decoded surround mix down to stereo, ahead of a route that cannot carry the channels and
 * would otherwise do it itself.
 *
 * <p>Two things make this worth doing here rather than leaving it to the platform. The platform's
 * fold has no headroom: it sums the centre and the surrounds into the front pair at the coefficients
 * of the standard matrix, on a signal that is already near full scale in a film, and clips whatever
 * comes out the top. That is heard as a crackle the moment a two-channel output is asked to carry a
 * decoded 5.1 track — an HDMI ARC connection most often, since ARC carries stereo PCM and nothing
 * wider. And the fold happens below everything this app can reach, so no gain, limiter or night mode
 * placed here can take the clipping back out again.
 *
 * <p>So the fold is done here, with the same coefficients, and then halved: six decibels of headroom
 * over the worst case a full-scale mix can reach, a little over one and a half. Whatever survives
 * that is bent over a soft knee rather than wrapped, so a loud moment gives up a little dynamic
 * range instead of turning into noise.
 *
 * <p>Only decoded PCM is touched. A bitstream is left alone: the receiver folds that itself, with
 * its own bass management and speaker distances, and no mix made here can match that.
 */
class DownmixAudioProcessor extends BaseAudioProcessor {

    /** The centre and the surrounds, at the level the standard fold gives them. */
    private static final float CENTRE_GAIN = 0.7071f;
    private static final float SURROUND_GAIN = 0.7071f;
    /** A back channel in 7.1, split between the pair: it sits between the side and the front. */
    private static final float BACK_GAIN = 0.5f;
    /** Half, six decibels: a full-scale 5.1 mix sums to 1.207, so this leaves the limiter room. */
    private static final float HEADROOM = 0.5f;
    /** Where the limiter starts to bend. Below this the mix passes through unchanged. */
    private static final float KNEE = 0.75f;

    // Both decided when the sink is built and never changed after: the chain is fixed for the life of
    // a player, and neither the preference nor the route's answer changes within one.
    private final boolean enabled;
    private final int routePcmChannels;

    // The fold in force, written by onConfigure and read on the playback thread. null = not folding.
    private float[][] gains;
    private int inputChannels;
    /** Said once per configuration, when the fold first runs out of headroom. */
    private boolean limited;

    DownmixAudioProcessor(boolean enabled, int routePcmChannels) {
        this.enabled = enabled;
        this.routePcmChannels = routePcmChannels;
    }

    /**
     * Per input channel, what it adds to the left and the right. Media3 delivers samples in the
     * ascending bit order of the channel mask, so these are the layouts those counts name:
     * FL, FR, FC, LFE, BL, BR, SL, SR.
     *
     * <p>The LFE gets no row of its own. Folding it in at any useful level would cost more headroom
     * than the rest of the mix put together, on a channel that carries rumble more than it carries
     * anything a viewer would miss in stereo.
     */
    private static float[][] layoutFor(int channelCount) {
        switch (channelCount) {
            case 3:  // FL FR FC
                return new float[][]{{1, 0}, {0, 1}, {CENTRE_GAIN, CENTRE_GAIN}};
            case 4:  // FL FR BL BR
                return new float[][]{{1, 0}, {0, 1}, {SURROUND_GAIN, 0}, {0, SURROUND_GAIN}};
            case 5:  // FL FR FC BL BR
                return new float[][]{{1, 0}, {0, 1}, {CENTRE_GAIN, CENTRE_GAIN},
                        {SURROUND_GAIN, 0}, {0, SURROUND_GAIN}};
            case 6:  // FL FR FC LFE BL BR
                return new float[][]{{1, 0}, {0, 1}, {CENTRE_GAIN, CENTRE_GAIN}, {0, 0},
                        {SURROUND_GAIN, 0}, {0, SURROUND_GAIN}};
            case 7:  // 6.1, its back centre split between the pair
                return new float[][]{{1, 0}, {0, 1}, {CENTRE_GAIN, CENTRE_GAIN}, {0, 0},
                        {BACK_GAIN, BACK_GAIN}, {SURROUND_GAIN, 0}, {0, SURROUND_GAIN}};
            case 8:  // FL FR FC LFE BL BR SL SR
                return new float[][]{{1, 0}, {0, 1}, {CENTRE_GAIN, CENTRE_GAIN}, {0, 0},
                        {BACK_GAIN, 0}, {0, BACK_GAIN}, {SURROUND_GAIN, 0}, {0, SURROUND_GAIN}};
            default:
                return null;
        }
    }

    /** Anything wider than 7.1: keep the seven-point-one part, fold the rest into the pair. */
    private static float[][] widen(int channelCount) {
        final float[][] base = layoutFor(8);
        final float[][] gains = new float[channelCount][];
        System.arraycopy(base, 0, gains, 0, base.length);
        for (int channel = base.length; channel < channelCount; channel++) {
            // Height channels, mostly, and they sit above the front pair: sent to it evenly.
            gains[channel] = (channel - base.length) % 2 == 0
                    ? new float[]{SURROUND_GAIN, 0} : new float[]{0, SURROUND_GAIN};
        }
        return gains;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        // A new configuration always starts unfolded, so a route change back to a wide one, or a
        // stereo track after a surround one, drops the matrix rather than reusing the old one.
        gains = null;
        final int channels = inputAudioFormat.channelCount;
        final boolean pcm = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
                || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
        if (!enabled || !pcm || channels <= 2
                || (routePcmChannels > 0 && routePcmChannels >= channels)) {
            // Nothing to fold: a bitstream (the receiver's business), a track that is already stereo,
            // or a route wide enough to carry the channels as they are. An unanswerable route — zero —
            // is folded anyway, since the whole point here is that the platform's own fold clips.
            return AudioFormat.NOT_SET;
        }
        float[][] fold = layoutFor(channels);
        if (fold == null) {
            if (channels < 8) {
                // Three, five, six, seven and eight are the layouts above; anything between them is
                // something this side of the sink is not told the mask for, and guessing would be
                // worse than leaving the platform to fold it.
                return AudioFormat.NOT_SET;
            }
            fold = widen(channels);
        }
        gains = fold;
        inputChannels = channels;
        limited = false;
        Utils.log("downmix: folding " + channels + " channels to stereo for a "
                + routePcmChannels + "-channel route");
        return new AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding);
    }

    /**
     * Linear below the knee, then a curve that climbs to one and stops there. Continuous, and with a
     * slope of one where it starts, so an ordinary peak is untouched and a loud one is rounded rather
     * than squared off.
     */
    private float bend(float sample) {
        final float magnitude = Math.abs(sample);
        if (magnitude <= KNEE) {
            return sample;
        }
        if (!limited) {
            limited = true;
            Utils.log("downmix: the fold reached the limiter, this mix is mastered at full scale");
        }
        final float over = magnitude - KNEE;
        final float shaped = KNEE + (1f - KNEE) * over / (over + (1f - KNEE));
        return sample < 0f ? -shaped : shaped;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!inputBuffer.hasRemaining()) {
            // The pipeline drains itself with the shared empty buffer, which replaceOutputBuffer(0)
            // would hand straight back — copying a buffer onto itself throws.
            return;
        }
        final float[][] gains = this.gains;
        final int channels = this.inputChannels;
        final boolean floats = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
        // Normalised as it is read, so the same knee applies to both encodings: 16-bit arrives at
        // short scale, and the matrix and the limiter are written for full scale of one.
        final float scale = floats ? 1f : 1f / 32768f;
        final int sampleBytes = floats ? 4 : 2;
        // Media3 hands whole frames, so the channel counter starts at the first channel of a frame
        // every time and needs nothing carried between buffers.
        final int frames = inputBuffer.remaining() / (sampleBytes * channels);
        final ByteBuffer outputBuffer = replaceOutputBuffer(frames * 2 * sampleBytes);
        for (int frame = 0; frame < frames; frame++) {
            float left = 0f;
            float right = 0f;
            for (int channel = 0; channel < channels; channel++) {
                final float sample = (floats ? inputBuffer.getFloat()
                        : inputBuffer.getShort() * scale);
                final float[] gain = gains[channel];
                if (gain[0] != 0f) {
                    left += sample * gain[0];
                }
                if (gain[1] != 0f) {
                    right += sample * gain[1];
                }
            }
            final float outLeft = bend(left * HEADROOM);
            final float outRight = bend(right * HEADROOM);
            if (floats) {
                outputBuffer.putFloat(outLeft);
                outputBuffer.putFloat(outRight);
            } else {
                outputBuffer.putShort(quantize(outLeft));
                outputBuffer.putShort(quantize(outRight));
            }
        }
        outputBuffer.flip();
    }

    /**
     * The full scale of a short is 32768 below zero and 32767 above it, so a sample the limiter has
     * brought to exactly one still has to be held down a step to fit.
     */
    private static short quantize(float sample) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * 32768f)));
    }
}
