import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class build
{
    static final Logger logger = LogManager.getLogger(build.class);

    public static void main(String... args)
    {
        final var options = Options.from(Args.read(args));

        logger.info("Build Mandrel");
        SequentialBuild.build(options);
    }
}

class Options
{
    final Action action;
    final String version;
    final boolean verbose;
    final String mavenProxy;
    final String mavenRepoId;
    final String mavenURL;

    Options(
        Action action
        , String version
        , boolean verbose
        , String mavenProxy
        , String mavenRepoId
        , String mavenURL
    )
    {
        this.action = action;
        this.version = version;
        this.verbose = verbose;
        this.mavenProxy = mavenProxy;
        this.mavenRepoId = mavenRepoId;
        this.mavenURL = mavenURL;
    }

    public static Options from(Map<String, List<String>> args)
    {
        final var action = args.containsKey("deploy")
            ? Action.DEPLOY
            : Action.INSTALL;
        final var version = required("version", args);
        final var verbose = args.containsKey("verbose");
        final var mavenProxy = optional("maven-proxy", args);
        final var mavenRepoId = optional("maven-repo-id", args);
        final var mavenURL = optional("maven-url", args);
        return new Options(action, version, verbose, mavenProxy, mavenRepoId, mavenURL);
    }

    private static String optional(String name, Map<String, List<String>> args)
    {
        final var option = args.get(name);
        return option != null ? option.get(0) : null;
    }

    private static String required(String name, Map<String, List<String>> args)
    {
        final var option = args.get(name);
        if (Objects.isNull(option))
            throw new IllegalArgumentException(String.format(
                "Missing mandatory --%s"
                , name
            ));

        return option.get(0);
    }

    enum Action
    {
        INSTALL, DEPLOY
    }
}

class SequentialBuild
{
    static void build(Options options)
    {
        // TODO git checkout mx version associated with mandrel version
        Mx.hookMavenProxy(options);
        Mx.build("sdk", options);
        // TODO consider splitting mvn into install and deploy
        // TODO produce src jars
        Maven.mvn("sdk", options);
        // Mx.build("substratevm", options);
        // Maven.install("substratevm", options);
    }
}

class Mx
{
    static void build(String artifactName, Options options)
    {
        OperatingSystem.exec()
            .compose(Mx.mxbuild(options))
            .compose(LocalPaths::to)
            .apply(artifactName);
    }

    private static Function<Path, OperatingSystem.Command> mxbuild(Options options)
    {
        return path ->
            new OperatingSystem.Command(
                Stream.of(
                    "mx"
                    , options.verbose ? "-V" : ""
                    , "--trust-http"
                    , "build"
                    , "--no-native"
                )
                , path
                , Stream.empty()
            );
    }

    static void hookMavenProxy(Options options)
    {
        if (options.mavenProxy == null)
            return;

        prependMavenProxyToMxPy(options)
            .apply(backupOrRestoreMxPy());
    }

    private static Function<Path, Void> prependMavenProxyToMxPy(Options options)
    {
        return mxPy ->
        {
            try (var lines = Files.lines(mxPy))
            {
                final var replaced = lines
                    .filter(Mx::notMavenOrg)
                    .map(prependMavenProxy(options))
                    .collect(Collectors.toList());
                Files.write(mxPy, replaced);
                return null;
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        };
    }

    private static boolean notMavenOrg(String line)
    {
        return !line.contains("maven.org");
    }

    private static Path backupOrRestoreMxPy()
    {
        Path backupMxPy = Path.of("/opt", "mx", "mx.py.backup");
        Path mxPy = Path.of("/opt", "mx", "mx.py");
        if (!backupMxPy.toFile().exists())
        {
            copyIgnoreAccessDenied(backupMxPy, mxPy);
        }
        else
        {
            copy(backupMxPy, mxPy, StandardCopyOption.REPLACE_EXISTING);
        }

        return mxPy;
    }

    private static void copyIgnoreAccessDenied(Path backupMxPy, Path mxPy)
    {
        try
        {
            Files.copy(mxPy, backupMxPy);
        }
        catch (AccessDeniedException e)
        {
            // Ignore if unable to get access to copy
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static void copy(Path from, Path to, CopyOption... copyOptions)
    {
        try
        {
            Files.copy(from, to, copyOptions);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static Function<String, String> prependMavenProxy(Options options)
    {
        return line ->
        {
            var mavenBaseURL = String.format("\"%s/\"", options.mavenProxy);
            return line.contains("_mavenRepoBaseURLs")
                ? line.replaceFirst("\\[", String.format("[ %s", mavenBaseURL))
                : line;
        };
    }

}

class LocalPaths
{
    static Path to(String artifact)
    {
        return Path.of("/tmp", "mandrel", artifact);
    }

    static String from(Path path)
    {
        return path.getFileName().toString();
    }
}

class Maven
{
    static final Map<String, String> GROUP_IDS = Map.of(
        "sdk", "org.graalvm.sdk"
        , "substratevm", "org.graalvm.nativeimage"
    );

    static final Map<String, String> ARTIFACT_IDS = Map.of(
        "sdk", "graal-sdk"
        , "substratevm", "svm"
    );

    static void mvn(String artifactName, Options options)
    {
        OperatingSystem.exec()
            .compose(Maven.mvn(options))
            .compose(LocalPaths::to)
            .apply(artifactName);
    }

    private static Function<Path, OperatingSystem.Command> mvn(Options options)
    {
        return path ->
        {
            final var artifactName = LocalPaths.from(path);
            final var groupId = GROUP_IDS.get(artifactName);
            final var artifactId = ARTIFACT_IDS.get(artifactName);

            final var repoId = Objects.nonNull(options.mavenRepoId)
                ? String.format("-DrepositoryId=%s", options.mavenRepoId)
                : "";

            final var url = Objects.nonNull(options.mavenURL)
                ? String.format("-Durl=%s", options.mavenURL)
                : "";

            return new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    , options.verbose ? "--debug" : ""
                    , String.format("%1$s:%1$s-file", options.action.toString().toLowerCase())
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
                    , repoId
                    , url
                )
                , path
                , Stream.empty()
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
        final var commandList = command.command
            .filter(Predicate.not(String::isEmpty))
            .collect(Collectors.toList());

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

final class Args
{
    static Map<String, List<String>> read(String... args)
    {
        final Map<String, List<String>> params = new HashMap<>();

        List<String> options = null;
        for (final String arg : args)
        {
            if (arg.startsWith("--"))
            {
                if (arg.length() < 3)
                {
                    System.err.println("Error at argument " + arg);
                    return params;
                }

                options = new ArrayList<>();
                params.put(arg.substring(2), options);
            }
            else if (options != null)
            {
                options.add(arg);
            }
            else
            {
                System.err.println("Illegal parameter usage");
                return params;
            }
        }

        return params;
    }
}

final class LogManager
{
    static Logger getLogger(Class<?> clazz)
    {
        return new Logger(clazz.getSimpleName());
    }
}
