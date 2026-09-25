package io.github.eiriksb.sipher.asr;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranscriptFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "...", "Mm.", "Uh, um.", "hmm hmm", "[Music]", "(coughs)", "*laughs*"})
    void dropsNoise(String text) {
        assertEquals("", TranscriptFilter.clean(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Watch out, a creeper!", "Um, can you help me?", "OK.", "42"})
    void keepsSpeech(String text) {
        assertEquals(text, TranscriptFilter.clean(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  hello   there  "})
    void normalisesWhitespace(String text) {
        assertEquals("hello there", TranscriptFilter.clean(text));
    }
}
