package sipher.build;

import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.ArtifactRepository;

import java.util.function.Supplier;

/** Repositories for Sipher's own dependencies, each limited to the groups it serves. */
public final class SipherRepositories {
    private SipherRepositories() {
    }

    public static void add(RepositoryHandler repositories) {
        exclusive(repositories, "de.maxhenkel.voicechat", () -> repositories.maven(maven -> {
            maven.setName("Max Henkel");
            maven.setUrl("https://maven.maxhenkel.de/repository/public");
        }));
        exclusive(repositories, "maven.modrinth", () -> repositories.maven(maven -> {
            maven.setName("Modrinth");
            maven.setUrl("https://api.modrinth.com/maven");
        }));
        // sherpa-onnx is not published to Maven Central; resolve its release jars straight from GitHub releases.
        // Checksums are pinned in gradle/third-party-checksums.txt and checked by :core:verifyThirdPartyJars.
        exclusive(repositories, "com.k2fsa.sherpa.onnx", () -> repositories.ivy(ivy -> {
            ivy.setName("sherpa-onnx GitHub releases");
            ivy.setUrl("https://github.com/k2-fsa/sherpa-onnx/releases/download/");
            ivy.patternLayout(layout -> layout.artifact("v[revision]/[module]-[revision].[ext]"));
            ivy.metadataSources(sources -> sources.artifact());
        }));
        repositories.mavenCentral();
    }

    private static void exclusive(RepositoryHandler repositories, String group, Supplier<ArtifactRepository> repository) {
        repositories.exclusiveContent(content -> {
            content.forRepository(repository::get);
            content.filter(filter -> filter.includeGroup(group));
        });
    }
}
