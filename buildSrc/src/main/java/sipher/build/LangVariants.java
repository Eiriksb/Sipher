package sipher.build;

import org.gradle.api.file.CopySpec;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Minecraft falls back to en_us, not to the main language, so regional variants get a copy of the main translation.
 */
public final class LangVariants {
    private static final Map<String, List<String>> VARIANTS = Map.of(
            "es_es", List.of("es_ar", "es_cl", "es_ec", "es_mx", "es_uy", "es_ve"),
            "de_de", List.of("de_at", "de_ch"),
            "fr_fr", List.of("fr_ca", "fr_ch"),
            "nl_nl", List.of("nl_be"),
            "pt_br", List.of("pt_pt"),
            "no_no", List.of("nn_no"));

    private LangVariants() {
    }

    /** Adds the copies to a resources task, reading the translations from {@code langDirectory}. */
    public static void copyInto(CopySpec resources, File langDirectory) {
        VARIANTS.forEach((main, variants) -> variants.forEach(variant ->
                resources.from(new File(langDirectory, main + ".json"), spec -> {
                    spec.into("assets/sipher/lang");
                    spec.rename(name -> variant + ".json");
                })));
    }
}
