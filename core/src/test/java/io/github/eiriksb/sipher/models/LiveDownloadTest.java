package io.github.eiriksb.sipher.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Installs a real language pack from the published GitHub release with the production download policy (HTTPS,
 * allowlisted hosts through every redirect, SHA-256 checks). Opt-in because it downloads hundreds of MB:
 * {@code ./gradlew test --tests '*LiveDownloadTest*' -PliveDownload=es}.
 */
class LiveDownloadTest {
    @TempDir
    Path sipherDirectory;

    @Test
    void installsAPublishedPack() throws Exception {
        String code = System.getProperty("sipher.test.liveDownload");
        assumeTrue(code != null, "no -PliveDownload given");

        Catalog catalog = Catalog.load();
        Catalog.Language language = catalog.language(code).orElseThrow();
        LanguagePacks packs = new LanguagePacks(catalog, sipherDirectory, "Sipher-test");
        assertEquals(LanguagePacks.State.AVAILABLE, packs.status(language).state());

        CountDownLatch installed = new CountDownLatch(1);
        long started = System.nanoTime();
        packs.install(language, done -> installed.countDown());
        while (!installed.await(1, TimeUnit.SECONDS)) {
            LanguagePacks.Status status = packs.status(language);
            assertTrue(status.state() == LanguagePacks.State.DOWNLOADING, "download stopped: " + status.error());
        }
        System.out.printf("Installed %s (%d MB) in %d s%n", code, catalog.size(language) / 1_000_000,
                (System.nanoTime() - started) / 1_000_000_000);
        assertEquals(LanguagePacks.State.INSTALLED, packs.status(language).state());
    }
}
