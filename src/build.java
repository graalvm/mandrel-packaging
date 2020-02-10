//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.1.4
//DEPS org.apache.logging.log4j:log4j-core:2.13.0

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(
    description = "Build mandrel bits"
    , mixinStandardHelpOptions = true
    , name = "build"
    , version = "build 0.1"
)
public class build implements Callable<Integer>
{
    static final Logger logger = LogManager.getLogger(build.class);

    public static void main(String... args)
    {
        Configurator.initialize(new DefaultConfiguration());
        Configurator.setRootLevel(Level.DEBUG);

        int exitCode = new CommandLine(new build()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call()
    {
        logger.info("Bob the builder!");
        SequentialBuild.build();
        return 0;
    }
}

class SequentialBuild
{
    static void build()
    {
        Replacements.groupId("sdk");
        Mx.build("sdk");
        Mx.mavenInstall("sdk");
        Replacements.groupId("substratevm");
        Mx.build("substratevm");
        Mx.mavenInstall("substratevm");
        // TODO commit replacements
    }
}

class Replacements
{
    static void groupId(String artifactName)
    {
        replaceGroupId(path(artifactName));
    }

    private static void replaceGroupId(Path path)
    {
        try
        {
            try (var lines = Files.lines(path))
            {
                List<String> replaced = lines
                    .map(replaceField("groupId"))
                    .collect(Collectors.toList());
                Files.write(path, replaced);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static Function<String, String> replaceField(String field)
    {
        return line ->
            line.contains(field)
            ? line.replaceFirst("org.graalvm", "io.mandrel")
            : line;
    }

    private static Path path(String artifactName)
    {
        final var file = new File(String.format(
            "/home/mandrel/mandrel/%1$s/mx.%1$s/suite.py"
            , artifactName
        ));
        return Paths.get(file.toURI());
    }
}

class Mx
{
    public static final OperatingSystem.EnvVar JAVA_HOME_ENV_VAR =
        new OperatingSystem.EnvVar(
            "JAVA_HOME"
            , "/opt/labsjdk"
        );

    static void build(String artifactName)
    {
        OperatingSystem.exec()
            .compose(Mx::mxbuild)
            .compose(Mx::path)
            .apply(artifactName);
    }

    static void mavenInstall(String artifactName)
    {
        OperatingSystem.exec()
            .compose(Mx::mxMavenInstall)
            .compose(Mx::path)
            .apply(artifactName);
    }

    private static OperatingSystem.Command mxbuild(File path)
    {
        return new OperatingSystem.Command(
            Stream.of(
                "mx"
                , "build"
            )
            , path
            , Stream.of(
                JAVA_HOME_ENV_VAR
            )
        );
    }

    private static OperatingSystem.Command mxMavenInstall(File path)
    {
        return new OperatingSystem.Command(
            Stream.of(
                "mx"
                , "maven-install"
            )
            , path
            , Stream.of(
                JAVA_HOME_ENV_VAR
            )
        );
    }

    private static File path(String artifact)
    {
        return new File(String.format(
            "/home/mandrel/mandrel/%s"
            , artifact
        ));
    }
}

class OperatingSystem
{
    static final Logger logger = LogManager.getLogger(OperatingSystem.class);

    static Function<OperatingSystem.Command, Void> exec()
    {
        return OperatingSystem::exec;
    }

    private static Void exec(Command command)
    {
        final var commandList = command.command.collect(Collectors.toList());
        logger.debug("Execute {} in {}", commandList, command.directory);
        try
        {
            var processBuilder = new ProcessBuilder(commandList)
                .directory(command.directory)
                .inheritIO();

            command.envVars.forEach(
                envVar -> processBuilder.environment()
                    .put(envVar.name, envVar.value)
            );

            Process process = processBuilder.start();

            if (process.waitFor() != 0)
            {
                throw new RuntimeException(
                    "Failed, exit code: " + process.exitValue()
                );
            }

            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    static class Command
    {
        final Stream<String> command;
        final File directory;
        final Stream<EnvVar> envVars;

        Command(Stream<String> command, File directory, Stream<EnvVar> envVars)
        {
            this.command = command;
            this.directory = directory;
            this.envVars = envVars;
        }
    }

    static class EnvVar
    {
        final String name;
        final String value;

        EnvVar(String name, String value)
        {
            this.name = name;
            this.value = value;
        }
    }
}