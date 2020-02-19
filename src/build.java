import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class build
{
    static final Logger logger = LogManager.getLogger(build.class);

    public static void main(String... args)
    {
        final var version = System.getProperty("version");
        if (Objects.isNull(version))
            throw new IllegalArgumentException("Missing mandatory -Dversion system property");

        final var options = new Options(version);

        logger.info("Build Mandrel");
        SequentialBuild.build(options);
    }
}

class Options
{
    final String version;

    Options(String version)
    {
        this.version = version;
    }
}

class SequentialBuild
{
    static void build(Options options)
    {
        Mx.build("sdk");
        Maven.install("sdk", options);
        Mx.build("substratevm");
        Maven.install("substratevm", options);
    }
}

class EnvVars
{
    public static final OperatingSystem.EnvVar JAVA_HOME_ENV_VAR =
        new OperatingSystem.EnvVar(
            "JAVA_HOME"
            , "/opt/labsjdk"
        );
}

class Mx
{
    static void build(String artifactName)
    {
        OperatingSystem.exec()
            .compose(Mx::mxbuild)
            .compose(LocalPaths::to)
            .apply(artifactName);
    }

    private static OperatingSystem.Command mxbuild(Path path)
    {
        return new OperatingSystem.Command(
            Stream.of(
                "mx"
                , "build"
                , "--no-native"
            )
            , path
            , Stream.of(
                EnvVars.JAVA_HOME_ENV_VAR
            )
        );
    }

}

class LocalPaths
{
    static Path to(String artifact)
    {
        return Path.of("/home", "mandrel", "mandrel", artifact);
    }

    static String from(Path path)
    {
        return path.getFileName().toString();
    }
}

class Maven
{
    static final Map<String, String> GROUP_IDS = Map.of(
        "sdk", "io.mandrel.sdk"
        , "substratevm", "io.mandrel.nativeimage"
    );

    static final Map<String, String> ARTIFACT_IDS = Map.of(
        "sdk", "graal-sdk"
        , "substratevm", "svm"
    );

    static void install(String artifactName, Options options)
    {
        OperatingSystem.exec()
            .compose(Maven.mvnInstall(options))
            .compose(LocalPaths::to)
            .apply(artifactName);
    }
    
    private static Function<Path, OperatingSystem.Command> mvnInstall(Options options)
    {
        return path ->
        {
            final var artifactName = LocalPaths.from(path);
            final var groupId = GROUP_IDS.get(artifactName);
            final var artifactId = ARTIFACT_IDS.get(artifactName);

            return new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    // , "--debug"
                    , "install:install-file"
                    , String.format("-DgroupId=%s", groupId)
                    , String.format("-DartifactId=%s", artifactId)
                    , String.format("-Dversion=%s", options.version)
                    , "-Dpackaging=jar"
                    , String.format(
                        "-Dfile=%s/mxbuild/dists/jdk11/%s.jar"
                        , path.toString()
                        , artifactId
                    )
                    , "-DcreateChecksum=true"
                )
                , path
                , Stream.of(
                    EnvVars.JAVA_HOME_ENV_VAR
                )
            );
        };
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
        logger.debugf("Execute %s in %s", commandList, command.directory);
        try
        {
            var processBuilder = new ProcessBuilder(commandList)
                .directory(command.directory.toFile())
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
        final Path directory;
        final Stream<EnvVar> envVars;

        Command(Stream<String> command, Path directory, Stream<EnvVar> envVars)
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

final class Logger
{
    final String name;

    Logger(String name)
    {
        this.name = name;
    }

    void debugf(String format, Object... params)
    {
        System.out.printf("DEBUG [%s] %s%n"
            , name
            , String.format(format, params)
        );
    }

    public void info(String msg)
    {
        System.out.printf("INFO [%s] %s%n"
            , name
            , msg
        );
    }
}

final class LogManager
{
    static Logger getLogger(Class<?> clazz)
    {
        return new Logger(clazz.getSimpleName());
    }
}
