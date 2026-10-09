package sipher.build;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * Lets only one NeoForge build set up (decompile and recompile) Minecraft at a time. With a dozen Minecraft versions
 * in one build, doing them all in parallel runs the machine out of memory.
 */
public abstract class MinecraftSetupLock implements BuildService<BuildServiceParameters.None> {
}
