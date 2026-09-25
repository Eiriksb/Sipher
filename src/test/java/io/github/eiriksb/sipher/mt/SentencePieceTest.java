package io.github.eiriksb.sipher.mt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Compares the Java tokenizer against Google's sentencepiece library (golden output generated in Python). */
class SentencePieceTest {
    @Test
    void matchesSentencepieceOnGoldenInputs() throws Exception {
        SentencePiece model = SentencePiece.load(Path.of(SentencePieceTest.class.getResource("/mt/opus-mt_tiny_eng-spa.spm").toURI()));
        JsonObject golden = golden();
        JsonArray texts = golden.getAsJsonArray("texts");
        JsonArray tokens = golden.getAsJsonArray("tokens");
        for (int i = 0; i < texts.size(); i++) {
            List<String> expected = new ArrayList<>();
            tokens.get(i).getAsJsonArray().forEach(piece -> expected.add(piece.getAsString()));
            assertEquals(expected, model.encode(texts.get(i).getAsString()), "input: " + texts.get(i));
        }
    }

    @Test
    void decodesPiecesBackToText() {
        assertEquals("Hola, ¿qué tal?", SentencePiece.decode(List.of("▁Hola", ",", "▁¿", "qué", "▁tal", "?")));
    }

    static JsonObject golden() throws Exception {
        try (var reader = new InputStreamReader(SentencePieceTest.class.getResourceAsStream("/mt/opus-mt_tiny_eng-spa.golden.json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
