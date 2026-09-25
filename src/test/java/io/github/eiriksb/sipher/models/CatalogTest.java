package io.github.eiriksb.sipher.models;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the catalogue compiled into the mod: it alone decides what Sipher may download. */
class CatalogTest {
    private static final Set<String> ALLOWED_HOSTS = Set.of("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");

    @Test
    void shippedCatalogueIsConsistentAndSafe() throws Exception {
        Catalog catalog = Catalog.load();
        assertEquals(ALLOWED_HOSTS, catalog.hosts(), "download hosts changed — review before shipping");

        for (Catalog.Language language : catalog.languages()) {
            assertFalse(language.builtIn(), language.code() + " must reference its components");
            assertEquals(3, language.components().size(), language.code());
            for (String id : language.components()) {
                Catalog.Component component = assertDoesNotThrow(() -> catalog.component(id), id);
                assertFalse(component.files().isEmpty(), id);
                assertFalse(component.license().isBlank(), id + " needs a licence");
                assertTrue(component.files().stream().anyMatch(f -> f.path().equals("LICENSE")), id + " must ship its LICENSE");
                for (ModelDownloader.FileSpec file : component.files()) {
                    URI url = URI.create(file.url());
                    assertEquals("https", url.getScheme(), file.url());
                    assertEquals("github.com", url.getHost(), file.url());
                    assertTrue(url.getPath().startsWith("/Eiriksb/Sipher/releases/download/"), file.url());
                    assertEquals(64, file.sha256().length(), file.url());
                    assertTrue(file.size() > 0, file.url());
                    // Same data-only rule the downloader enforces at runtime.
                    assertDoesNotThrow(() -> ModelDownloader.resolveSafely(Path.of("models"), file.path()), file.path());
                }
            }
            Catalog.Component speech = catalog.component(language.speech());
            Catalog.Component toEnglish = catalog.component(language.toEnglish());
            Catalog.Component fromEnglish = catalog.component(language.fromEnglish());
            assertTrue(speech.isSpeech(), language.speech());
            assertEquals("en", toEnglish.details().get("target").getAsString(), language.toEnglish());
            assertEquals("en", fromEnglish.details().get("source").getAsString(), language.fromEnglish());
        }
    }
}
