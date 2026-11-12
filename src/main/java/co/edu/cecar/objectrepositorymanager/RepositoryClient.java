package co.edu.cecar.objectrepositorymanager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.queries.BinaryForSourceQuery;
import org.netbeans.api.java.queries.UnitTestForSourceQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.SourceGroup;
import org.openide.filesystems.FileUtil;

/** The IDE exchanges text with a child JVM; project classes are never loaded here. */
public class RepositoryClient implements AutoCloseable {
    private final Project project;
    private volatile boolean closed;
    private volatile Process running;

    public RepositoryClient(Project project) {
        this.project = project;
    }

    public Properties execute(Properties input) throws Exception {
        if (closed) {
            throw new IOException("El gestor se ha cerrado.");
        }
        RuntimePaths paths = runtimePaths();
        Properties request = new Properties();
        request.putAll(input);
        request.setProperty("roots", String.join(File.pathSeparator, paths.outputs));
        File projectDirectory = FileUtil.toFile(project.getProjectDirectory());
        if (projectDirectory != null) {
            request.setProperty("dataDirectory", projectDirectory.toPath().resolve("data").toString());
        }
        Path work = Files.createTempDirectory("object-repository-manager-");
        Process process = null;
        try {
            Path requestFile = work.resolve("request.properties");
            Path responseFile = work.resolve("response.properties");
            try (OutputStream out = Files.newOutputStream(requestFile)) {
                request.store(out, "Object Repository Manager");
            }
            // Manifest URLs handle long classpaths and Unicode without the native
            // encoding restrictions of the Java launcher's @argument files.
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ",
                    paths.classpath.stream().map(path -> Path.of(path).toUri().toASCIIString()).toList()));
            Path bootstrap = work.resolve("classpath.jar");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(bootstrap), manifest)) {
                // The manifest contains the worker and project classpath.
            }
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xmx256m", "-Dfile.encoding=UTF-8",
                    "-cp", bootstrap.toString(), "co.edu.cecar.objectrepositorymanager.worker.RepositoryWorker",
                    requestFile.toString(), responseFile.toString());
            File projectDir = FileUtil.toFile(project.getProjectDirectory());
            if (projectDir != null) {
                builder.directory(projectDir);
            }
            // No pipe can block the child; project initialization may write to stdout.
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            synchronized (this) {
                if (closed) {
                    throw new IOException("El gestor se ha cerrado.");
                }
                process = builder.start();
                running = process;
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("La operación superó 60 segundos. Vuelve a abrir el archivo para comprobar su estado.");
            }
            if (!Files.isRegularFile(responseFile)) {
                throw new IOException("El proceso Java terminó sin respuesta (código " + process.exitValue()
                        + "). Comprueba el JDK y las dependencias del proyecto.");
            }
            if (Files.size(responseFile) > 32L * 1024 * 1024) {
                throw new IOException("La respuesta supera el límite de 32 MB del visor.");
            }
            Properties response = new Properties();
            try (InputStream in = Files.newInputStream(responseFile)) {
                response.load(in);
            }
            if (!Boolean.parseBoolean(response.getProperty("ok"))) {
                throw new IOException(response.getProperty("error", "No se pudo completar la operación."));
            }
            return response;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Operación interrumpida. Vuelve a abrir el archivo para comprobar su estado.", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            running = null;
            // This directory is created exclusively for this request.
            try (var entries = Files.walk(work)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            } catch (IOException ex) {
                work.toFile().deleteOnExit();
            }
        }
    }

    private RuntimePaths runtimePaths() throws Exception {
        Set<String> outputs = new LinkedHashSet<>();
        Set<String> dependencies = new LinkedHashSet<>();
        for (SourceGroup group : ManageBinaryFilesAction.javaSources(project)) {
            if (UnitTestForSourceQuery.findSources(group.getRootFolder()).length > 0) {
                continue;
            }
            URL source = group.getRootFolder().toURL();
            for (URL binary : BinaryForSourceQuery.findBinaryRoots(source).getRoots()) {
                addLocal(outputs, binary);
            }
            for (String kind : List.of(ClassPath.EXECUTE, ClassPath.COMPILE)) {
                ClassPath classpath = ClassPath.getClassPath(group.getRootFolder(), kind);
                if (classpath != null) {
                    for (ClassPath.Entry entry : classpath.entries()) {
                        addLocal(dependencies, entry.getURL());
                    }
                }
            }
        }
        File directory = FileUtil.toFile(project.getProjectDirectory());
        if (outputs.isEmpty() && directory != null) {
            for (String conventional : List.of("target/classes", "build/classes", "build/classes/java/main")) {
                Path path = directory.toPath().resolve(conventional);
                if (Files.isDirectory(path)) {
                    outputs.add(path.toAbsolutePath().toString());
                }
            }
        }
        if (outputs.isEmpty()) {
            throw new IOException("No hay clases compiladas. Ejecuta Clean and Build en el proyecto y pulsa Actualizar clases.");
        }
        Set<String> classpath = new LinkedHashSet<>();
        URL module = RepositoryClient.class.getProtectionDomain().getCodeSource().getLocation();
        File moduleFile = FileUtil.archiveOrDirForURL(module);
        if (moduleFile == null || !moduleFile.exists()) {
            throw new IOException("No se pudo localizar el módulo instalado de Object Repository Manager: " + module);
        }
        classpath.add(moduleFile.getAbsolutePath());
        classpath.addAll(outputs);
        classpath.addAll(dependencies);
        return new RuntimePaths(outputs, classpath);
    }

    private static void addLocal(Set<String> paths, URL url) {
        File file = FileUtil.archiveOrDirForURL(url);
        if (file != null && file.exists()) {
            paths.add(file.getAbsolutePath());
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (running != null) {
            running.destroyForcibly();
        }
    }

    private record RuntimePaths(Set<String> outputs, Set<String> classpath) { }
}
