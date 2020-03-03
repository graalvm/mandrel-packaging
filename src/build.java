import java.io.IOException;
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
import java.util.regex.Pattern;
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
        // TODO consider splitting mvn into install and deploy

        Mx.build("sdk", options);
        Maven.mvn("sdk", options);
        // Mx.build("substratevm", options);
        // Maven.mvn("substratevm", options);
    }
}

class Artifact
{
    final Path rootPath;
    final Path suitePyPath;
    final String mxVersion;

    Artifact(Path rootPath, Path suitePyPath, String mxVersion)
    {
        this.rootPath = rootPath;
        this.suitePyPath = suitePyPath;
        this.mxVersion = mxVersion;
    }
}

class Mx
{
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"([0-9]\\.[0-9]{1,3}\\.[0-9]{1,2})\"");

    static void build(String artifactName, Options options)
    {
        OperatingSystem.exec()
            .compose(Mx.mxbuild(options))
            .compose(Mx.hookMavenProxy(options))
            .compose(Mx::artifact)
            .apply(artifactName);
    }

    private static Artifact artifact(String artifactName)
    {
        var rootPath = LocalPaths.GRAAL_HOME.resolve(artifactName);
        var suitePyPath = LocalPaths.GRAAL_HOME.resolve(suitePy(artifactName));
        final var mxVersion = mxVersion(suitePyPath);
        return new Artifact(rootPath, suitePyPath, mxVersion);
    }

    private static Path suitePy(String artifactName)
    {
        return Path.of(
            artifactName
            , String.format("mx.%s", artifactName)
            , "suite.py"
        );
    }

    private static String mxVersion(Path suitePy)
    {
        try (var lines = Files.lines(suitePy))
        {
            return lines
                .filter(line -> line.contains("mxversion"))
                .map(Mx::extractMxVersion)
                .findFirst()
                .orElse(null);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static String extractMxVersion(String line)
    {
        final var matcher = VERSION_PATTERN.matcher(line);
        return matcher.results()
            .findFirst()
            .map(result -> result.group(1))
            .orElse(null);
    }

    private static Function<Artifact, OperatingSystem.Command> mxbuild(Options options)
    {
        return artifact ->
            new OperatingSystem.Command(
                Stream.of(
                    LocalPaths.mxHome(artifact.mxVersion).resolve("mx").toString()
                    , options.verbose ? "-V" : ""
                    , "--trust-http"
                    , "build"
                    , "--no-native"
                )
                , artifact.rootPath
                , Stream.empty()
            );
    }

    static Function<Artifact, Artifact> hookMavenProxy(Options options)
    {
        return artifact -> {
            if (options.mavenProxy != null) {
                Mx.prependMavenProxyToMxPy(options)
                    .compose(Mx::backupOrRestoreMxPy)
                    .apply(artifact);
            }

            return artifact;
        };
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

    private static Path backupOrRestoreMxPy(Artifact artifact)
    {
        Path backupMxPy = LocalPaths.mxHome(artifact.mxVersion).resolve("mx.py.backup");
        Path mxPy = LocalPaths.mxHome(artifact.mxVersion).resolve("mx.py");
        if (!backupMxPy.toFile().exists())
        {
            copy(mxPy, backupMxPy);
        }
        else
        {
            copy(backupMxPy, mxPy, StandardCopyOption.REPLACE_EXISTING);
        }

        return mxPy;
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
}

class Git
{
    static Function<String, OperatingSystem.Command> checkout(Path directory)
    {
        return branch ->
            new OperatingSystem.Command(
                Stream.of(
                    "git"
                    , "checkout"
                    , branch
                )
                , directory
                , Stream.empty()
            );
    }
}

class LocalPaths
{
    static final Path GRAAL_HOME = Path.of("/tmp", "mandrel");

    static Path graalHome(Path other)
    {
        return GRAAL_HOME.resolve(other);
    }

    static Path mxHome(String mxVersion)
    {
        return Path.of("/opt", "mx", mxVersion);
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
            .compose(LocalPaths::graalHome)
            .apply(Path.of(artifactName));
    }

    private static Function<Path, OperatingSystem.Command> mvn(Options options)
    {
        return path ->
        {
            final var artifactName = path.getFileName().toString();
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
                    , String.format(
                        "-Dsources=%s/mxbuild/dists/jdk11/%s.src.zip"
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
