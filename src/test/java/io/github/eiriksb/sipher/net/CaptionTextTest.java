package io.github.eiriksb.sipher.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaptionTextTest {
    @Test
    void stripsFormattingCodesAndControlCharacters() {
        assertEquals("free diamonds here", CaptionText.sanitize("§kfree§r diamonds\u0000‮ here\n", 256));
    }

    @Test
    void truncatesToTheLimit() {
        assertEquals("abc", CaptionText.sanitize("abcdef", 3));
    }

    @Test
    void acceptsOnlyLanguageCodes() {
        assertEquals("nb", CaptionText.language("NB"));
        assertEquals("zh-hans", CaptionText.language("zh-Hans"));
        assertEquals("und", CaptionText.language("english; drop table"));
        assertEquals("und", CaptionText.language(null));
    }
}
