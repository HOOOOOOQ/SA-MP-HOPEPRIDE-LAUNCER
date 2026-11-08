import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Compilation gate for files that do not need the Android SDK.
 *
 * The CI sandbox for this repository has no Android SDK, so a syntax or type
 * error in a pure-Java helper would otherwise only be found on the runner. This
 * tool feeds a curated list of pure-Java sources (plus the JUnit tests that cover
 * them) to javac together with the unit-test jar, and fails on any error.
 *
 * Only files that have no android.* dependency belong in TARGETS. Everything else
 * is reported as SKIP so the list stays honest about what it proves.
 */
public final class CompileCheck {

    /** Pure Java sources that this gate can type check. */
    private static final String[] TARGETS = {
            "app/src/main/java/com/sampmobile/xyvern/launcher/data/DataIntegrity.java",
            "app/src/main/java/com/sampmobile/xyvern/launcher/data/ArchivePolicy.java",
            "app/src/main/java/com/sampmobile/xyvern/launcher/data/DataManifest.java",
            "app/src/main/java/com/sampmobile/xyvern/launcher/ui/MotionSpec.java",
            "app/src/main/java/com/sampmobile/xyvern/launcher/ui/DeveloperProfile.java",
            "app/src/main/java/com/sampmobile/xyvern/game/ui/StartupStageModel.java",
            "app/src/main/java/com/sampmobile/xyvern/launcher/update/UpdatePlan.java"
    };

    /** Tests that cover TARGETS; compiled together so a signature change fails here. */
    private static final String[] TESTS = {
            "app/src/test/java/com/hopepride/samp/launcher/data/DataIntegrityTest.java",
            "app/src/test/java/com/hopepride/samp/launcher/data/ArchivePolicyTest.java",
            "app/src/test/java/com/hopepride/samp/launcher/data/DataManifestTest.java",
            "app/src/test/java/com/hopepride/samp/launcher/ui/MotionSpecTest.java",
            "app/src/test/java/com/hopepride/samp/launcher/ui/DeveloperProfileTest.java",
            "app/src/test/java/com/hopepride/samp/game/ui/StartupStageModelTest.java",
            "app/src/test/java/com/hopepride/samp/launcher/update/UpdatePlanTest.java"
    };

    public static void main(String[] args) throws Exception {
        final String repository = args.length > 0 ? args[0] : ".";
        final String junitJar = args.length > 1 ? args[1] : null;

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("FAIL  no system Java compiler (a JRE cannot run this gate)");
            System.exit(2);
        }

        List<File> sources = new ArrayList<>();
        int skipped = 0;
        for (String relative : concat(TARGETS, TESTS)) {
            File file = new File(repository, relative);
            if (!file.isFile()) {
                // A test for a target that does not exist yet must fail loudly,
                // otherwise the gate silently stops covering it.
                System.err.println("FAIL  missing source: " + relative);
                System.exit(1);
            }
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.contains("import android.") || text.contains("import androidx.")) {
                System.out.println("SKIP  " + relative + " (needs the Android SDK)");
                skipped++;
                continue;
            }
            sources.add(file);
        }

        if (sources.isEmpty()) {
            System.out.println("FAIL  nothing to compile");
            System.exit(1);
        }

        Path output = Files.createTempDirectory("hopepride-compilecheck");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {

            manager.setLocation(StandardLocation.CLASS_OUTPUT,
                    java.util.Collections.singletonList(output.toFile()));
            if (junitJar != null && new File(junitJar).isFile()) {
                manager.setLocation(StandardLocation.CLASS_PATH,
                        java.util.Collections.singletonList(new File(junitJar)));
            }

            Iterable<? extends JavaFileObject> units =
                    manager.getJavaFileObjectsFromFiles(sources);

            List<String> options = Arrays.asList("-encoding", "UTF-8", "-Xlint:-options",
                    "-source", "11", "-target", "11");

            StringWriterOut writer = new StringWriterOut();
            boolean ok = compiler.getTask(writer.writer(), manager, diagnostics, options,
                    null, units).call();
            manager.close();

            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() != Diagnostic.Kind.ERROR) continue;
                System.out.println("error: " + diagnostic.getSource() + ":"
                        + diagnostic.getLineNumber() + " " + diagnostic.getMessage(Locale.ROOT));
            }
            String extra = writer.text();
            if (!extra.isEmpty()) System.out.print(extra);

            System.out.println();
            System.out.println("compiled " + sources.size() + " file(s), skipped " + skipped);
            if (ok) {
                System.out.println("COMPILE PASS");
            } else {
                System.out.println("COMPILE FAIL");
                System.exit(1);
            }
        }
    }

    private static List<String> concat(String[] a, String[] b) {
        List<String> all = new ArrayList<>(Arrays.asList(a));
        all.addAll(Arrays.asList(b));
        return all;
    }

    /** Minimal sink so the compiler writes into a string we can print. */
    private static final class StringWriterOut {
        private final java.io.StringWriter buffer = new java.io.StringWriter();
        PrintWriter writer() { return new PrintWriter(buffer, true); }
        String text() { return buffer.toString(); }
    }
}
