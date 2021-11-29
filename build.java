import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class build
{
    static final Logger logger = LogManager.getLogger(build.class);
    public static final boolean IS_WINDOWS = System.getProperty("os.name").matches(".*[Ww]indows.*");
    public static final String JDK_VERSION = "jdk" + Runtime.version().feature();

    public static void main(String... args) throws IOException
    {
        Check.main();

        final Options options = Options.from(Args.read(args));
        final FileSystem fs = FileSystem.ofSystem(options);
        final OperatingSystem os = new OperatingSystem();
        final Mx mx = new Mx(fs, os);
        final SequentialBuild sequentialBuild = new SequentialBuild(fs, os, mx);
        final Path mandrelRepo = FileSystem.mandrelRepo(options);

        if (!options.skipClean)
        {
            logger.debugf("Cleaning mxbuild dirs...");
            Mx.removeMxbuilds(mandrelRepo);
        }

        logger.debugf("Building...");
        if (options.mandrelVersion == null)
        {
            options.mandrelVersion = getMandrelVersion(fs, os, mandrelRepo);
        }
        final String mandrelVersionUntilSpace = options.mandrelVersion.split(" ")[0];

        sequentialBuild.build(options);

        logger.debugf("Creating JDK...");
        final Path mandrelHome = FileSystem.mandrelHome(options);
        FileSystem.deleteRecursively(mandrelHome);
        fs.copyDirectory(os.javaHome(), mandrelHome);

        final String OS = System.getProperty("os.name").toLowerCase().split(" ")[0]; // We want "windows" not e.g. "windows 10"
        final String ARCH = System.getProperty("os.arch");
        final String PLATFORM = OS + "-" + ARCH;
        final Path nativeImage = mandrelHome.resolve(Path.of("lib", "svm", "bin", IS_WINDOWS ? "native-image.cmd" : "native-image"));

        if (!options.skipJava)
        {
            logger.debugf("Copy jars...");
            mx.artifacts.forEach((artifact, paths) ->
            {
                final String source = PathFinder.getFirstExisting(mandrelRepo.resolve(paths[0]).toString(), artifact).toString();
                final String destination = mandrelHome.resolve(paths[1]).toString();
                FileSystem.copy(Path.of(source), Path.of(destination));
                FileSystem.copy(Path.of(source.replace(".jar", ".src.zip")), Path.of(destination.replace(".jar", ".src.zip")));
                logger.debugf("Copying .jar and .src.zip for src: %s, dst: %s", source, destination);
            });

            logger.debugf("Copy macros...");
            mx.macroPaths.forEach((macro, path) ->
            {
                final Path source = PathFinder.getFirstExisting(mandrelRepo.resolve(path).toString(), macro);
                final Path destination = mandrelHome.resolve(Path.of("lib", "svm", "macros", macro));
                fs.copyDirectory(source, destination);
                logger.debugf("Copying macro src: %s, dst: %s", source, destination);
            });

            logger.debugf("Copy native-image...");
            FileSystem.copy(mandrelRepo.resolve(
                    Path.of("sdk", "mxbuild", PLATFORM, IS_WINDOWS ? "native-image.exe.image-bash" : "native-image.image-bash",
                            IS_WINDOWS ? "native-image.cmd" : "native-image")), nativeImage);

            logger.debugf("Patch native image...");
            patchNativeImageLauncher(nativeImage, options.mandrelVersion);
        }

        if (!options.skipNative)
        {
            // Although docs are not native per se we chose to install them during the native part of the build
            logger.debugf("Copy docs...");
            FileSystem.copy(mandrelRepo.resolve("LICENSE"), mandrelHome.resolve("LICENSE"));
            FileSystem.copy(mandrelRepo.resolve("THIRD_PARTY_LICENSE.txt"), mandrelHome.resolve("THIRD_PARTY_LICENSE.txt"));
            if (mandrelRepo.resolve("README-Mandrel.md").toFile().exists())
            {
                FileSystem.copy(mandrelRepo.resolve("README-Mandrel.md"), mandrelHome.resolve("README.md"));
            }
            else
            {
                FileSystem.copy(mandrelRepo.resolve("README.md"), mandrelHome.resolve("README.md"));
            }
            FileSystem.copy(mandrelRepo.resolve("SECURITY.md"), mandrelHome.resolve("SECURITY.md"));

            logger.debugf("Native bits...");
            FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.native.libchelper", "include", "amd64cpufeatures.h")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "amd64cpufeatures.h")));
            FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.native.libchelper", "include", "aarch64cpufeatures.h")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "aarch64cpufeatures.h")));
            FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.libffi", "include", "svm_libffi.h")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "svm_libffi.h")));
            FileSystem.copy(mandrelRepo.resolve(Path.of("truffle", "src", "com.oracle.truffle.nfi.native", "include", "trufflenfi.h")),
                mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "trufflenfi.h")));
            String platformAndJDK = PLATFORM + "-" + JDK_VERSION;
            if (IS_WINDOWS)
            {
                Path libchelperSource;
                Path jvmlibSource;
                if (MxVersion.mx5_313_0.compareTo(mx.version) > 0)
                {
                    libchelperSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.libchelper", ARCH, "libchelper.lib");
                    jvmlibSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.jvm.windows", ARCH, "jvm.lib");
                }
                else
                {
                    libchelperSource = Path.of("substratevm", "mxbuild", platformAndJDK, "com.oracle.svm.native.libchelper", ARCH, "libchelper.lib");
                    jvmlibSource = Path.of("substratevm", "mxbuild", platformAndJDK, "com.oracle.svm.native.jvm.windows", ARCH, "jvm.lib");
                }
                FileSystem.copy(mandrelRepo.resolve(libchelperSource),
                        mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "libchelper.lib")));
                FileSystem.copy(mandrelRepo.resolve(jvmlibSource),
                        mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "jvm.lib")));
            }
            else
            {
                Path libchelperSource;
                Path libjvmSource;
                if (MxVersion.mx5_313_0.compareTo(mx.version) > 0)
                {
                    libchelperSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.libchelper", ARCH, "liblibchelper.a");
                    libjvmSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.jvm.posix", ARCH, "libjvm.a");
                }
                else
                {
                    libchelperSource = Path.of("substratevm", "mxbuild", PLATFORM, "com.oracle.svm.native.libchelper", ARCH, "liblibchelper.a");
                    libjvmSource = Path.of("substratevm", "mxbuild", platformAndJDK, "com.oracle.svm.native.jvm.posix", ARCH, "libjvm.a");
                }
                FileSystem.copy(mandrelRepo.resolve(libchelperSource),
                        mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "liblibchelper.a")));
                FileSystem.copy(mandrelRepo.resolve(libjvmSource),
                        mandrelHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "libjvm.a")));
            }
            // We don't create symlink on Windows, See https://github.com/graalvm/mandrel-packaging/pull/71#discussion_r517268470
            if (IS_WINDOWS)
            {
                // exe_link_template.cmd: DOS batch file, ASCII text, with CRLF line terminators
                final String nativeImageCmd = Files.readString(mandrelRepo.resolve(
                    Path.of("sdk", "mx.sdk", "vm", "exe_link_template.cmd")), StandardCharsets.US_ASCII)
                    .replace("<target>", "..\\lib\\svm\\bin\\native-image.cmd");
                Files.writeString(mandrelHome.resolve(
                    Path.of("bin", "native-image.cmd")), nativeImageCmd, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            }
            else
            {
                logger.debugf("Symlink native-image...");
                Files.createSymbolicLink(mandrelHome.resolve(
                    Path.of("bin", "native-image")), Path.of("..", "lib", "svm", "bin", "native-image"));
            }

            if (!options.skipNativeAgents)
            {
                logger.debugf("Build native agents...");
                buildAgents(nativeImage, fs, os);
            }
        }

        logger.info("Congratulations you successfully built Mandrel " + mandrelVersionUntilSpace + " based on Java " + System.getProperty("java.runtime.version"));
        logger.info("You can find your newly built native-image enabled JDK under " + mandrelHome);
        if (options.archiveSuffix != null)
        {
            int javaMajor = Runtime.version().feature();
            String archiveName = "mandrel-java" + javaMajor + "-" + PLATFORM + "-" + mandrelVersionUntilSpace + "." + options.archiveSuffix;
            logger.info("Creating Archive " + archiveName);
            createArchive(fs, os, mandrelHome, archiveName);
        }
    }

    private static void buildAgents(Path nativeImage, FileSystem fs, OperatingSystem os)
    {
        final Tasks.Exec agent = Tasks.Exec.of(Arrays.asList(nativeImage.toString(), "--macro:native-image-agent-library"), fs.workingDir());
        os.exec(agent, false);
        final Tasks.Exec dagent = Tasks.Exec.of(Arrays.asList(nativeImage.toString(), "--macro:native-image-diagnostics-agent-library"), fs.workingDir());
        os.exec(dagent, false);
    }

    private static void createArchive(FileSystem fs, OperatingSystem os, Path mandrelHome, String archiveName)
    {
        final String mandrelHomeParentDir = mandrelHome.getParent().toString();
        final String mandrelHomeDirName = mandrelHome.getFileName().toString();
        Tasks.Exec archiveCommand;
        if (archiveName.endsWith("tar.gz"))
        {
            archiveCommand = Tasks.Exec.of(
                Arrays.asList("tar", "-czf", archiveName, "-C", mandrelHomeParentDir, mandrelHomeDirName),
                fs.workingDir());
        }
        else if (archiveName.endsWith("tarxz"))
        {
            archiveCommand = Tasks.Exec.of(
                Arrays.asList("tar", "cJf", archiveName, "-C", mandrelHomeParentDir, mandrelHomeDirName),
                fs.workingDir(),
                new EnvVar("XZ_OPT", "-9e"));
        }
        else if (archiveName.endsWith("zip") && IS_WINDOWS)
        {
            archiveCommand = Tasks.Exec.of(
                Arrays.asList("powershell",
                    "Compress-Archive -Force -Path " + mandrelHomeParentDir + File.separator + mandrelHomeDirName +
                        " -DestinationPath " + fs.workingDir() + File.separator + archiveName),
                fs.workingDir());
        }
        else if (archiveName.endsWith("zip") && !IS_WINDOWS)
        {
            archiveCommand = Tasks.Exec.of(
                Arrays.asList("zip", "--symlinks", "-r", "-9", fs.workingDir() + File.separator + archiveName, mandrelHomeDirName),
                Path.of(mandrelHomeParentDir));
        }
        else
        {
            throw new IllegalArgumentException("Unsupported archive suffix. Please use one of tar.gz or tarxz or zip");
        }
        os.exec(archiveCommand, false);
    }

    private static String getMandrelVersion(FileSystem fs, OperatingSystem os, Path mandrelRepo)
    {
        String mandrelVersion = os.exec(Mx.mandrelVersion(fs.mxHome(), fs.mandrelRepo()), true).get(0);

        if (mandrelVersion.endsWith("-dev"))
        {
            final Tasks.Exec command = Tasks.Exec.of(Arrays.asList("git", "rev-parse", "--short", "HEAD"), mandrelRepo);
            List<String> output = os.exec(command, true);
            if (!output.isEmpty())
            {
                mandrelVersion += output.get(0);
            }
        }
        else
        {
            mandrelVersion += "-Final";
        }
        return mandrelVersion;
    }


    private static void patchNativeImageLauncher(Path nativeImage, String mandrelVersion) throws IOException
    {
        final List<String> lines = Files.readAllLines(nativeImage);
        // This is jamming two sets of parameters in between three sections of command line.
        // It is fragile at best. We should probably just generate the line fresh.
        final Pattern launcherPattern = Pattern.compile("(.*EnableJVMCI)(.*)");
        logger.debugf("mandrelVersion: %s", mandrelVersion);
        for (int i = 0; i < lines.size(); i++)
        {
            final Matcher launcherMatcher = launcherPattern.matcher(lines.get(i));
            if (launcherMatcher.find())
            {
                logger.debugf("Launcher line BEFORE: %s", lines.get(i));
                logger.debugf("launcherMatcher.group(1): %s", launcherMatcher.group(1));
                logger.debugf("launcherMatcher.group(2): %s", launcherMatcher.group(2));
                String launcherLine = launcherMatcher.group(1) +
                        " -Dorg.graalvm.version=\"" + mandrelVersion + "\"" +
                        " -Dorg.graalvm.config=\"Mandrel Distribution\"" +
                        launcherMatcher.group(2);
                lines.set(i, launcherLine);
                logger.debugf("Launcher line AFTER: %s", lines.get(i));
                break;
            }
        }
        Files.write(nativeImage, lines, StandardCharsets.UTF_8);
    }
}

class Dependency
{
    final String id;
    final String version;
    final String sha1;
    final String sourceSha1;
    final Pattern pattern;

    Dependency(String id, String version, String sha1, String sourceSha1, Pattern pattern)
    {
        this.id = id;
        this.version = version;
        this.sha1 = sha1;
        this.sourceSha1 = sourceSha1;
        this.pattern = pattern;
    }

    static Dependency of(Map<String, String> fields)
    {
        final String id = fields.get("id");
        final String version = fields.get("version");
        final String sha1 = fields.get("sha1");
        final String sourceSha1 = fields.get("sourceSha1");
        final Pattern pattern = Pattern.compile(String.format("%s[^({|\\n)]*\\{", id));
        return new Dependency(id, version, sha1, sourceSha1, pattern);
    }

    @Override
    public String toString()
    {
        return "Dependency{" +
            "id='" + id + '\'' +
            ", version='" + version + '\'' +
            ", sha1='" + sha1 + '\'' +
            ", sourceSha1='" + sourceSha1 + '\'' +
            ", pattern=" + pattern +
            '}';
    }
}

class Options
{
    final MavenAction mavenAction;
    final String mavenVersion;
    String mandrelVersion;
    final boolean verbose;
    final String mavenProxy;
    final String mavenRepoId;
    final String mavenURL;
    final String mavenLocalRepository;
    final String mandrelRepo;
    final String mandrelHome;
    final String mxHome;
    final List<Dependency> dependencies;
    final boolean skipClean;
    final boolean skipJava;
    final boolean skipNative;
    final boolean skipNativeAgents;
    final String mavenHome;
    final String archiveSuffix;

    Options(
        MavenAction action
        , String mavenVersion
        , String mandrelVersion
        , boolean verbose
        , String mavenProxy
        , String mavenRepoId
        , String mavenURL
        , String mavenLocalRepository
        , String mandrelRepo
        , String mandrelHome
        , String mxHome
        , List<Dependency> dependencies
        , boolean skipClean
        , boolean skipJava
        , boolean skipNative
        , boolean skipNativeAgents
        , String mavenHome
        , String archiveSuffix
    )
    {
        this.mavenAction = action;
        this.mavenVersion = mavenVersion;
        this.mandrelVersion = mandrelVersion;
        this.verbose = verbose;
        this.mavenProxy = mavenProxy;
        this.mavenRepoId = mavenRepoId;
        this.mavenURL = mavenURL;
        this.mavenLocalRepository = mavenLocalRepository;
        this.mandrelRepo = mandrelRepo;
        this.mandrelHome = mandrelHome;
        this.mxHome = mxHome;
        this.dependencies = dependencies;
        this.skipClean = skipClean;
        this.skipJava = skipJava;
        this.skipNative = skipNative;
        this.skipNativeAgents = skipNativeAgents;
        this.mavenHome = mavenHome;
        this.archiveSuffix = archiveSuffix;
    }

    public static Options from(Map<String, List<String>> args)
    {
        // Maven related
        Options.MavenAction mavenAction = MavenAction.NOP;
        if (args.containsKey("maven-install"))
        {
            mavenAction = MavenAction.INSTALL;
        }
        if (args.containsKey("maven-deploy"))
        {
            mavenAction = MavenAction.DEPLOY;
        }
        final String mavenVersion = required("maven-version", args, mavenAction != MavenAction.NOP);
        final String mavenProxy = optional("maven-proxy", args);
        final String mavenRepoId = required("maven-repo-id", args, mavenAction == MavenAction.DEPLOY);
        final String mavenURL = required("maven-url", args, mavenAction == MavenAction.DEPLOY);
        final String mavenLocalRepository = optional("maven-local-repository", args);
        final String mavenHome = optional("maven-home", args);

        // Mandrel related
        final String mandrelVersion = optional("mandrel-version", args);
        final String mandrelHome = optional("mandrel-home", args);
        final String mandrelRepo = optional("mandrel-repo", args);

        final boolean verbose = args.containsKey("verbose");
        Logger.debug = verbose;

        final String mxHome = optional("mx-home", args);

        final List<String> dependenciesArg = args.get("dependencies");
        final List<Dependency> dependencies = dependenciesArg == null
            ? Collections.emptyList()
            : toDependencies(dependenciesArg);

        final boolean skipClean = args.containsKey("skip-clean");
        final boolean skipJava = args.containsKey("skip-java");
        final boolean skipNative = args.containsKey("skip-native");
        final boolean skipNativeAgents = args.containsKey("skip-native-agents");

        final String archiveSuffix = optional("archive-suffix", args);

        return new Options(
            mavenAction
            , mavenVersion
            , mandrelVersion
            , verbose
            , mavenProxy
            , mavenRepoId
            , mavenURL
            , mavenLocalRepository
            , mandrelRepo
            , mandrelHome
            , mxHome
            , dependencies
            , skipClean
            , skipJava
            , skipNative
            , skipNativeAgents
            , mavenHome
            , archiveSuffix
        );
    }

    private static List<Dependency> toDependencies(List<String> args)
    {
        return args.stream()
            .map(Options::toFields)
            .map(Dependency::of)
            .collect(Collectors.toList());
    }

    static Map<String, String> toFields(String dependency)
    {
        final List<String> fields = Arrays.asList(dependency.split(","));
        return fields.stream()
            .map(fs -> fs.split("="))
            .collect(Collectors.toMap(fs -> fs[0], fs -> fs[1]));
    }

    static String snapshotVersion(Options options)
    {
        return String.format("%s-SNAPSHOT", options.mavenVersion);
    }

    private static String optional(String name, Map<String, List<String>> args)
    {
        return required(name, args, false);
    }

    private static String required(String name, Map<String, List<String>> args, boolean required)
    {
        final List<String> option = args.get(name);
        if (Objects.isNull(option))
        {
            if (required)
            {
                throw new IllegalArgumentException(String.format("Missing mandatory --%s", name));
            }
            return null;
        }

        if (option.size() == 0)
        {
            throw new IllegalArgumentException(String.format("Missing mandatory value for --%s", name));
        }

        return option.get(0);
    }

    enum MavenAction
    {
        INSTALL, DEPLOY, NOP
    }
}

class SequentialBuild
{
    final FileSystem fs;
    final OperatingSystem os;
    final Mx mx;

    SequentialBuild(FileSystem fs, OperatingSystem os, Mx mx)
    {
        this.fs = fs;
        this.os = os;
        this.mx = mx;
    }

    void build(Options options)
    {
        final Tasks.Exec.Effects exec = new Tasks.Exec.Effects(task -> os.exec(task, false));
        final Tasks.FileReplace.Effects replace = Tasks.FileReplace.Effects.ofSystem();
        Mx.build(options, exec, replace, fs.mxHome(), fs.mandrelRepo(), os.javaHome());
        if (options.mavenAction != Options.MavenAction.NOP && !options.skipJava)
        {
            final Maven maven = Maven.of(fs.mavenHome(), fs.mavenRepoHome());
            maven.mvn(options, exec, replace, fs.mandrelRepo(), fs.workingDir(), mx);
        }
    }
}

class EnvVar
{
    final String name;
    final String value;

    EnvVar(String name, String value)
    {
        this.name = name;
        this.value = value;
    }

    @Override
    public String toString()
    {
        return name + "=" + value;
    }
}

class Tasks
{
    static class Exec
    {
        final List<String> args;
        final Path directory;
        final List<EnvVar> envVars;

        private Exec(List<String> args, Path directory, List<EnvVar> envVars)
        {
            this.args = args;
            this.directory = directory;
            this.envVars = envVars;
        }

        static Exec of(List<String> args, Path directory, EnvVar... envVars)
        {
            final List<String> nonEmpty = args.stream()
                .filter(Predicate.not(String::isEmpty))
                .collect(Collectors.toList());
            return new Tasks.Exec(nonEmpty, directory, Arrays.asList(envVars));
        }

        @Override
        public String toString()
        {
            return "Exec{" +
                "args=" + args +
                ", directory=" + directory +
                ", envVars=" + envVars +
                '}';
        }

        static class Effects
        {
            final Consumer<Exec> exec;

            Effects(Consumer<Exec> exec)
            {
                this.exec = exec;
            }
        }
    }

    static class FileReplace
    {
        private final Path path;
        private final Function<Stream<String>, List<String>> replacer;

        FileReplace(Path path, Function<Stream<String>, List<String>> replacer)
        {
            this.path = path;
            this.replacer = replacer;
        }

        static void replace(FileReplace replace, Effects effects)
        {
            try (Stream<String> lines = effects.readLines.apply(replace.path))
            {
                final List<String> transformed = replace.replacer.apply(lines);
                effects.writeLines.accept(replace.path, transformed);
            }
        }

        static void copyReplace(FileReplace replace, Path from, Effects effects)
        {
            effects.copy.accept(from, replace.path);
            FileReplace.replace(
                new FileReplace(replace.path, replace.replacer)
                , effects
            );
        }

        static class Effects
        {
            final Function<Path, Stream<String>> readLines;
            final BiConsumer<Path, List<String>> writeLines;
            final BiConsumer<Path, Path> copy;

            private Effects(
                Function<Path, Stream<String>> readLines
                , BiConsumer<Path, List<String>> writeLines
                , BiConsumer<Path, Path> copy
            )
            {
                this.readLines = readLines;
                this.writeLines = writeLines;
                this.copy = copy;
            }

            public static Effects ofSystem()
            {
                return new Effects(
                    FileSystem::readLines
                    , FileSystem::writeLines
                    , FileSystem::copy
                );
            }

            public static Effects noop()
            {
                // @formatter:off
                return new Effects(p -> Stream.empty(), (p, l) -> {}, (src, target) -> {});
                // @formatter:on
            }
        }
    }
}

class Mx
{
    final String[] mxDistDir;
    final Path compilerDistPath;
    final Path truffleDistPath;
    final Path substrateDistPath;
    final Path sdkDistPath;

    final String[] mxbuildDir;
    final Path sdkBuildPath;

    final MxVersion version;

    /**
     * A map of entries with a key (the jar artifact) and 3 String values. The first String is group ID of the artifact,
     * the second String is its source path in the mxbuild directories, and the third String is its destination in the
     * target JDK.
     */
    final Map<String, Path[]> artifacts;
    final Map<String, Path> macroPaths;

    private static final Logger LOG = LogManager.getLogger(Mx.class);

    private static final Pattern DEPENDENCY_SHA1_PATTERN =
        Pattern.compile("\"sha1\"\\s*:\\s*\"([a-f0-9]*)\"");
    private static final Pattern DEPENDENCY_SOURCE_SHA1_PATTERN =
        Pattern.compile("\"sourceSha1\"\\s*:\\s*\"([a-f0-9]*)\"");
    private static final Pattern DEPENDENCY_VERSION_PATTERN =
        Pattern.compile("\"version\"\\s*:\\s*\"([0-9.]*)\"");

    static final List<BuildArgs> BUILD_JAVA_STEPS = List.of(
        BuildArgs.of("--no-native", "--dependencies", "SVM,SVM_DRIVER,SVM_AGENT,SVM_DIAGNOSTICS_AGENT")
        , BuildArgs.of("--only",
                build.IS_WINDOWS ?
                        "native-image.exe.image-bash," +
                                "native-image-agent-library_native-image.properties," +
                                "native-image-launcher_native-image.properties," +
                                "native-image-diagnostics-agent-library_native-image.properties"
                        :
                        "native-image.image-bash," +
                                "native-image-agent-library_native-image.properties," +
                                "native-image-launcher_native-image.properties," +
                                "native-image-diagnostics-agent-library_native-image.properties")
    );

    static final List<BuildArgs> BUILD_NATIVE_STEPS = List.of(
        BuildArgs.of("--projects",
            build.IS_WINDOWS ?
                "com.oracle.svm.native.libchelper," +
                    "com.oracle.svm.native.jvm.windows," +
                    "com.oracle.svm.core.windows" :
                "com.oracle.svm.native.libchelper," +
                    "com.oracle.svm.native.jvm.posix")
    );

    Mx(FileSystem fs, OperatingSystem os)
    {
        version = new MxVersion(os.exec(Mx.mxversion(fs.mxHome()), true).get(0));

        if (MxVersion.mx5_313_0.compareTo(version) > 0 ) {
            mxDistDir = new String[]{"mxbuild", "dists", PathFinder.JDK_NUMBER_DIRECTORY};
            mxbuildDir = new String[]{"mxbuild"};
        }
        else
        {
            mxDistDir = new String[]{"mxbuild", build.JDK_VERSION, "dists", PathFinder.JDK_NUMBER_DIRECTORY};
            mxbuildDir = new String[]{"mxbuild", build.JDK_VERSION};
        }
        compilerDistPath = Path.of("compiler", mxDistDir);
        truffleDistPath = Path.of("truffle", mxDistDir);
        substrateDistPath = Path.of("substratevm", mxDistDir);
        sdkDistPath = Path.of("sdk", mxDistDir);

        sdkBuildPath = Path.of("sdk", mxbuildDir);


        artifacts = Map.ofEntries(
                new SimpleEntry<>("org.graalvm.sdk:graal-sdk.jar",
                        new Path[]{sdkDistPath.resolve("graal-sdk.jar"), Path.of("lib", "jvmci", "graal-sdk.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:svm.jar",
                        new Path[]{substrateDistPath.resolve("svm.jar"), Path.of("lib", "svm", "builder", "svm.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:native-image-base.jar",
                        new Path[]{substrateDistPath.resolve("native-image-base.jar"), Path.of("lib", "svm", "builder", "native-image-base.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:pointsto.jar",
                        new Path[]{substrateDistPath.resolve("pointsto.jar"), Path.of("lib", "svm", "builder", "pointsto.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:library-support.jar",
                        new Path[]{substrateDistPath.resolve("library-support.jar"), Path.of("lib", "svm", "library-support.jar")}),
                new SimpleEntry<>("org.graalvm.truffle:truffle-api.jar",
                        new Path[]{truffleDistPath.resolve("truffle-api.jar"), Path.of("lib", "truffle", "truffle-api.jar")}),
                new SimpleEntry<>("org.graalvm.compiler:compiler.jar",
                        new Path[]{compilerDistPath.resolve("graal.jar"), Path.of("lib", "jvmci", "graal.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:objectfile.jar",
                        new Path[]{substrateDistPath.resolve("objectfile.jar"), Path.of("lib", "svm", "builder", "objectfile.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:svm-driver.jar",
                        new Path[]{substrateDistPath.resolve("svm-driver.jar"), Path.of("lib", "graalvm", "svm-driver.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:jvmti-agent-base.jar",
                        new Path[]{substrateDistPath.resolve("jvmti-agent-base.jar"), Path.of("lib", "graalvm", "jvmti-agent-base.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:svm-agent.jar",
                        new Path[]{substrateDistPath.resolve("svm-agent.jar"), Path.of("lib", "graalvm", "svm-agent.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:svm-diagnostics-agent.jar",
                        new Path[]{substrateDistPath.resolve("svm-diagnostics-agent.jar"), Path.of("lib", "graalvm", "svm-diagnostics-agent.jar")}),
                new SimpleEntry<>("org.graalvm.nativeimage:svm-configure.jar",
                        new Path[]{substrateDistPath.resolve("svm-configure.jar"), Path.of("lib", "graalvm", "svm-configure.jar")})
        );

        macroPaths = Map.ofEntries(
                new SimpleEntry<>("native-image-agent-library", sdkBuildPath.resolve(Path.of("native-image.properties", "native-image-agent-library"))),
                new SimpleEntry<>("native-image-launcher", sdkBuildPath.resolve(Path.of("native-image.properties", "native-image-launcher"))),
                new SimpleEntry<>("native-image-diagnostics-agent-library", sdkBuildPath.resolve(Path.of("native-image.properties", "native-image-diagnostics-agent-library")))
        );
    }

    static void removeMxbuilds(Path mandrelRepo) throws IOException
    {
        final PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:**/mxbuild");
        Files.walkFileTree(mandrelRepo, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes arg1) throws IOException
            {
                if (pathMatcher.matches(path) && Files.isDirectory(path))
                {
                    LOG.debugf("Deleting %s", path.toString());
                    FileSystem.deleteRecursively(path);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void build(
        Options options
        , Tasks.Exec.Effects exec
        , Tasks.FileReplace.Effects replace
        , Path mxHome
        , Path mandrelRepo
        , Path javaHome
    )
    {
        swapDependencies(options, replace, mxHome);
        removeDependencies(replace, mandrelRepo);
        hookMavenProxy(options, replace, mxHome);

        final boolean clean = !options.skipClean;
        if (clean)
            exec.exec.accept(mxclean(options, mxHome, mandrelRepo));

        if (!options.skipJava)
        {
            BUILD_JAVA_STEPS.stream()
                .map(mxbuild(options, mxHome, mandrelRepo, javaHome))
                .forEach(exec.exec);
        }

        if (!options.skipNative)
        {
            BUILD_NATIVE_STEPS.stream()
                .map(mxbuild(options, mxHome, mandrelRepo, javaHome))
                .forEach(exec.exec);
        }
    }

    private static Tasks.Exec mxclean(
        Options options
        , Path mxHome
        , Path mandrelRepo
    )
    {
        final Path mx = mxHome.resolve("mx");
        return execTask(
            Arrays.asList(
                mx.toString()
                , options.verbose ? "-V" : ""
                , "clean"
            )
            , mandrelRepo.resolve("substratevm")
        );
    }

    static Tasks.Exec mxversion(Path mxHome)
    {
        final Path mx = mxHome.resolve("mx");
        return execTask(
            Arrays.asList(
                mx.toString()
                , "version"
            )
            , mxHome
        );
    }

    static Tasks.Exec mandrelVersion(
        Path mxHome
        , Path mandrelRepo
    )
    {
        final Path mx = mxHome.resolve("mx");
        return execTask(
            Arrays.asList(
                mx.toString()
                , "graalvm-version"
            )
            , mandrelRepo.resolve("substratevm")
        );
    }

    private static Function<BuildArgs, Tasks.Exec> mxbuild(
        Options options
        , Path mxHome
        , Path mandrelRepo
        , Path javaHome
    )
    {
        return buildArgs ->
        {
            final Path mx = mxHome.resolve("mx");
            final List<String> args = Lists.concat(
                List.of(
                    mx.toString()
                    , options.verbose ? "-V" : ""
                    , "--trust-http"
                    , "--no-jlinking"
                    , "--java-home"
                    , javaHome.toString()
                    , "--native-images=lib:native-image-agent,lib:native-image-diagnostics-agent"
                    , "--exclude-components=nju,svmnfi"
                    , "build"
                )
                , buildArgs.args
            );

            return execTask(
                args
                , mandrelRepo.resolve("substratevm")
            );
        };
    }

    private static Tasks.Exec execTask(List<String> args, Path directory, EnvVar... envVars)
    {
        final EnvVar[] mxEnvVars = new EnvVar[envVars.length + 1];
        mxEnvVars[0] = new EnvVar("MX_PYTHON", "python3");
        System.arraycopy(envVars, 0, mxEnvVars, 1, envVars.length);
        return Tasks.Exec.of(args, directory, mxEnvVars);
    }

    static void removeDependencies(Tasks.FileReplace.Effects effects, Path mandrelRepo)
    {
        LOG.debugf("Remove dependencies");
        Path suitePy = Path.of("substratevm", "mx.substratevm", "suite.py");
        Path path = mandrelRepo.resolve(suitePy);
        List<String> dependencies = Arrays.asList(
            "com.oracle.svm.truffle",
            "com.oracle.svm.truffle.api                   to org.graalvm.truffle",
            "com.oracle.truffle.api.TruffleLanguage.Provider",
            "com.oracle.truffle.api.instrumentation.TruffleInstrument.Provider",
            "com.oracle.svm.polyglot",
            "truffle:TRUFFLE_API",
            "com.oracle.svm.truffle.nfi",
            "com.oracle.svm.truffle.nfi.posix",
            "com.oracle.svm.truffle.nfi.windows",
            "extracted-dependency:truffle:LIBFFI_DIST",
            "extracted-dependency:truffle:TRUFFLE_NFI_NATIVE/include/*",
            "file:src/com.oracle.svm.libffi/include/svm_libffi.h",
            "org.graalvm.libgraal.jni");
        Tasks.FileReplace.replace(
            new Tasks.FileReplace(path, removeDependencies(dependencies))
            , effects
        );
        suitePy = Path.of("compiler", "mx.compiler", "suite.py");
        path = mandrelRepo.resolve(suitePy);
        dependencies = Arrays.asList(
            "org.graalvm.libgraal.jni");
        Tasks.FileReplace.replace(
            new Tasks.FileReplace(path, removeDependencies(dependencies))
            , effects
        );
    }

    private static Function<Stream<String>, List<String>> removeDependencies(List<String> dependencies)
    {
        return lines ->
            lines.filter(l -> dependenciesFilter(l, dependencies)).collect(Collectors.toList());
    }

    private static boolean dependenciesFilter(String line, List<String> dependencies)
    {
        for (String dependency : dependencies)
        {
            if (line.contains("\"" + dependency + "\","))
            {
                LOG.debugf("REMOVING dependency : " + dependency);
                return false;
            }
        }
        LOG.debugf("KEEPING : " + line);
        return true;
    }

    static void swapDependencies(Options options, Tasks.FileReplace.Effects effects, Path mxHome)
    {
        final List<Dependency> dependencies = options.dependencies;
        if (dependencies.isEmpty())
        {
            return;
        }
        LOG.debugf("Swap dependencies: %s", dependencies);
        final Path suitePy = Path.of("mx.mx", "suite.py");
        final Path path = mxHome.resolve(suitePy);

        Tasks.FileReplace.replace(
            new Tasks.FileReplace(path, swapDependencies(dependencies))
            , effects
        );
    }

    private static Function<Stream<String>, List<String>> swapDependencies(List<Dependency> dependencies)
    {
        return lines -> applyDependencies(dependencies, parseSuitePy(dependencies, lines));
    }

    private static List<String> applyDependencies(List<Dependency> dependencies, ParsedDependencies parsed)
    {
        final List<String> result = new ArrayList<>(parsed.lines);
        dependencies.forEach(apply(a -> a.version, parsed.versions, result));
        dependencies.forEach(apply(a -> a.sha1, parsed.sha1s, result));
        dependencies.forEach(apply(a -> a.sourceSha1, parsed.sourceSha1s, result));
        return result;
    }

    static Consumer<Dependency> apply(
        Function<Dependency, String> extract
        , Map<String, Coordinate> values
        , List<String> lines
    )
    {
        return artifact ->
        {
            final Coordinate coordinate = values.get(artifact.id);
            if (coordinate != null)
            {
                final String line = lines.get(coordinate.lineNumber);
                final String replaced = line.replace(coordinate.value, extract.apply(artifact));
                lines.set(coordinate.lineNumber, replaced);
            }
        };
    }

    static ParsedDependencies parseSuitePy(List<Dependency> dependencies, Stream<String> lines)
    {
        int lineNumber = -1;
        String id = null;
        String tmp;

        final List<String> output = new ArrayList<>();
        final Map<String, Coordinate> versions = new HashMap<>();
        final Map<String, Coordinate> sha1s = new HashMap<>();
        final Map<String, Coordinate> sourceSha1s = new HashMap<>();

        final Iterator<String> it = lines.iterator();
        while (it.hasNext())
        {
            final String line = it.next();

            lineNumber++;
            output.add(line);

            if (id == null)
            {
                final Optional<Dependency> maybeArtifact = dependencies.stream()
                    .filter(artifact -> artifact.pattern.matcher(line).find())
                    .findFirst();

                if (maybeArtifact.isPresent())
                {
                    id = maybeArtifact.get().id;
                }
            }
            else
            {
                tmp = extract(line, DEPENDENCY_SHA1_PATTERN);
                if (tmp != null)
                {
                    sha1s.put(id, new Coordinate(tmp, lineNumber));
                    continue;
                }

                tmp = extract(line, DEPENDENCY_SOURCE_SHA1_PATTERN);
                if (tmp != null)
                {
                    sourceSha1s.put(id, new Coordinate(tmp, lineNumber));
                    continue;
                }

                tmp = extract(line, DEPENDENCY_VERSION_PATTERN);
                if (tmp != null)
                {
                    versions.put(id, new Coordinate(tmp, lineNumber));
                    id = null;
                }
            }
        }

        return new ParsedDependencies(output, versions, sha1s, sourceSha1s);
    }

    static String extract(String line, Pattern pattern)
    {
        final Matcher matcher = pattern.matcher(line);
        if (matcher.find())
        {
            return matcher.group(1);
        }
        return null;
    }

    static void hookMavenProxy(Options options, Tasks.FileReplace.Effects effects, Path mxHome)
    {
        if (options.mavenProxy != null)
        {
            final Path mxPy = mxHome.resolve("mx.py");
            Tasks.FileReplace.replace(
                new Tasks.FileReplace(mxPy, prependMavenProxyToMxPy(options))
                , effects
            );
        }
    }

    private static Function<Stream<String>, List<String>> prependMavenProxyToMxPy(Options options)
    {
        return lines ->
            lines
                .filter(Mx::notMavenOrg)
                .map(prependMavenProxy(options))
                .collect(Collectors.toList());
    }

    private static boolean notMavenOrg(String line)
    {
        return !line.contains("maven.org");
    }

    private static Function<String, String> prependMavenProxy(Options options)
    {
        return line ->
            line.contains("_mavenRepoBaseURLs")
                ? line.replaceFirst("\\[", String.format("[ %s", String.format("\"%s/\"", options.mavenProxy)))
                : line;
    }

    static class BuildArgs
    {
        final List<String> args;

        BuildArgs(List<String> args)
        {
            this.args = args;
        }

        static BuildArgs of(String... args)
        {
            return new BuildArgs(Arrays.asList(args));
        }
    }

    static final class ParsedDependencies
    {
        final List<String> lines;
        final Map<String, Coordinate> versions;
        final Map<String, Coordinate> sha1s;
        final Map<String, Coordinate> sourceSha1s;

        ParsedDependencies(
            List<String> lines
            , Map<String, Coordinate> versions
            , Map<String, Coordinate> sha1s
            , Map<String, Coordinate> sourceSha1s
        )
        {
            this.lines = lines;
            this.versions = versions;
            this.sha1s = sha1s;
            this.sourceSha1s = sourceSha1s;
        }
    }

    static final class Coordinate
    {
        final String value;
        final int lineNumber;

        Coordinate(String value, int lineNumber)
        {
            this.value = value;
            this.lineNumber = lineNumber;
        }

        @Override
        public String toString()
        {
            return "Coordinate{" +
                "value='" + value + '\'' +
                ", lineNumber=" + lineNumber +
                '}';
        }
    }
}

class MxVersion implements Comparable<MxVersion>
{
    final int major;
    final int minor;
    final int patch;
    static final MxVersion mx5_313_0 = new MxVersion("5.313.0");

    MxVersion (String version)
    {
        String[] split = version.split("\\.");
        if (split.length != 3)
        {
            throw new IllegalArgumentException("Version should be of the form MAJOR.MINOR.PATCH not " + version);
        }
        major = Integer.parseInt(split[0]);
        minor = Integer.parseInt(split[1]);
        patch = Integer.parseInt(split[2]);
    }

    @Override
    public int compareTo(MxVersion other)
    {
        int result = major - other.major;
        if (result != 0)
        {
            return result;
        }
        result = minor - other.minor;
        if (result != 0)
        {
            return result;
        }
        return patch - other.patch;
    }
}

/**
 * Components reside in directories based on jdk compatibility, e.g.
 * ./substratevm/mxbuild/dists/jdk17/svm-agent.jar
 * ./substratevm/mxbuild/dists/jdk16/svm.jar
 * ./truffle/mxbuild/dists/jdk11/truffle-api.jar
 * ./truffle/mxbuild/dists/jdk9/truffle-dsl-processor.jar
 * <p>
 * It is not practical to hard code those, so this util class finds them.
 */
class PathFinder
{
    private static final Logger LOG = LogManager.getLogger(PathFinder.class);
    // Highest compatible is selected, e.g. jdk17, even if jdk11 dir might be there too.
    public static final List<String> POSSIBLE_DIRS = IntStream.rangeClosed(9, Runtime.version().feature())
        .boxed().sorted(Collections.reverseOrder()).map(i -> "jdk" + i).collect(Collectors.toList());
    public static final String JDK_NUMBER_DIRECTORY = "@JDK_NUMBER_DIRECTORY@";

    static Path getFirstExisting(String basePath, String artifact)
    {
        for (String dir : POSSIBLE_DIRS)
        {
            final String file = basePath.replace(JDK_NUMBER_DIRECTORY, dir);
            LOG.debugf("Trying path: %s", file);
            if (new File(file).exists())
            {
                // returning file without suffix
                return Path.of(file);
            }
        }
        throw new IllegalArgumentException("There is no existing dir for record " + artifact);
    }
}

class Maven
{
    static final String INSTALL_FILE_VERSION = "2.4";

    static final String INSTALL_FILE_GOAL = String.format(
        "org.apache.maven.plugins:maven-install-plugin:%s:install-file"
        , INSTALL_FILE_VERSION
    );

    static final String DEPLOY_FILE_VERSION = "2.7";

    static final String DEPLOY_FILE_GOAL = String.format(
        "org.apache.maven.plugins:maven-deploy-plugin:%s:deploy-file"
        , DEPLOY_FILE_VERSION
    );

    final Path mvn;
    final Path repoHome;

    private Maven(Path mvn, Path repoHome)
    {
        this.mvn = mvn;
        this.repoHome = repoHome;
    }

    static Maven of(Path lazyHome, Path repoHome)
    {
        final Path mvn = lazyHome.toString().isEmpty()
            ? Path.of("mvn")
            : Path.of("bin", "mvn");
        return new Maven(lazyHome.resolve(mvn), repoHome);
    }

    void mvn(
        Options options
        , Tasks.Exec.Effects exec
        , Tasks.FileReplace.Effects replace
        , Path mandrelRepo
        , Path workingDir
        , Mx mx
    )
    {
        // Only invoke mvn if all mx builds succeeded
        final List<Maven.ReleaseArtifact> releaseArtifacts = new ArrayList<>();
        mx.artifacts.forEach((artifact, paths) ->
        {
            String[] split = artifact.split(":");
            String groupID = split[0];
            String artifactID = split[1];
            Path distsPath = PathFinder.getFirstExisting(mandrelRepo.resolve(paths[0]).toString(), artifact);
            final Maven.Artifact mvnArtifact = new Artifact(groupID, artifactID, distsPath);

            exec.exec.accept(mvnInstallSnapshot(mvnArtifact, options, mandrelRepo));
            exec.exec.accept(mvnInstallAssembly(mvnArtifact, options, replace, workingDir));

            final Maven.ReleaseArtifact releaseArtifact = ReleaseArtifact.of(mvnArtifact, options, workingDir, repoHome);
            exec.exec.accept(mvnInstallRelease(releaseArtifact, options, replace, workingDir));
            releaseArtifacts.add(releaseArtifact);
        });

        // Only deploy if all mvn installs worked
        if (options.mavenAction == Options.MavenAction.DEPLOY)
        {
            releaseArtifacts.forEach(mvnDeploy(options, exec, workingDir));
        }
    }

    private Consumer<ReleaseArtifact> mvnDeploy(Options options, Tasks.Exec.Effects exec, Path workingDir)
    {
        return artifact -> exec.exec.accept(deploy(artifact, options, workingDir));
    }

    private Tasks.Exec deploy(ReleaseArtifact artifact, Options options, Path workingDir)
    {
        return Tasks.Exec.of(
            Arrays.asList(
                mvn.toString()
                , options.verbose ? "--debug" : ""
                , DEPLOY_FILE_GOAL
                , String.format("-DgroupId=%s", artifact.groupId)
                , String.format("-DartifactId=%s", artifact.artifactId)
                , String.format("-Dversion=%s", options.mavenVersion)
                , "-Dpackaging=jar"
                , String.format("-Dfile=%s", artifact.jarPath)
                , String.format("-Dsources=%s", artifact.sourceJarPath)
                , "-DcreateChecksum=true"
                , String.format("-DpomFile=%s", artifact.pomPaths.target)
                , String.format("-DrepositoryId=%s", options.mavenRepoId)
                , String.format("-Durl=%s", options.mavenURL)
            )
            , workingDir
        );
    }

    private static Function<Stream<String>, List<String>> replacePomXml(Options options)
    {
        return lines ->
            lines
                .map(line -> line.replace("999", options.mavenVersion))
                .collect(Collectors.toList());
    }

    private Tasks.Exec mvnInstallSnapshot(Artifact artifact, Options options, Path mandrelRepo)
    {
        return Tasks.Exec.of(
            Arrays.asList(
                mvn.toString()
                , options.verbose ? "--debug" : ""
                , INSTALL_FILE_GOAL
                , String.format("-DgroupId=%s", artifact.groupId)
                , String.format("-DartifactId=%s", artifact.artifactId)
                , String.format("-Dversion=%s", Options.snapshotVersion(options))
                , "-Dpackaging=jar"
                , String.format(
                    "-Dfile=%s.jar"
                    , artifact.distsPath.toString()
                )
                , String.format(
                    "-Dsources=%s.src.zip"
                    , artifact.distsPath
                )
                , "-DcreateChecksum=true"
            )
            , mandrelRepo
        );
    }

    private Tasks.Exec mvnInstallAssembly(
        Artifact artifact
        , Options options
        , Tasks.FileReplace.Effects effects
        , Path workingDir
    )
    {
        final Path pomPath = Path.of(
            "assembly"
            , artifact.artifactId
            , "pom.xml"
        );
        final Maven.DirectionalPaths paths = DirectionalPaths.ofPom(pomPath, workingDir);

        Tasks.FileReplace.copyReplace(
            new Tasks.FileReplace(paths.target, Maven.replacePomXml(options))
            , paths.source
            , effects
        );

        return Tasks.Exec.of(
            Arrays.asList(
                mvn.toString()
                , options.verbose ? "--debug" : ""
                , "install"
            )
            , paths.target.getParent()
        );
    }

    private Tasks.Exec mvnInstallRelease(
        ReleaseArtifact releaseArtifact
        , Options options
        , Tasks.FileReplace.Effects replace
        , Path workingDir
    )
    {
        Tasks.FileReplace.copyReplace(
            new Tasks.FileReplace(releaseArtifact.pomPaths.target, Maven.replacePomXml(options))
            , releaseArtifact.pomPaths.source
            , replace
        );

        return Tasks.Exec.of(
            Arrays.asList(
                mvn.toString()
                , options.verbose ? "--debug" : ""
                , INSTALL_FILE_GOAL
                , String.format("-DgroupId=%s", releaseArtifact.groupId)
                , String.format("-DartifactId=%s", releaseArtifact.artifactId)
                , String.format("-Dversion=%s", options.mavenVersion)
                , "-Dpackaging=jar"
                , String.format("-Dfile=%s", releaseArtifact.jarPath)
                , String.format("-Dsources=%s", releaseArtifact.sourceJarPath)
                , "-DcreateChecksum=true"
                , String.format("-DpomFile=%s", releaseArtifact.pomPaths.target)
            )
            , workingDir
        );
    }

    private static final class Artifact
    {
        final String groupId;
        final String artifactId;
        final Path distsPath;

        Artifact(String groupId, String artifactId, Path distsPath)
        {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.distsPath = distsPath;
        }
    }

    private static final class ReleaseArtifact
    {
        final String groupId;
        final String artifactId;
        final DirectionalPaths pomPaths;
        final Path jarPath;
        final Path sourceJarPath;

        private ReleaseArtifact(
            String groupId
            , String artifactId
            , DirectionalPaths pomPaths
            , Path jarPath
            , Path sourceJarPath
        )
        {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.pomPaths = pomPaths;
            this.jarPath = jarPath;
            this.sourceJarPath = sourceJarPath;
        }

        // TODO Release artifact should not depend on mavenRepoHome,
        //      it can be built with relative paths throughout.
        //      The final paths can be computed when constructing the mvn command.
        static ReleaseArtifact of(Artifact artifact, Options options, Path workingDir, Path mavenRepoHome)
        {
            final Path releasePomPath = Path.of(
                "release"
                , artifact.artifactId
                , "pom.xml"
            );

            final Maven.DirectionalPaths pomPaths = DirectionalPaths.ofPom(releasePomPath, workingDir);

            final String jarName = String.format(
                "%s-%s-ASSEMBLY-jar-with-dependencies.jar"
                , artifact.artifactId
                , options.mavenVersion
            );

            final Path artifactPath = mavenRepoHome
                .resolve(Path.of(artifact.groupId.replace(".", "/")))
                .resolve(artifact.artifactId);

            final Path jarPath = artifactPath
                .resolve(String.format("%s-ASSEMBLY", options.mavenVersion))
                .resolve(jarName);

            final String sourceJarName = String.format(
                "%s-%s-SNAPSHOT-sources.jar"
                , artifact.artifactId
                , options.mavenVersion
            );

            final Path sourceJarPath = artifactPath
                .resolve(String.format("%s-SNAPSHOT", options.mavenVersion))
                .resolve(sourceJarName);

            return new ReleaseArtifact(
                artifact.groupId
                , artifact.artifactId
                , pomPaths
                , jarPath
                , sourceJarPath
            );
        }
    }

    static final class DirectionalPaths
    {
        final Path source;
        final Path target;

        DirectionalPaths(Path source, Path target)
        {
            this.source = source;
            this.target = target;
        }

        static DirectionalPaths ofPom(Path pomPath, Path workingDir)
        {
            return new DirectionalPaths(
                workingDir.resolve(Path.of("resources").resolve(pomPath))
                , workingDir.resolve(Path.of("target").resolve(pomPath))
            );
        }
    }
}

// Dependency
class FileSystem
{
    static final Logger LOG = LogManager.getLogger(FileSystem.class);

    private final Path mandrelRepo;
    private final Path mxHome;
    private final Path workingDir;
    private final Path mavenRepoHome;
    private final Path mavenHome;

    FileSystem(Path mandrelRepo, Path mxHome, Path workingDir, Path mavenRepoHome, Path mavenHome)
    {
        this.mandrelRepo = mandrelRepo;
        this.mxHome = mxHome;
        this.workingDir = workingDir;
        this.mavenRepoHome = mavenRepoHome;
        this.mavenHome = mavenHome;
    }

    Path mxHome()
    {
        return mxHome;
    }

    Path mandrelRepo()
    {
        return mandrelRepo;
    }

    Path workingDir()
    {
        return workingDir;
    }

    Path mavenRepoHome()
    {
        return mavenRepoHome;
    }

    Path mavenHome()
    {
        return mavenHome;
    }

    static Stream<String> readLines(Path path)
    {
        try
        {
            return Files.lines(path);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    static void writeLines(Path path, Iterable<? extends CharSequence> lines)
    {
        try
        {
            Files.write(path, lines);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    static void copy(Path from, Path to)
    {
        try
        {
            LOG.debugf("Create parent directories for %s", to);
            FileSystem.mkdirs(to.getParent());

            LOG.debugf("Copy %s to %s", from, to);
            Files.copy(from, to, REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static void mkdirs(Path path)
    {
        final File file = path.toFile();
        if (!file.exists())
        {
            final boolean created = file.mkdirs();
            if (!created)
                throw new RuntimeException("Failed to create target directory");
        }
    }

    public static void deleteRecursively(Path directory) throws IOException
    {
        if (Files.notExists(directory))
        {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    void copyDirectory(Path source, Path destination)
    {
        assert source.toFile().isDirectory();
        assert !destination.toFile().exists() || destination.toFile().isDirectory();
        CopyVisitor copyVisitor = new CopyVisitor(source, destination);
        try
        {
            Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, copyVisitor);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    static class CopyVisitor extends SimpleFileVisitor<Path>
    {
        private final Path source;
        private final Path destination;

        public CopyVisitor(Path source, Path destination)
        {
            this.source = source;
            this.destination = destination;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
        {
            final Path relativePath = source.relativize(file);
            copy(file, destination.resolve(relativePath));
            return FileVisitResult.CONTINUE;
        }
    }

    static FileSystem ofSystem(Options options)
    {
        final Path mandrelRepo = mandrelRepo(options);
        final Path mxHome = mxHome(options);
        final String userDir = System.getProperty("user.dir");
        final Path workingDir = new File(userDir).toPath();
        final Path mavenRepoHome = mavenRepoHome(options);
        final Path mavenHome = mavenHome(options);
        return new FileSystem(mandrelRepo, mxHome, workingDir, mavenRepoHome, mavenHome);
    }

    static Path mandrelRepo(Options options)
    {
        if (options.mandrelRepo == null)
        {
            return Path.of("/tmp", "mandrel");
        }

        return Path.of(options.mandrelRepo);
    }

    static Path mandrelHome(Options options)
    {
        if (options.mandrelHome == null)
        {
            int javaMajor = Runtime.version().feature();
            return Path.of(".", "mandrel-java" + javaMajor + "-" + options.mandrelVersion.split(" ")[0]);
        }

        return Path.of(options.mandrelHome);
    }

    private static Path mxHome(Options options)
    {
        if (options.mxHome == null)
        {
            return Path.of("/opt", "mx");
        }

        return Path.of(options.mxHome);
    }

    private static Path mavenRepoHome(Options options)
    {
        if (options.mavenLocalRepository == null)
        {
            final String userHome = System.getProperty("user.home");
            return Path.of(userHome, ".m2", "repository");
        }

        return Path.of(options.mavenLocalRepository);
    }

    private static Path mavenHome(Options options)
    {
        return Path.of(Objects.requireNonNullElse(options.mavenHome, ""));
    }
}

class OperatingSystem
{
    static final Logger LOG = LogManager.getLogger(OperatingSystem.class);

    List<String> exec(Tasks.Exec task, boolean getOutput)
    {
        LOG.debugf("Execute %s in %s with environment variables %s", task.args, task.directory, task.envVars);
        try
        {
            List<String> s = new ArrayList<>();
            if (build.IS_WINDOWS)
            {
                s.add("cmd");
                s.add("/C");
            }
            s.addAll(task.args);
            final ProcessBuilder processBuilder = new ProcessBuilder(s)
                .directory(task.directory.toFile())
                .inheritIO();

            task.envVars.forEach(
                envVar -> processBuilder.environment()
                    .put(envVar.name, envVar.value)
            );

            File outputFile = null;
            if (getOutput)
            {
                outputFile = File.createTempFile("mandrel-builder-exec-output", "txt");
                outputFile.deleteOnExit();
                processBuilder.redirectOutput(outputFile);
            }
            Process process = processBuilder.start();

            if (process.waitFor() != 0)
            {
                throw new RuntimeException(
                    "Failed, exit code: " + process.exitValue()
                );
            }

            if (getOutput)
            {
                final BufferedReader bufferedReader = new BufferedReader(new FileReader(outputFile));
                List<String> result = bufferedReader.lines().collect(Collectors.toList());
                bufferedReader.close();
                return result;
            }
            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    Path javaHome()
    {
        return Path.of(System.getProperty("java.home"));
    }
}

final class Logger
{
    static boolean debug;

    final String name;

    Logger(String name)
    {
        this.name = name;
    }

    void debugf(String format, Object... params)
    {
        if (debug)
        {
            System.out.printf("DEBUG [%s] %s%n"
                , name
                , String.format(format, params)
            );
        }
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

final class Lists
{
    static <E> List<E> concat(List<? extends E> a, List<? extends E> b)
    {
        final List<E> list = new ArrayList<>(a.size() + b.size());
        list.addAll(a);
        list.addAll(b);
        return list;
    }
}

final class Check
{
    public static void main(String... args)
    {
        shouldEnableAssertions();
        checkMx();
    }

    static void checkMx()
    {
        final Options options = Options.from(Args.read("--maven-version-suffix", ".redhat-00001"));
        final RecordingOperatingSystem os = new RecordingOperatingSystem();
        final Tasks.Exec.Effects exec = new Tasks.Exec.Effects(os::record);
        final Tasks.FileReplace.Effects replace = Tasks.FileReplace.Effects.noop();
        Mx.build(options, exec, replace, Path.of(""), Path.of(""), Path.of("java"));
        os.assertNumberOfTasks(4);
        os.assertTask("mx clean");
    }

    private static void shouldEnableAssertions()
    {
        boolean enabled = false;
        //noinspection AssertWithSideEffects
        assert enabled = true;
        //noinspection ConstantConditions
        if (!enabled)
        {
            throw new AssertionError("assert not enabled");
        }
    }

    // TODO use a marker or equivalent as return of exec and verify that instead of tracking tasks separately
    private static final class RecordingOperatingSystem
    {
        private final Queue<Tasks.Exec> tasks = new ArrayDeque<>();

        void record(Tasks.Exec task)
        {
            final boolean success = tasks.offer(task);
            assert success : task;
        }

        void assertNumberOfTasks(int size)
        {
            assert tasks.size() == size
                : String.format("%d:%s", tasks.size(), tasks);
        }

        private void assertTask(String expected)
        {
            assertTask(t ->
            {
                final String actual = String.join(" ", t.args);
                assert actual.equals(expected) : t;
            });
        }

        private void assertTask(Consumer<Tasks.Exec> asserts)
        {
            final Tasks.Exec head = peekTask();
            asserts.accept(head);
            forward();
        }

        private Tasks.Exec peekTask()
        {
            return tasks.peek();
        }

        private void forward()
        {
            tasks.remove();
        }
    }
}
