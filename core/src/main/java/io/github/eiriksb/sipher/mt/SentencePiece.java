package io.github.eiriksb.sipher.mt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java SentencePiece <em>unigram</em> tokenizer, enough for OPUS-MT/Marian models: reads the {@code .spm}
 * protobuf directly, applies the precompiled {@code nmt_nfkc} normaliser and segments with Viterbi, matching the
 * reference implementation's output. Avoids shipping a native tokenizer for every platform.
 */
public final class SentencePiece {
    static final String SPACE = "▁";

    private static final int NORMAL = 1;
    private static final int UNKNOWN = 2;
    private static final int USER_DEFINED = 4;
    private static final int UNIGRAM = 1;
    private static final float UNKNOWN_PENALTY = 10f;

    private final List<String> pieces = new ArrayList<>();
    private final List<Float> scores = new ArrayList<>();
    private final List<Integer> types = new ArrayList<>();
    private final Map<String, Integer> pieceIds = new HashMap<>();
    private final Charsmap charsmap;
    private final boolean addDummyPrefix;
    private final boolean removeExtraWhitespaces;
    private final boolean escapeWhitespaces;
    private final int unknownId;
    private final float minScore;
    private final float maxScore;
    private final int maxPieceLength;

    private SentencePiece(byte[] model) {
        int modelType = UNIGRAM;
        byte[] precompiled = null;
        boolean dummyPrefix = true;
        boolean removeWhitespace = true;
        boolean escapeWhitespace = true;

        Proto proto = new Proto(model, 0, model.length);
        while (proto.hasNext()) {
            proto.next();
            switch (proto.field) {
                case 1 -> readPiece(proto.bytes());
                case 2 -> {
                    Proto trainer = proto.message();
                    while (trainer.hasNext()) {
                        trainer.next();
                        if (trainer.field == 3) {
                            modelType = (int) trainer.varint;
                        }
                    }
                }
                case 3 -> {
                    Proto normalizer = proto.message();
                    while (normalizer.hasNext()) {
                        normalizer.next();
                        switch (normalizer.field) {
                            case 2 -> precompiled = normalizer.bytes();
                            case 3 -> dummyPrefix = normalizer.varint != 0;
                            case 4 -> removeWhitespace = normalizer.varint != 0;
                            case 5 -> escapeWhitespace = normalizer.varint != 0;
                            default -> {
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (modelType != UNIGRAM) {
            throw new IllegalArgumentException("Only unigram SentencePiece models are supported (got type " + modelType + ")");
        }

        int unknown = 0;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        int longest = 1;
        for (int id = 0; id < pieces.size(); id++) {
            int type = types.get(id);
            if (type == NORMAL || type == USER_DEFINED) {
                pieceIds.put(pieces.get(id), id);
                longest = Math.max(longest, pieces.get(id).codePointCount(0, pieces.get(id).length()));
            }
            if (type == NORMAL) {
                min = Math.min(min, scores.get(id));
                max = Math.max(max, scores.get(id));
            }
            if (type == UNKNOWN) {
                unknown = id;
            }
        }
        this.unknownId = unknown;
        this.minScore = min;
        this.maxScore = max;
        this.maxPieceLength = longest;
        this.charsmap = precompiled == null || precompiled.length == 0 ? null : new Charsmap(precompiled);
        this.addDummyPrefix = dummyPrefix;
        this.removeExtraWhitespaces = removeWhitespace;
        this.escapeWhitespaces = escapeWhitespace;
    }

    public static SentencePiece load(Path model) throws IOException {
        return new SentencePiece(Files.readAllBytes(model));
    }

    private void readPiece(byte[] message) {
        String piece = "";
        float score = 0f;
        int type = NORMAL;
        Proto proto = new Proto(message, 0, message.length);
        while (proto.hasNext()) {
            proto.next();
            switch (proto.field) {
                case 1 -> piece = new String(proto.bytes(), StandardCharsets.UTF_8);
                case 2 -> score = Float.intBitsToFloat(proto.fixed32);
                case 3 -> type = (int) proto.varint;
                default -> {
                }
            }
        }
        pieces.add(piece);
        scores.add(score);
        types.add(type);
    }

    /** Splits text into pieces (for example {@code ▁Hello}, {@code ▁there}, {@code !}). */
    public List<String> encode(String text) {
        int[] s = normalize(text).codePoints().toArray();
        int n = s.length;
        float unknownScore = minScore - UNKNOWN_PENALTY;
        float[] bestScore = new float[n + 1];
        int[] bestStart = new int[n + 1];
        int[] bestId = new int[n + 1];
        java.util.Arrays.fill(bestStart, -1);

        for (int start = 0; start < n; start++) {
            if (start > 0 && bestStart[start] == -1) {
                continue;
            }
            float base = bestScore[start];
            boolean hasSingle = false;
            StringBuilder candidate = new StringBuilder();
            for (int end = start + 1; end <= Math.min(n, start + maxPieceLength); end++) {
                candidate.appendCodePoint(s[end - 1]);
                Integer id = pieceIds.get(candidate.toString());
                if (id == null) {
                    continue;
                }
                float score = types.get(id) == USER_DEFINED ? (end - start) * maxScore - 0.1f : scores.get(id);
                float total = base + score;
                if (bestStart[end] == -1 || total > bestScore[end]) {
                    bestScore[end] = total;
                    bestStart[end] = start;
                    bestId[end] = id;
                }
                if (end - start == 1) {
                    hasSingle = true;
                }
            }
            if (!hasSingle) {
                int end = start + 1;
                float total = base + unknownScore;
                if (bestStart[end] == -1 || total > bestScore[end]) {
                    bestScore[end] = total;
                    bestStart[end] = start;
                    bestId[end] = unknownId;
                }
            }
        }

        List<String> segments = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (int end = n; end > 0; end = bestStart[end]) {
            segments.add(new String(s, bestStart[end], end - bestStart[end]));
            ids.add(bestId[end]);
        }
        List<String> result = new ArrayList<>(segments.size());
        int previousId = -1;
        for (int i = segments.size() - 1; i >= 0; i--) {
            // SentencePieceProcessor merges runs of unknown pieces into one.
            if (ids.get(i) == unknownId && previousId == unknownId) {
                result.set(result.size() - 1, result.getLast() + segments.get(i));
            } else {
                result.add(segments.get(i));
            }
            previousId = ids.get(i);
        }
        return result;
    }

    /** Joins target pieces back into text. */
    public static String decode(List<String> pieces) {
        return String.join("", pieces).replace(SPACE, " ").strip();
    }

    String normalize(String text) {
        List<String> chunks = charsmap == null ? text.codePoints().mapToObj(Character::toString).toList() : charsmap.split(text);
        int i = 0;
        if (removeExtraWhitespaces) {
            while (i < chunks.size() && chunks.get(i).equals(" ")) {
                i++;
            }
        }
        if (i == chunks.size()) {
            return "";
        }
        String whitespace = escapeWhitespaces ? SPACE : " ";
        StringBuilder out = new StringBuilder();
        if (addDummyPrefix) {
            out.append(whitespace);
        }
        boolean previousSpace = removeExtraWhitespaces;
        for (; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (previousSpace) {
                chunk = chunk.replaceFirst("^ +", "");
            }
            if (!chunk.isEmpty()) {
                out.append(escapeWhitespaces ? chunk.replace(" ", whitespace) : chunk);
                previousSpace = chunk.endsWith(" ");
            }
            if (!removeExtraWhitespaces) {
                previousSpace = false;
            }
        }
        String normalized = out.toString();
        if (removeExtraWhitespaces) {
            while (normalized.endsWith(whitespace)) {
                normalized = normalized.substring(0, normalized.length() - whitespace.length());
            }
        }
        return normalized;
    }

    /** SentencePiece's precompiled normalisation map: a darts-clone double-array trie over UTF-8 bytes. */
    private static final class Charsmap {
        private final int[] units;
        private final byte[] replacements;

        Charsmap(byte[] blob) {
            ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
            int trieSize = buffer.getInt(0);
            units = new int[trieSize / 4];
            for (int i = 0; i < units.length; i++) {
                units[i] = buffer.getInt(4 + i * 4);
            }
            replacements = java.util.Arrays.copyOfRange(blob, 4 + trieSize, blob.length);
        }

        List<String> split(String text) {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            int i = 0;
            while (i < data.length) {
                long match = longestPrefix(data, i, Math.min(data.length, i + 64));
                int length = (int) (match >>> 32);
                if (length > 0) {
                    int value = (int) match;
                    int end = value;
                    while (replacements[end] != 0) {
                        end++;
                    }
                    out.add(new String(replacements, value, end - value, StandardCharsets.UTF_8));
                    i += length;
                } else {
                    int lead = data[i] & 0xFF;
                    int length1 = lead < 0x80 ? 1 : lead < 0xE0 ? 2 : lead < 0xF0 ? 3 : 4;
                    length1 = Math.min(length1, data.length - i);
                    out.add(new String(data, i, length1, StandardCharsets.UTF_8));
                    i += length1;
                }
            }
            return out;
        }

        /** Returns (match length << 32) | value offset, or 0 when nothing matches. */
        private long longestPrefix(byte[] key, int from, int to) {
            int bestLength = 0;
            int bestValue = -1;
            int position = 0;
            int unit = units[position];
            position ^= offset(unit);
            for (int i = from; i < to; i++) {
                int c = key[i] & 0xFF;
                position ^= c;
                if (position < 0 || position >= units.length) {
                    break;
                }
                unit = units[position];
                if ((unit & 0x800000FF) != c) {
                    break;
                }
                position ^= offset(unit);
                if (((unit >>> 8) & 1) == 1) {
                    bestLength = i - from + 1;
                    bestValue = units[position] & 0x7FFFFFFF;
                }
            }
            return bestLength == 0 ? 0 : ((long) bestLength << 32) | (bestValue & 0xFFFFFFFFL);
        }

        private static int offset(int unit) {
            return (unit >>> 10) << ((unit & (1 << 9)) >>> 6);
        }
    }

    /** Minimal protobuf wire-format reader for the few ModelProto fields we need. */
    private static final class Proto {
        private final byte[] buffer;
        private final int end;
        private int position;
        int field;
        long varint;
        int fixed32;
        private int bytesStart;
        private int bytesLength;

        Proto(byte[] buffer, int start, int end) {
            this.buffer = buffer;
            this.position = start;
            this.end = end;
        }

        boolean hasNext() {
            return position < end;
        }

        void next() {
            long key = readVarint();
            field = (int) (key >>> 3);
            switch ((int) (key & 7)) {
                case 0 -> varint = readVarint();
                case 1 -> position += 8;
                case 2 -> {
                    bytesLength = (int) readVarint();
                    bytesStart = position;
                    position += bytesLength;
                }
                case 5 -> {
                    fixed32 = ByteBuffer.wrap(buffer, position, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    position += 4;
                }
                default -> throw new IllegalArgumentException("Unsupported protobuf wire type " + (key & 7));
            }
        }

        byte[] bytes() {
            return java.util.Arrays.copyOfRange(buffer, bytesStart, bytesStart + bytesLength);
        }

        Proto message() {
            return new Proto(buffer, bytesStart, bytesStart + bytesLength);
        }

        private long readVarint() {
            long result = 0;
            int shift = 0;
            while (true) {
                byte b = buffer[position++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
        }
    }
}
