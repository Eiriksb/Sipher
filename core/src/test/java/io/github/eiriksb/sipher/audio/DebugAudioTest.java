package io.github.eiriksb.sipher.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugAudioTest {
    @Test
    void readsTheTestRecordingAt48kHz() throws Exception {
        Path file = Path.of(DebugAudioTest.class.getResource("/audio/jfk.wav").toURI());
        AudioFileFormat original = AudioSystem.getAudioFileFormat(file.toFile());
        double seconds = original.getFrameLength() / original.getFormat().getSampleRate();

        short[] samples = DebugAudio.read(file);

        assertEquals(seconds, samples.length / (double) DebugAudio.SAMPLE_RATE, 0.001);
    }

    @Test
    void cutsTwentyMillisecondFramesFollowedBySilence() {
        short[] samples = new short[DebugAudio.FRAME_SAMPLES * 2 + 10];
        Arrays.fill(samples, (short) 1000);

        short[][] frames = DebugAudio.frames(samples);

        for (short[] frame : frames) {
            assertEquals(DebugAudio.FRAME_SAMPLES, frame.length);
        }
        assertEquals(1000, frames[2][9]);
        assertEquals(0, frames[2][10], "the last frame is padded with silence");
        assertTrue(frames.length > 3);
        assertArrayEquals(new short[DebugAudio.FRAME_SAMPLES], frames[frames.length - 1], "then silence");
    }

    @Test
    void resamplesByInterpolating() {
        short[] out = DebugAudio.resample(new short[] {0, 300}, 24_000);
        assertEquals(4, out.length);
        assertEquals(0, out[0]);
        assertEquals(150, out[1]);
        assertEquals(300, out[2]);
    }
}
