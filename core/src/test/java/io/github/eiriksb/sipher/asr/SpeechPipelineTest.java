package io.github.eiriksb.sipher.asr;

import io.github.eiriksb.sipher.audio.DebugAudio;
import io.github.eiriksb.sipher.models.BuiltinModels;
import io.github.eiriksb.sipher.testing.TestDirectories;
import io.github.eiriksb.sipher.runtime.NativeRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end through the real natives and the bundled English model: a recording is fed in as Simple Voice Chat would
 * deliver it (48 kHz, 20 ms frames), followed by silence as if push-to-talk was released.
 */
class SpeechPipelineTest {
    @Test
    void transcribesVoiceChatFrames() throws Exception {
        NativeRuntime.Status natives = NativeRuntime.load(TestDirectories.sipher());
        TestDirectories.requireNatives(natives.ready(), "natives unavailable on this platform: " + natives.message());
        BuiltinModels models = BuiltinModels.extract(TestDirectories.sipher());

        List<String> partials = new CopyOnWriteArrayList<>();
        List<String> finals = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        try (SpeechDetector detector = new SpeechDetector(models.sileroVad(), 0.5f, 0.35f);
             SpeechRecognizer recognizer = SpeechRecognizer.create(models.englishSpeech(), 2);
             SpeechPipeline pipeline = new SpeechPipeline("test", detector, recognizer,
                     new SpeechPipeline.Settings(() -> 400, 300), new SpeechPipeline.Listener() {
                 @Override
                 public void onPartial(int utterance, String text) {
                     partials.add(text);
                 }

                 @Override
                 public void onFinal(int utterance, String text) {
                     finals.add(text);
                     if (String.join(" ", finals).toLowerCase(Locale.ROOT).contains("for your country")) {
                         done.countDown();
                     }
                 }
             })) {
            short[] pcm = DebugAudio.read(Path.of(SpeechPipelineTest.class.getResource("/audio/jfk.wav").toURI()));
            for (int offset = 0; offset < pcm.length; offset += 960) {
                short[] frame = new short[960];
                System.arraycopy(pcm, offset, frame, 0, Math.min(960, pcm.length - offset));
                pipeline.offer(frame);
                Thread.sleep(10); // twice real time: live captions are paced by the wall clock
            }
            assertTrue(done.await(20, TimeUnit.SECONDS), "finals: " + finals + ", partials: " + partials);
        }

        System.out.println("partials: " + partials + "\nfinals: " + finals);
        String transcript = String.join(" ", finals).toLowerCase(Locale.ROOT);
        assertTrue(transcript.contains("ask not what your country can do for you"), transcript);
        assertTrue(!partials.isEmpty(), "expected live partial captions while speaking");
    }
}
