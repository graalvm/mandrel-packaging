import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
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
import java.util.Scanner;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class build
{
    static final Logger logger = LogManager.getLogger(build.class);
    public static final boolean IS_WINDOWS = System.getProperty("os.name").matches(".*[Ww]indows.*");
    public static final boolean IS_MAC = System.getProperty("os.name").matches(".*[Mm]ac.*");
    public static final String JDK_VERSION = "jdk" + Runtime.version().feature();
    private static final String MANDREL_RELEASE_FILE = "mandrel.release";

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
        final Path mandrelJavaHome = IS_MAC ? mandrelHome.resolve(Path.of("Contents", "Home")) : mandrelHome;
        FileSystem.deleteRecursively(mandrelHome);
        if (IS_MAC)
        {
            fs.copyDirectory(os.javaHome().getParent().getParent(), mandrelHome);
        } else
        {
            fs.copyDirectory(os.javaHome(), mandrelHome);
        }

        String OS = System.getProperty("os.name").toLowerCase().split(" ")[0]; // We want "windows" not e.g. "windows 10"
        OS = OS.replace("mac", "darwin");
        String ARCH = System.getProperty("os.arch");
        ARCH = ARCH.replace("x86_64", "amd64");
        final String PLATFORM = OS + "-" + ARCH;
        final Path nativeImage = mandrelJavaHome.resolve(Path.of("lib", "svm", "bin", IS_WINDOWS ? "native-image.cmd" : "native-image"));

        if (!options.skipJava)
        {
            logger.debugf("Copy jars...");
            mx.artifacts.forEach((artifact, paths) ->
            {
                final String source = PathFinder.getFirstExisting(mandrelRepo.resolve(paths[0]).toString(), artifact).toString();
                final String destination = mandrelJavaHome.resolve(paths[1]).toString();
                FileSystem.copy(Path.of(source), Path.of(destination));
                FileSystem.copy(Path.of(source.replace(".jar", ".src.zip")), Path.of(destination.replace(".jar", ".src.zip")));
                logger.debugf("Copying .jar and .src.zip for src: %s, dst: %s", source, destination);
            });

            logger.debugf("Copy macros...");
            mx.macroPaths.forEach((macro, path) ->
            {
                final Path source = PathFinder.getFirstExisting(mandrelRepo.resolve(path).toString(), macro);
                final Path destination = mandrelJavaHome.resolve(Path.of("lib", "svm", "macros", macro));
                fs.copyDirectory(source, destination);
                logger.debugf("Copying macro src: %s, dst: %s", source, destination);
            });

            logger.debugf("Copy native-image...");
            FileSystem.copy(mandrelRepo.resolve(
                Path.of("sdk", "mxbuild", PLATFORM, IS_WINDOWS ? "native-image.exe.image-bash" : "native-image.image-bash",
                    IS_WINDOWS ? "native-image.cmd" : "native-image")), nativeImage);
        }

        if (!options.skipNative)
        {
            // Although docs are not native per se we chose to install them during the native part of the build
            logger.debugf("Copy docs...");
            FileSystem.copy(mandrelRepo.resolve("LICENSE"), mandrelJavaHome.resolve("LICENSE"));
            FileSystem.copy(mandrelRepo.resolve("THIRD_PARTY_LICENSE.txt"), mandrelJavaHome.resolve("THIRD_PARTY_LICENSE.txt"));
            if (mandrelRepo.resolve("README-Mandrel.md").toFile().exists())
            {
                FileSystem.copy(mandrelRepo.resolve("README-Mandrel.md"), mandrelJavaHome.resolve("README.md"));
            }
            else
            {
                FileSystem.copy(mandrelRepo.resolve("README.md"), mandrelJavaHome.resolve("README.md"));
            }
            FileSystem.copy(mandrelRepo.resolve("SECURITY.md"), mandrelJavaHome.resolve("SECURITY.md"));

            logger.debugf("Native bits...");
            FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.native.libchelper", "include", "amd64cpufeatures.h")),
                mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "amd64cpufeatures.h")));
            FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.native.libchelper", "include", "aarch64cpufeatures.h")),
                mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "aarch64cpufeatures.h")));
            Path riscv64headers = mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.native.libchelper", "include", "riscv64cpufeatures.h"));
            if (riscv64headers.toFile().exists())
            {
                FileSystem.copy(riscv64headers, mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "riscv64cpufeatures.h")));
            }
            FileSystem.copy(mandrelRepo.resolve(Path.of("substratevm", "src", "com.oracle.svm.libffi", "include", "svm_libffi.h")),
                mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "svm_libffi.h")));
            FileSystem.copy(mandrelRepo.resolve(Path.of("truffle", "src", "com.oracle.truffle.nfi.native", "include", "trufflenfi.h")),
                mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "include", "trufflenfi.h")));
            String platformAndJDK = PLATFORM + "-" + JDK_VERSION;
            if (IS_WINDOWS)
            {
                Path libchelperSource;
                Path jvmlibSource;
                Path reporterchelperSource;
                if (MxVersion.mx5_313_0.compareTo(mx.version) > 0)
                {
                    libchelperSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.libchelper", ARCH, "libchelper.lib");
                    jvmlibSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.jvm.windows", ARCH, "jvm.lib");
                    reporterchelperSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.reporterchelper", ARCH, "reporterchelper.dll");
                }
                else
                {
                    libchelperSource = Path.of("substratevm", "mxbuild", PLATFORM, "com.oracle.svm.native.libchelper", ARCH, "libchelper.lib");
                    jvmlibSource = Path.of("substratevm", "mxbuild", platformAndJDK, "com.oracle.svm.native.jvm.windows", ARCH, "jvm.lib");
                    reporterchelperSource = Path.of("substratevm", "mxbuild", platformAndJDK, "com.oracle.svm.native.reporterchelper", ARCH, "reporterchelper.dll");
                }
                FileSystem.copy(mandrelRepo.resolve(libchelperSource),
                    mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "libchelper.lib")));
                FileSystem.copy(mandrelRepo.resolve(jvmlibSource),
                    mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "jvm.lib")));
                FileSystem.copy(mandrelRepo.resolve(reporterchelperSource),
                    mandrelJavaHome.resolve(Path.of("lib", "svm", "builder", "lib", "reporterchelper.dll")));
            }
            else
            {
                Path libchelperSource;
                Path libjvmSource;
                Path libdarwinSource = null;
                Path reporterchelperSource;
                if (MxVersion.mx5_313_0.compareTo(mx.version) > 0)
                {
                    libchelperSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.libchelper", ARCH, "liblibchelper.a");
                    libjvmSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.jvm.posix", ARCH, "libjvm.a");
                    if (IS_MAC)
                    {
                        libdarwinSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.darwin", ARCH, "libdarwin.a");
                    }
                    reporterchelperSource = Path.of("substratevm", "mxbuild", PLATFORM, "src", "com.oracle.svm.native.reporterchelper", ARCH, System.mapLibraryName("reporterchelper"));
                }
                else
                {
                    libchelperSource = Path.of("substratevm", "mxbuild", PLATFORM, "com.oracle.svm.native.libchelper", ARCH, "liblibchelper.a");
                    libjvmSource = Path.of("substratevm", "mxbuild", platformAndJDK, "com.oracle.svm.native.jvm.posix", ARCH, "libjvm.a");
                    if (IS_MAC)
                    {
                        libdarwinSource = Path.of("substratevm", "mxbuild", PLATFORM, "com.oracle.svm.native.darwin", ARCH, "libdarwin.a");
                    }
                    reporterchelperSource = Path.of("substratevm", "mxbuild", platformAndJDK, "com.oracle.svm.native.reporterchelper", ARCH, System.mapLibraryName("reporterchelper"));
                }
                FileSystem.copy(mandrelRepo.resolve(libchelperSource),
                    mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "liblibchelper.a")));
                FileSystem.copy(mandrelRepo.resolve(libjvmSource),
                    mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "libjvm.a")));
                if (IS_MAC)
                {
                    FileSystem.copy(mandrelRepo.resolve(libdarwinSource),
                        mandrelJavaHome.resolve(Path.of("lib", "svm", "clibraries", PLATFORM, "libdarwin.a")));
                }
                FileSystem.copy(mandrelRepo.resolve(reporterchelperSource),
                    mandrelJavaHome.resolve(Path.of("lib", "svm", "builder", "lib", System.mapLibraryName("reporterchelper"))));
            }
            // We don't create symlink on Windows, See https://github.com/graalvm/mandrel-packaging/pull/71#discussion_r517268470
            if (IS_WINDOWS)
            {
                // exe_link_template.cmd: DOS batch file, ASCII text, with CRLF line terminators
                final String nativeImageCmd = Files.readString(mandrelRepo.resolve(
                        Path.of("sdk", "mx.sdk", "vm", "exe_link_template.cmd")), StandardCharsets.US_ASCII)
                    .replace("<target>", "..\\lib\\svm\\bin\\native-image.cmd");
                Files.writeString(mandrelJavaHome.resolve(
                    Path.of("bin", "native-image.cmd")), nativeImageCmd, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            }
            else
            {
                logger.debugf("Symlink native-image...");
                Files.createSymbolicLink(mandrelJavaHome.resolve(
                    Path.of("bin", "native-image")), Path.of("..", "lib", "svm", "bin", "native-image"));
            }

            logger.debugf("Patch native image...");
            patchNativeImageLauncher(nativeImage, options.mandrelVersion, options.vendor, options.vendorUrl);

            if (!options.skipNativeAgents)
            {
                logger.debugf("Build native agents...");
                buildAgents(nativeImage, fs, os);
            }

            generateMandrelReleaseFile(mandrelVersionUntilSpace, mandrelHome);
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

    private static void generateMandrelReleaseFile(String mandrelVersion, Path mandrelHome)
    {
        final String content = String.format("GRAALVM_VERSION=%s%nMANDREL_VERSION=%s%n", mandrelVersion, mandrelVersion);
        final Path mandrelReleaseFile = mandrelHome.resolve(Path.of(MANDREL_RELEASE_FILE));
        try
        {
            Files.writeString(mandrelReleaseFile, content, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to write " + MANDREL_RELEASE_FILE + " file", e);
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


    private static void patchNativeImageLauncher(Path nativeImage, String mandrelVersion, String vendor, String vendorUrl) throws IOException
    {
        final List<String> lines = Files.readAllLines(nativeImage);
        // This is jamming two sets of parameters in between three sections of command line.
        // It is fragile at best. We should probably just generate the line fresh.
        final Pattern launcherPattern = Pattern.compile("(.*EnableJVMCI)(.*)");
        logger.debugf("mandrelVersion: %s", mandrelVersion);
        String defaultVendor = "GraalVM Community";
        String defaultVendorUrl = "https://github.com/graalvm/mandrel/issues";
        for (int i = 0; i < lines.size(); i++)
        {
            final Matcher launcherMatcher = launcherPattern.matcher(lines.get(i));
            if (launcherMatcher.find())
            {
                logger.debugf("Launcher line BEFORE: %s", lines.get(i));
                logger.debugf("launcherMatcher.group(1): %s", launcherMatcher.group(1));
                logger.debugf("launcherMatcher.group(2): %s", launcherMatcher.group(2));
                final String launcherLine = launcherMatcher.group(1) +
                    " -Dorg.graalvm.version=\"" + mandrelVersion + "\"" +
                    " -Dorg.graalvm.vendorversion=\"Mandrel-" + mandrelVersion + "\"" +
                    " -Dorg.graalvm.vendor=\"" + (vendor != null ? vendor : defaultVendor) + "\"" +
                    " -Dorg.graalvm.vendorurl=\"" + (vendorUrl != null ? vendorUrl : defaultVendorUrl) + "\"" +
                    launcherMatcher.group(2);
                lines.set(i, launcherLine);
                logger.debugf("Launcher line AFTER: %s", lines.get(i));
                break;
            }
        }
        Files.write(nativeImage, lines, UTF_8);
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
    final boolean mavenDeploy;
    final String mavenVersion;
    String mandrelVersion;
    final boolean verbose;
    final String mavenProxy;
    final String mavenRepoId;
    final String mavenURL;
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
    final String vendor;
    final String vendorUrl;

    Options(
        boolean mavenDeploy
        , String mavenVersion
        , String mandrelVersion
        , boolean verbose
        , String mavenProxy
        , String mavenRepoId
        , String mavenURL
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
        , String vendor
        , String vendorUrl
    )
    {
        this.mavenDeploy = mavenDeploy;
        this.mavenVersion = mavenVersion;
        this.mandrelVersion = mandrelVersion;
        this.verbose = verbose;
        this.mavenProxy = mavenProxy;
        this.mavenRepoId = mavenRepoId;
        this.mavenURL = mavenURL;
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
        this.vendor = vendor;
        this.vendorUrl = vendorUrl;
    }

    public static Options from(Map<String, List<String>> args)
    {
        // Maven related
        boolean mavenDeploy = args.containsKey("maven-deploy");
        final String mavenVersion = required("maven-version", args, mavenDeploy);
        final String mavenProxy = optional("maven-proxy", args);
        final String mavenRepoId = required("maven-repo-id", args, mavenDeploy);
        final String mavenURL = required("maven-url", args, mavenDeploy);
        final String mavenHome = optional("maven-home", args);

        // Mandrel related
        final String mandrelVersion = optional("mandrel-version", args);
        final String mandrelHome = optional("mandrel-home", args);
        final String mandrelRepo = optional("mandrel-repo", args);
        final String vendor = optional("vendor", args);
        final String vendorUrl = optional("vendor-url", args);

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
            mavenDeploy
            , mavenVersion
            , mandrelVersion
            , verbose
            , mavenProxy
            , mavenRepoId
            , mavenURL
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
            , vendor
            , vendorUrl
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
}

class SequentialBuild
{
    static final Logger LOG = LogManager.getLogger(SequentialBuild.class);
    final FileSystem fs;
    final OperatingSystem os;
    final Mx mx;

    SequentialBuild(FileSystem fs, OperatingSystem os, Mx mx)
    {
        this.fs = fs;
        this.os = os;
        this.mx = mx;
    }

    void build(Options options) throws IOException
    {
        final Tasks.Exec.Effects exec = new Tasks.Exec.Effects(task -> os.exec(task, false));
        final Tasks.FileReplace.Effects replace = Tasks.FileReplace.Effects.ofSystem();
        Mx.build(options, exec, replace, fs.mxHome(), fs.mandrelRepo(), os.javaHome());
        if (options.mavenDeploy && !options.skipJava)
        {
            // Create wrapper jar file for archiving resources (e.g. native image launcher script)
            LOG.debugf("Patch sdk suite.py ...");
            String patchPath = fs.workingDir().resolve(Path.of("resources", "mandrel-packaging-wrapper.patch")).toString();
            exec.exec.accept(Tasks.Exec.of(List.of("git", "apply", patchPath), fs.mandrelRepo()));
            try
            {
                LOG.debugf("Build Mandrel's wrapper jar...");
                Mx.BuildArgs buildArgs = Mx.BuildArgs.of("--only", "MANDREL_PACKAGING_WRAPPER");
                exec.exec.accept(Mx.mxbuild(options, fs.mxHome(), fs.mandrelRepo(), os.javaHome()).apply(buildArgs));
                // Add Specification-Version and Implementation-Version to jars' manifests.
                // These attributes are access by Red Hat Build of Quarkus to verify that the correct artifacts are being used.
                // The value of Specification-Version is not that important, but the Implementation-Version should match the version of the native-image.
                LOG.debugf("Patch jars' manifests with Specification-Version and Implementation-Version...");
                mx.artifacts.forEach(amendOrCreateManifest(options, replace));
                LOG.debugf("Deploy maven artifacts...");
                Mx.mavenDeploy(options, exec, fs.mxHome(), fs.mandrelRepo(), os.javaHome());
            }
            finally
            {
                exec.exec.accept(Tasks.Exec.of(List.of("git", "apply", "-R", patchPath), fs.mandrelRepo()));
            }
        }
    }

    private BiConsumer<String, Path[]> amendOrCreateManifest(Options options, Tasks.FileReplace.Effects replace)
    {
        return (artifact, paths) ->
        {
            final Path jarPath = PathFinder.getFirstExisting(fs.mandrelRepo().resolve(paths[0]).toString(), artifact);
            try (java.nio.file.FileSystem jarfs = FileSystems.newFileSystem(jarPath, this.getClass().getClassLoader()))
            {
                Path metaInfPath = jarfs.getPath("/META-INF");
                Path manifestPath = metaInfPath.resolve("MANIFEST.MF");
                if (!Files.exists(manifestPath))
                {
                    if (!Files.exists(metaInfPath))
                    {
                        Files.createDirectory(metaInfPath);
                    }
                    Files.createFile(manifestPath);
                }
                Tasks.FileReplace.replace(new Tasks.FileReplace(manifestPath, amendManifest(options)), replace);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        };
    }

    /*
     * Add Specification-Version and Implementation-Version to jars' manifests.
     * These attributes are accessed by Red Hat Build of Quarkus to verify that the correct artifacts are being used.
     * The value of Specification-Version is not that important, but the Implementation-Version should match the version of the native-image.
     */
    private static Function<Stream<String>, List<String>> amendManifest(Options options)
    {
        return lines ->
        {
            List<String> result = lines.collect(Collectors.toList());
            result.add("Specification-Version: 0.0");
            result.add("Implementation-Version: " + options.mavenVersion);
            return result;
        };
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

    static final List<BuildArgs> BUILD_NATIVE_STEPS;

    static
    {
        String projects;
        if (build.IS_WINDOWS)
        {
            projects = "com.oracle.svm.native.libchelper," +
                "com.oracle.svm.native.reporterchelper," +
                "com.oracle.svm.native.jvm.windows," +
                "com.oracle.svm.core.windows";
        }
        else
        {
            projects = "com.oracle.svm.native.libchelper," +
                "com.oracle.svm.native.reporterchelper," +
                "com.oracle.svm.native.jvm.posix";
            if (build.IS_MAC)
            {
                projects += ",com.oracle.svm.native.darwin";
            }
        }
        BUILD_NATIVE_STEPS = List.of(BuildArgs.of("--projects", projects));
    }

    Mx(FileSystem fs, OperatingSystem os)
    {
        version = new MxVersion(os.exec(Mx.mxversion(fs.mxHome()), true).get(0));

        if (MxVersion.mx5_313_0.compareTo(version) > 0)
        {
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
            new SimpleEntry<>("org.graalvm.truffle:truffle-compiler.jar",
                new Path[]{truffleDistPath.resolve("truffle-compiler.jar"), Path.of("lib", "truffle", "truffle-compiler.jar")}),
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

    static final List<BuildArgs> DEPLOY_ARTIFACTS_STEPS = List.of(
        BuildArgs.of("--only",
            "GRAAL_SDK," +
                "SVM," +
                "NATIVE_IMAGE_BASE," +
                "POINTSTO," +
                "LIBRARY_SUPPORT," +
                "TRUFFLE_COMPILER," +
                "GRAAL," +
                "OBJECTFILE," +
                "SVM_DRIVER," +
                "JVMTI_AGENT_BASE," +
                "SVM_AGENT," +
                "SVM_DIAGNOSTICS_AGENT," +
                "SVM_CONFIGURE," +
                "MANDREL_PACKAGING_WRAPPER")
    );

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
        patchSuites(replace, mandrelRepo);
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

    static void mavenDeploy(
        Options options
        , Tasks.Exec.Effects exec
        , Path mxHome
        , Path mandrelRepo
        , Path javaHome
    )
    {
        DEPLOY_ARTIFACTS_STEPS.stream()
            .map(Mx.mxMavenDeploy(options, mxHome, mandrelRepo, javaHome))
            .forEach(exec.exec);
    }

    private static Function<BuildArgs, Tasks.Exec> mxMavenDeploy(
        Options options
        , Path mxHome
        , Path mandrelRepo
        , Path javaHome
    )
    {
        return buildArgs ->
        {
            final Path mx = mxHome.resolve(Paths.get("mx"));
            final List<String> args = Lists.concat(
                List.of(
                    mx.toString()
                    , options.verbose ? "-V" : ""
                    , "--trust-http"
                    , "--java-home"
                    , javaHome.toString()
                    , "maven-deploy"
                    , "--all-suites"
                    , "--all-distribution-types"
                    , "--validate"
                    , "compat"
                    , "--licenses"
                    , "GPLv2-CPE,UPL"
                    , "--suppress-javadoc"
                    , options.mavenRepoId
                    , options.mavenURL
                )
                , buildArgs.args
            );

            return execTask(
                args
                , mandrelRepo.resolve(Path.of("substratevm"))
            );
        };
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

    static Function<BuildArgs, Tasks.Exec> mxbuild(
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
                    , "--components=ni"
                    , "--exclude-components=nju,svmnfi,svml,tflm"
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
        mxEnvVars[0] = new EnvVar("MX_PYTHON", Python.get());
        System.arraycopy(envVars, 0, mxEnvVars, 1, envVars.length);
        return Tasks.Exec.of(args, directory, mxEnvVars);
    }

    static void patchSuites(Tasks.FileReplace.Effects effects, Path mandrelRepo)
    {
        LOG.debugf("Patch mx dependencies");
        Path suitePy = Path.of("substratevm", "mx.substratevm", "suite.py");
        Path path = mandrelRepo.resolve(suitePy);
        Map<String, String> dependenciesToPatch = Map.ofEntries(
            // Mandrel doesn't use truffle
            new SimpleEntry<>("^ +\"com.oracle.svm.truffle\",", ""),
            new SimpleEntry<>("^ +\"truffle:TRUFFLE_API\",", ""),
            new SimpleEntry<>("^ +\"truffle:TRUFFLE_RUNTIME\",", ""),
            new SimpleEntry<>("^ +\"com.oracle.svm.truffle.api                   to org.graalvm.truffle\",", ""),
            new SimpleEntry<>("^ +\"com.oracle.truffle.api.TruffleLanguage.Provider\",", ""),
            new SimpleEntry<>("^ +\"com.oracle.truffle.api.instrumentation.TruffleInstrument.Provider\",", ""),
            new SimpleEntry<>("^ +\"com.oracle.truffle.api.provider.TruffleLanguageProvider\",", ""),
            new SimpleEntry<>("^ +\"com.oracle.truffle.api.instrumentation.provider.TruffleInstrumentProvider\",", ""),
            new SimpleEntry<>("^ +\"com.oracle.svm.truffle.nfi\",", ""),
            new SimpleEntry<>("^ +\"com.oracle.svm.truffle.nfi.posix\",", ""),
            new SimpleEntry<>("^ +\"com.oracle.svm.truffle.nfi.windows\",", ""),
            new SimpleEntry<>("^ +\"extracted-dependency:truffle:LIBFFI_DIST\"", ""),
            new SimpleEntry<>("^ +\"file:src/com.oracle.svm.libffi/include/svm_libffi.h\",", ""),
            new SimpleEntry<>("^ +\"extracted-dependency:truffle:TRUFFLE_NFI_GRAALVM_SUPPORT/include/trufflenfi.h\",", ""),
            // Mandrel doesn't use polyglot
            new SimpleEntry<>("^ +\"com.oracle.svm.polyglot\",", ""));
        Tasks.FileReplace.replace(
            new Tasks.FileReplace(path, patchSuites(dependenciesToPatch))
            , effects
        );

        Path svmPy = Path.of("substratevm", "mx.substratevm", "mx_substratevm.py");
        path = mandrelRepo.resolve(svmPy);
        dependenciesToPatch = Map.of("^llvm_supported =.*", "llvm_supported = False");
        Tasks.FileReplace.replace(
            new Tasks.FileReplace(path, patchSuites(dependenciesToPatch))
            , effects
        );

        suitePy = Path.of("compiler", "mx.compiler", "suite.py");
        path = mandrelRepo.resolve(suitePy);
        dependenciesToPatch = Map.ofEntries(
            // Mandrel doesn't use llvm
            new SimpleEntry<>(",org.graalvm.nativeimage.llvm", ""));
        Tasks.FileReplace.replace(
            new Tasks.FileReplace(path, patchSuites(dependenciesToPatch))
            , effects
        );

        suitePy = Path.of("sdk", "mx.sdk", "suite.py");
        path = mandrelRepo.resolve(suitePy);
        dependenciesToPatch = Map.of(
            // Mandrel doesn't use polyglot
            "^ +\"org.graalvm.polyglot\",", "",
            "^ +\"org.graalvm.polyglot.proxy\",", "",
            "^ +\"org.graalvm.polyglot.io\",", "",
            "^ +\"org.graalvm.polyglot.management\",", "",
            "^ +\"org.graalvm.polyglot.impl to org.graalvm.truffle, com.oracle.truffle.enterprise\",", "",
            "^ +\"org.graalvm.polyglot.impl.AbstractPolyglotImpl\"", "",
            "^ +\"org.graalvm.polyglot to org.graalvm.truffle\"", "");
        Tasks.FileReplace.replace(
            new Tasks.FileReplace(path, patchSuites(dependenciesToPatch))
            , effects
        );

        suitePy = Path.of("sdk", "mx.sdk", "mx_sdk_vm_impl.py");
        path = mandrelRepo.resolve(suitePy);
        dependenciesToPatch = Map.of(" org.graalvm.truffle,", " ");
        Tasks.FileReplace.replace(
            new Tasks.FileReplace(path, patchSuites(dependenciesToPatch))
            , effects
        );
    }

    private static Function<Stream<String>, List<String>> patchSuites(Map<String, String> patches)
    {
        return lines ->
            lines.map(l -> dependencyPatcher(l, patches)).collect(Collectors.toList());
    }

    private static String dependencyPatcher(String line, Map<String, String> patches)
    {
        for (Map.Entry<String, String> entry : patches.entrySet())
        {
            Pattern from = Pattern.compile(entry.getKey());
            Matcher matcher = from.matcher(line);
            if (matcher.find())
            {
                String to = entry.getValue();
                LOG.debugf("Replacing:\n\t" + from + "\n\twith\n\t" + to + "\n\tin\n\t" + line);
                return matcher.replaceFirst(to);
            }
        }
        LOG.debugf("KEEPING : %s", line);
        return line;
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

    MxVersion(String version)
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
                return Path.of(file);
            }
        }
        throw new IllegalArgumentException("There is no existing dir for record " + artifact);
    }
}

// Dependency
class FileSystem
{
    static final Logger LOG = LogManager.getLogger(FileSystem.class);

    private final Path mandrelRepo;
    private final Path mxHome;
    private final Path workingDir;
    private final Path mavenHome;

    FileSystem(Path mandrelRepo, Path mxHome, Path workingDir, Path mavenHome)
    {
        this.mandrelRepo = mandrelRepo;
        this.mxHome = mxHome;
        this.workingDir = workingDir;
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
        final Path mavenHome = mavenHome(options);
        return new FileSystem(mandrelRepo, mxHome, workingDir, mavenHome);
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
        File outputFile = null;
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

            if (getOutput)
            {
                outputFile = File.createTempFile("mandrel-builder-exec-output", "txt");
                outputFile.deleteOnExit();
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(outputFile);
            }
            final Process process = processBuilder.start();
            if (process.waitFor() != 0)
            {
                if (outputFile != null && outputFile.exists() && Logger.debug)
                {
                    try (Scanner sc = new Scanner(outputFile.toPath(), UTF_8))
                    {
                        while (sc.hasNextLine())
                        {
                            LOG.debugf(sc.nextLine());
                        }
                    }
                    catch (IOException ex)
                    {
                        throw new RuntimeException(ex);
                    }
                }
                throw new RuntimeException(
                    "Failed, exit code: " + process.exitValue()
                );
            }
            if (getOutput)
            {
                return Files.readAllLines(outputFile.toPath());
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
    public static void main(String... args) throws IOException
    {
        shouldEnableAssertions();
        checkMx();
    }

    static void checkMx() throws IOException
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

final class Python
{

    static String get()
    {
        // Use MX_PYTHON if set, otherwise use 'python'
        String mxPython = System.getenv("MX_PYTHON");
        return mxPython == null ? "python" : mxPython;
    }

}
