import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
    private static final List<String> DEFAULT_ARTIFACTS =
        Arrays.asList("sdk", "substratevm");

    final Action action;
    final String version;
    final boolean verbose;
    final String mavenProxy;
    final String mavenRepoId;
    final String mavenURL;
    final List<String> artifacts;

    Options(
        Action action
        , String version
        , boolean verbose
        , String mavenProxy
        , String mavenRepoId
        , String mavenURL
        , List<String> artifacts
    )
    {
        this.action = action;
        this.version = version;
        this.verbose = verbose;
        this.mavenProxy = mavenProxy;
        this.mavenRepoId = mavenRepoId;
        this.mavenURL = mavenURL;
        this.artifacts = artifacts;
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

        final var artifactsArg = args.get("artifacts");
        final var artifacts = artifactsArg == null
            ? DEFAULT_ARTIFACTS
            : artifactsArg;

        return new Options(
            action
            , version
            , verbose
            , mavenProxy
            , mavenRepoId
            , mavenURL
            , artifacts
        );
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
        // Only invoke mvn if all mx builds succeeded
        options.artifacts.forEach(Mx.build(options));
        options.artifacts.forEach(Maven.mvn(options));
    }
}

class Mx
{
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"([0-9]\\.[0-9]{1,3}\\.[0-9]{1,2})\"");

    private static final String SVM_DEPENDENCIES = String.join(","
        , "SVM"
        , "com.oracle.svm.graal"
        , "com.oracle.svm.truffle"
        , "com.oracle.svm.hosted"
        , "com.oracle.svm.truffle.nfi"
        , "com.oracle.svm.truffle.nfi.posix"
        , "com.oracle.svm.truffle.nfi.windows"
        , "com.oracle.svm.core.jdk11"
        , "com.oracle.svm.core"
        , "com.oracle.svm.core.graal.amd64"
        , "com.oracle.svm.core.graal.aarch64"
        , "com.oracle.svm.core.posix.jdk11"
        , "com.oracle.svm.core.posix"
        , "com.oracle.svm.core.windows"
        , "com.oracle.svm.core.genscavenge"
        , "com.oracle.svm.jni"
        , "com.oracle.svm.reflect"
        , "com.oracle.svm.util" // svm.core dependency
        , "com.oracle.svm.core.graal" // svm.hosted dependency
        // , "SVM_HOSTED_NATIVE" // -- do not even try to download native dependencies
        // , "GRAAL_SDK" // -- previously built and available as mvn dependency
        , "OBJECTFILE"
        , "com.oracle.objectfile"
        , "POINTSTO"
        , "com.oracle.graal.pointsto"
        // , "JUNIT_TOOL" // -- don't need test tools in production artifacts
        , "TRUFFLE_NFI"
        , "com.oracle.truffle.nfi"
        , "com.oracle.truffle.nfi.spi"

        , "TRUFFLE_API"
        , "com.oracle.truffle.api"
        , "com.oracle.truffle.api.dsl"
        , "com.oracle.truffle.api.profiles"
        , "com.oracle.truffle.api.debug"
        , "com.oracle.truffle.api.utilities"
        , "com.oracle.truffle.object"
        , "com.oracle.truffle.api.object.dsl"
        , "com.oracle.truffle.polyglot"
        , "com.oracle.truffle.api.interop" // api.polyglot dependency
        , "com.oracle.truffle.api.instrumentation" // api.polyglot dependency
        , "com.oracle.truffle.api.utilities" // api.polyglot dependency
        , "com.oracle.truffle.api.profiles" // api.interop dependency
        , "com.oracle.truffle.api.library" // api.interop dependency
        , "com.oracle.truffle.api.object" // truffle.object dependency

        , "TRUFFLE_DSL_PROCESSOR"
        , "truffle:ANTLR4"
        , "com.oracle.truffle.dsl.processor"
        , "com.oracle.truffle.object.dsl.processor"
        , "com.oracle.truffle.dsl.processor.interop"

        , "GRAAL"
        , "org.graalvm.libgraal"
        , "org.graalvm.compiler.options"
        , "org.graalvm.compiler.nodeinfo"
        , "org.graalvm.compiler.serviceprovider"
        , "org.graalvm.compiler.api.replacements"
        , "org.graalvm.compiler.api.runtime"
        , "org.graalvm.compiler.graph"
        , "org.graalvm.compiler.core"
        , "org.graalvm.compiler.replacements"
        , "org.graalvm.compiler.runtime"
        , "org.graalvm.compiler.code"
        , "org.graalvm.compiler.printer"
        , "org.graalvm.compiler.core.aarch64"
        , "org.graalvm.compiler.replacements.aarch64"
        , "org.graalvm.compiler.core.amd64"
        , "org.graalvm.compiler.replacements.amd64"
        , "org.graalvm.compiler.core.sparc"
        , "org.graalvm.compiler.replacements.sparc"
        , "org.graalvm.compiler.hotspot.aarch64"
        , "org.graalvm.compiler.hotspot.amd64"
        , "org.graalvm.compiler.hotspot.sparc"
        , "org.graalvm.compiler.hotspot"
        , "org.graalvm.compiler.lir.aarch64"
        , "org.graalvm.compiler.truffle.compiler.amd64"
        , "org.graalvm.compiler.truffle.runtime.serviceprovider"
        , "org.graalvm.compiler.truffle.runtime.hotspot"
        , "org.graalvm.compiler.truffle.runtime.hotspot.java"
        , "org.graalvm.compiler.truffle.runtime.hotspot.libgraal"
        , "org.graalvm.compiler.truffle.compiler.hotspot.amd64"
        , "org.graalvm.compiler.truffle.compiler.hotspot.sparc"
        , "org.graalvm.compiler.truffle.compiler.hotspot.aarch64"
        , "org.graalvm.compiler.api.directives" // compiler.replacements dependency
        , "org.graalvm.compiler.java" // compiler.replacements dependency
        , "org.graalvm.compiler.loop.phases" // compiler.replacements dependency
        , "org.graalvm.compiler.word" // compiler.replacements dependency
        , "org.graalvm.compiler.nodes" // compiler.word dependency
        , "org.graalvm.compiler.lir" // compiler.nodes dependency
        , "org.graalvm.compiler.asm" // compiler.lir dependency
        , "org.graalvm.compiler.code" // compiler.lir dependency
        , "org.graalvm.compiler.nodeinfo" // compiler.graph dependency
        , "org.graalvm.compiler.core.common" // compiler.graph dependency
        , "org.graalvm.compiler.bytecode" // compiler.graph dependency
        , "org.graalvm.compiler.loop" // compiler.loop.phases dependency
        , "org.graalvm.compiler.phases.common" // compiler.loop.phases dependency
        , "org.graalvm.compiler.phases" // compiler.phases.common dependency
        , "org.graalvm.compiler.lir" // compiler.lir.aarch64 dependency
        , "org.graalvm.compiler.asm.aarch64" // compiler.lir.aarch64 dependency
        , "org.graalvm.compiler.lir.amd64" // compiler.core.amd64 dependency
        , "org.graalvm.compiler.asm.amd64" // compiler.lir.amd64 dependency
        , "org.graalvm.compiler.lir.sparc" // compiler.hotspot.sparc dependency (TODO missing in mx.compiler/suite.py)
        , "org.graalvm.compiler.truffle.compiler" // compiler.truffle.compiler.amd64 dependency
        , "org.graalvm.compiler.truffle.runtime" // compiler.truffle.runtime.hotspot dependency
        , "org.graalvm.compiler.truffle.common.hotspot" // compiler.truffle.runtime.hotspot dependency
        , "org.graalvm.compiler.truffle.compiler.hotspot" // compiler.truffle.runtime.hotspot.java dependency
        , "org.graalvm.compiler.truffle.common.hotspot.libgraal" // compiler.truffle.runtime.hotspot.libgraal dependency
        , "org.graalvm.util" // compiler.truffle.runtime.hotspot.libgraal dependency
        , "org.graalvm.compiler.debug" // compiler.core.common dependency
        , "org.graalvm.compiler.virtual" // compiler.core dependency
        , "org.graalvm.compiler.asm.sparc" // compiler.lir.sparc dependency

        , "GRAAL_PROCESSOR_COMMON"
        , "org.graalvm.compiler.processor"

        , "GRAAL_OPTIONS_PROCESSOR"
        , "org.graalvm.compiler.options.processor"

        , "GRAAL_NODEINFO_PROCESSOR"
        , "org.graalvm.compiler.nodeinfo.processor"

        , "GRAAL_SERVICEPROVIDER_PROCESSOR"
        , "org.graalvm.compiler.serviceprovider.processor"

        , "GRAAL_REPLACEMENTS_PROCESSOR"
        , "org.graalvm.compiler.replacements.processor"

        , "GRAAL_COMPILER_MATCH_PROCESSOR"
        , "org.graalvm.compiler.core.match.processor"

        , "GRAAL_GRAPHIO"
        , "org.graalvm.graphio"

        , "GRAAL_TRUFFLE_COMMON"
        , "org.graalvm.compiler.truffle.common"

        , "TRUFFLE_COMMON_PROCESSOR"
        , "org.graalvm.compiler.truffle.common.processor"

        , "JVMCI_HOTSPOT"
        , "JVMCI_API"
    );

    static final Map<String, Stream<String>> BUILD_ARGS = Map.of(
        "sdk", Stream.empty()
        , "substratevm", Stream.of("--only", SVM_DEPENDENCIES)
    );

    static Consumer<String> build(Options options)
    {
        return artifactName ->
            OperatingSystem.exec()
                .compose(Mx.mxbuild(options))
                .compose(Mx.hookMavenProxy(options))
                .compose(Mx::artifact)
                .apply(artifactName);
    }

    private static Artifact artifact(String artifactName)
    {
        var rootPath = LocalPaths.graalHome().resolve(artifactName);
        var suitePyPath = LocalPaths.graalHome().resolve(suitePy(artifactName));
        var mxVersion = mxVersion(suitePyPath);
        var buildArgs = BUILD_ARGS.get(artifactName);
        return new Artifact(rootPath, suitePyPath, mxVersion, buildArgs);
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
                Stream.concat(
                    Stream.of(
                        LocalPaths.mxHome(artifact.mxVersion).resolve("mx").toString()
                        , options.verbose ? "-V" : ""
                        , "--trust-http"
                        , "build"
                        , "--no-native"
                    )
                    , artifact.buildArgs
                )
                , artifact.rootPath
                , Stream.empty()
            );
    }

    static Function<Artifact, Artifact> hookMavenProxy(Options options)
    {
        return artifact ->
        {
            if (options.mavenProxy != null)
            {
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

    private static class Artifact
    {
        final Path rootPath;
        final Path suitePyPath;
        final String mxVersion;
        final Stream<String> buildArgs;

        Artifact(
            Path rootPath
            , Path suitePyPath
            , String mxVersion
            , Stream<String> buildArgs
        )
        {
            this.rootPath = rootPath;
            this.suitePyPath = suitePyPath;
            this.mxVersion = mxVersion;
            this.buildArgs = buildArgs;
        }
    }
}

class LocalPaths
{
    private static final Path GRAAL_HOME = Path.of("/tmp", "mandrel");

    static Path graalHome()
    {
        return GRAAL_HOME;
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

    static Consumer<String> mvn(Options options)
    {
        // TODO consider splitting mvn into install and deploy
        return artifactName ->
            OperatingSystem.exec()
                .compose(Maven.mavenMvn(options))
                .compose(Maven::artifact)
                .apply(artifactName);
    }

    private static Artifact artifact(String artifactName)
    {
        final var rootPath = LocalPaths.graalHome().resolve(artifactName);
        final var groupId = GROUP_IDS.get(artifactName);
        final var artifactId = ARTIFACT_IDS.get(artifactName);
        return new Artifact(rootPath, groupId, artifactId);
    }

    private static Function<Artifact, OperatingSystem.Command> mavenMvn(Options options)
    {
        return artifact ->
        {
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
                    , String.format("-DgroupId=%s", artifact.groupId)
                    , String.format("-DartifactId=%s", artifact.artifactId)
                    , String.format("-Dversion=%s", options.version)
                    , "-Dpackaging=jar"
                    , String.format(
                        "-Dfile=%s/mxbuild/dists/jdk11/%s.jar"
                        , artifact.rootPath.toString()
                        , artifact.artifactId
                    )
                    , String.format(
                        "-Dsources=%s/mxbuild/dists/jdk11/%s.src.zip"
                        , artifact.rootPath.toString()
                        , artifact.artifactId
                    )
                    , "-DcreateChecksum=true"
                    , repoId
                    , url
                )
                , artifact.rootPath
                , Stream.empty()
            );
        };
    }

    private static class Artifact
    {
        final Path rootPath;
        final String groupId;
        final String artifactId;

        Artifact(Path rootPath, String groupId, String artifactId)
        {
            this.rootPath = rootPath;
            this.groupId = groupId;
            this.artifactId = artifactId;
        }
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
