package io.github.eiriksb.sipher.mt;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OPUS-MT (Marian) translation with ONNX Runtime: an encoder plus a merged decoder with KV cache, as exported by
 * {@code optimum-cli export onnx --task text2text-generation-with-past}, decoded greedily.
 *
 * <p>Model directory layout: {@code encoder_model.onnx}, {@code decoder_model_merged.onnx}, {@code source.spm},
 * {@code vocab.json}, {@code config.json} and optionally {@code generation_config.json} / {@code target_vocab.json}.
 */
public final class MarianTranslator implements AutoCloseable {
    private static final String PAST_PREFIX = "past_key_values.";

    private final OrtEnvironment environment;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final SentencePiece sourcePieces;
    private final Map<String, Long> vocabulary;
    private final String[] targetPieces;
    private final List<String> pastNames = new ArrayList<>();
    private final Set<Long> suppressed = new HashSet<>();
    private final long decoderStart;
    private final long endOfSentence;
    private final long unknown;
    private final int maxPositions;
    private final int heads;
    private final int headSize;

    private MarianTranslator(Path directory, int threads) throws IOException, OrtException {
        JsonObject config = readJson(directory.resolve("config.json"));
        Path generationPath = directory.resolve("generation_config.json");
        JsonObject generation = Files.isRegularFile(generationPath) ? readJson(generationPath) : new JsonObject();

        decoderStart = setting(generation, config, "decoder_start_token_id").getAsLong();
        endOfSentence = setting(generation, config, "eos_token_id").getAsLong();
        long pad = setting(generation, config, "pad_token_id").getAsLong();
        JsonElement badWords = setting(generation, config, "bad_words_ids");
        if (badWords != null && badWords.isJsonArray()) {
            for (JsonElement ids : badWords.getAsJsonArray()) {
                JsonArray sequence = ids.getAsJsonArray();
                if (sequence.size() == 1) {
                    suppressed.add(sequence.get(0).getAsLong());
                }
            }
        }
        suppressed.add(pad);
        maxPositions = config.get("max_position_embeddings").getAsInt();
        heads = config.get("decoder_attention_heads").getAsInt();
        headSize = config.get("d_model").getAsInt() / heads;

        vocabulary = readVocabulary(directory.resolve("vocab.json"));
        Path targetVocabularyPath = directory.resolve("target_vocab.json");
        Map<String, Long> targetVocabulary = Files.isRegularFile(targetVocabularyPath) ? readVocabulary(targetVocabularyPath) : vocabulary;
        targetPieces = new String[targetVocabulary.values().stream().mapToInt(Long::intValue).max().orElse(0) + 1];
        targetVocabulary.forEach((piece, id) -> targetPieces[id.intValue()] = piece);
        unknown = vocabulary.getOrDefault("<unk>", 1L);
        sourcePieces = SentencePiece.load(directory.resolve("source.spm"));

        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, threads));
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            encoder = environment.createSession(directory.resolve("encoder_model.onnx").toString(), options);
            decoder = environment.createSession(directory.resolve("decoder_model_merged.onnx").toString(), options);
        }
        for (String input : decoder.getInputNames()) {
            if (input.startsWith(PAST_PREFIX)) {
                pastNames.add(input);
            }
        }
    }

    public static MarianTranslator load(Path directory, int threads) throws IOException, OrtException {
        return new MarianTranslator(directory, threads);
    }

    /**
     * Translates text sentence by sentence (OPUS-MT tends to drop sentences when given several at once).
     *
     * @param targetToken target language token for multilingual models, such as {@code >>nob<<}; {@code null} if none
     */
    public synchronized String translate(String text, String targetToken) throws OrtException {
        List<String> translated = new ArrayList<>();
        for (String sentence : sentences(text)) {
            String result = translateSentence(sentence, targetToken);
            if (!result.isEmpty()) {
                translated.add(result);
            }
        }
        return String.join(" ", translated);
    }

    /** Translates the text as a single unit, without sentence splitting. */
    synchronized String translateSentence(String sentence, String targetToken) throws OrtException {
        long[] input = encode(sentence, targetToken);
        long[] mask = new long[input.length];
        java.util.Arrays.fill(mask, 1L);
        long[] shape = {1, input.length};
        // Captions are short: stop runaway generation well before the position table runs out.
        int limit = Math.min(maxPositions - 1, input.length * 3 + 10);

        Map<String, OnnxTensor> feed = new HashMap<>();
        List<AutoCloseable> owned = new ArrayList<>();
        OrtSession.Result firstStep = null;
        OrtSession.Result previous = null;
        try {
            OnnxTensor inputIds = own(owned, OnnxTensor.createTensor(environment, LongBuffer.wrap(input), shape));
            OnnxTensor attention = own(owned, OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape));
            OrtSession.Result encoded = encoder.run(Map.of("input_ids", inputIds, "attention_mask", attention));
            owned.add(encoded);

            OnnxTensor empty = own(owned, OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), new long[]{1, heads, 0, headSize}));
            for (String name : pastNames) {
                feed.put(name, empty);
            }
            feed.put("encoder_attention_mask", attention);
            feed.put("encoder_hidden_states", (OnnxTensor) encoded.get(0));
            feed.put("input_ids", own(owned, OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[]{decoderStart}), new long[]{1, 1})));
            feed.put("use_cache_branch", own(owned, OnnxTensor.createTensor(environment, new boolean[]{false})));

            List<String> output = new ArrayList<>();
            for (int step = 0; step < limit; step++) {
                OrtSession.Result result = decoder.run(feed);
                long next = step == limit - 1 ? endOfSentence : argmax((OnnxTensor) result.get("logits").orElseThrow());
                if (step == 0) {
                    firstStep = result;
                } else if (previous != firstStep) {
                    previous.close();
                }
                previous = result;
                if (next == endOfSentence) {
                    break;
                }
                if (next != unknown && next < targetPieces.length && targetPieces[(int) next] != null) {
                    output.add(targetPieces[(int) next]);
                }

                // Cross-attention K/V only come out of the first step; later steps return placeholders for them.
                for (String name : pastNames) {
                    if (step == 0 || name.contains(".decoder.")) {
                        String present = "present." + name.substring(PAST_PREFIX.length());
                        feed.put(name, (OnnxTensor) result.get(present).orElseThrow());
                    }
                }
                feed.put("input_ids", own(owned, OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[]{next}), new long[]{1, 1})));
                feed.put("use_cache_branch", own(owned, OnnxTensor.createTensor(environment, new boolean[]{true})));
            }
            return SentencePiece.decode(output);
        } finally {
            if (previous != null && previous != firstStep) {
                previous.close();
            }
            if (firstStep != null) {
                firstStep.close();
            }
            for (AutoCloseable resource : owned.reversed()) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // Native memory; nothing more to do.
                }
            }
        }
    }

    long[] encode(String sentence, String targetToken) {
        List<Long> ids = new ArrayList<>();
        if (targetToken != null && !targetToken.isEmpty()) {
            Long token = vocabulary.get(targetToken);
            if (token == null) {
                throw new IllegalArgumentException("Model has no target token " + targetToken);
            }
            ids.add(token);
        }
        for (String piece : sourcePieces.encode(sentence)) {
            ids.add(vocabulary.getOrDefault(piece, unknown));
        }
        int keep = Math.min(ids.size(), maxPositions - 1);
        long[] input = new long[keep + 1];
        for (int i = 0; i < keep; i++) {
            input[i] = ids.get(i);
        }
        input[keep] = endOfSentence;
        return input;
    }

    private long argmax(OnnxTensor logits) {
        FloatBuffer values = logits.getFloatBuffer();
        long[] shape = logits.getInfo().getShape();
        int vocabularySize = (int) shape[2];
        int offset = (int) ((shape[1] - 1) * vocabularySize);
        int best = -1;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vocabularySize; i++) {
            float score = values.get(offset + i);
            if (score > bestScore && !suppressed.contains((long) i)) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static List<String> sentences(String text) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(text);
        List<String> sentences = new ArrayList<>();
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private static <T extends AutoCloseable> T own(List<AutoCloseable> owned, T resource) {
        owned.add(resource);
        return resource;
    }

    private static JsonElement setting(JsonObject generation, JsonObject config, String key) {
        JsonElement value = generation.get(key);
        return value != null && !value.isJsonNull() ? value : config.get(key);
    }

    private static JsonObject readJson(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static Map<String, Long> readVocabulary(Path path) throws IOException {
        Map<String, Long> vocabulary = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : readJson(path).entrySet()) {
            vocabulary.put(entry.getKey(), entry.getValue().getAsLong());
        }
        return vocabulary;
    }

    @Override
    public synchronized void close() throws OrtException {
        decoder.close();
        encoder.close();
    }
}
