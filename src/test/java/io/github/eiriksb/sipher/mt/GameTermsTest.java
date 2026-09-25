package io.github.eiriksb.sipher.mt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameTermsTest {
    @Test
    void masksNamesAndRestoresTheirCanonicalSpelling() {
        GameTerms.Masked masked = GameTerms.protect("Two creepers and an ENDER DRAGON in the nether, another creeper!");
        assertEquals("Two X1 and an X2 in the X3, another X4!", masked.text());
        assertEquals("Dos Creepers y un Ender Dragon en el Nether, ¡otro Creeper!",
                masked.restore("Dos X1 y un X2 en el X3, ¡otro X4!"));
    }

    @Test
    void leavesOrdinaryWordsAlone() {
        assertEquals("The withering netherlands", GameTerms.protect("The withering netherlands").text());
    }

    @Test
    void picksAPlaceholderThatIsNotAlreadyInTheText() {
        GameTerms.Masked masked = GameTerms.protect("X1 marks the creeper");
        assertEquals("X1 marks the Q1", masked.text());
        assertEquals("X1 marca el Creeper", masked.restore("X1 marca el Q1"));
    }
}
