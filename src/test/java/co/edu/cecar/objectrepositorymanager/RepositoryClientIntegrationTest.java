package co.edu.cecar.objectrepositorymanager;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;
import javax.swing.Icon;
import javax.swing.event.ChangeListener;
import javax.tools.ToolProvider;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.LocalFileSystem;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;

/** Real subprocess smoke test; run main with the module's NetBeans dependencies. */
public final class RepositoryClientIntegrationTest {
    private static final String ENTITY = "fixtures.Customer";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        // This standalone JVM has no running NetBeans platform. Suppress ambient
        // module services; the test supplies Project/Sources explicitly and
        // intentionally verifies the conventional output-directory fallback.
        System.setProperty("org.openide.util.Lookup", EmptyLookup.class.getName());
        Path projectFolder = Files.createTempDirectory("orm cliente espacios á漢-");
        try {
            Path source = Files.createDirectories(projectFolder.resolve("src/main/java/fixtures"))
                    .resolve("Customer.java");
            Files.writeString(source, """
                    package fixtures;
                    public class Customer implements java.io.Serializable {
                        private static final long serialVersionUID = 1L;
                        static { System.setProperty("orm.test.child.loaded", "yes"); }
                        private int id;
                        private String name;
                        private boolean active;
                        public Customer() { }
                    }
                    """, StandardCharsets.UTF_8);
            Path compiled = Files.createDirectories(projectFolder.resolve("target/classes"));
            int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--release", "17", "-encoding", "UTF-8", "-proc:none", "-classpath", compiled.toString(),
                    "-d", compiled.toString(), source.toString());
            check(result == 0, "Fixture compilation");
            Project project = fakeProject(projectFolder);
            Path data = projectFolder.resolve("clientes con ñ.bin");
            RepositoryClient client = new RepositoryClient(project);
            try (client) {
                Properties discovered = client.execute(request("discover", null));
                check("1".equals(discovered.getProperty("class.count")), "Find project entity: " + discovered);
                check(ENTITY.equals(discovered.getProperty("class.0")), "Class name round trip");

                Properties schema = client.execute(request("describe", null));
                check("true".equals(schema.getProperty("creatable")), "No-argument entity is creatable");
                int id = field(schema, "id");
                int name = field(schema, "name");
                int active = field(schema, "active");

                Properties creation = request("create", data);
                creation.setProperty("revision", "MISSING");
                Properties empty = client.execute(creation);
                check("0".equals(empty.getProperty("row.count")), "Create an empty repository");
                byte[] initialBytes = Files.readAllBytes(data);
                expectFailure(() -> client.execute(creation), "Creating over an existing file fails");
                check(Arrays.equals(initialBytes, Files.readAllBytes(data)), "Existing file is untouched");

                Properties insert = mutation("insert", data, empty, id);
                insert.setProperty("value." + id, "42");
                insert.setProperty("value." + name, "José\nLínea 2 = café \\\"sí\\\"");
                insert.setProperty("value." + active, "true");
                Properties inserted = client.execute(insert);
                check("1".equals(inserted.getProperty("row.count")), "Insert record");
                check(insert.getProperty("value." + name).equals(inserted.getProperty("cell.0." + name)),
                        "Unicode/newline/backslash properties round trip");
                check(Files.isRegularFile(data.resolveSibling(data.getFileName() + ".bak")), "Backup created");
                check(System.getProperty("orm.test.child.loaded") == null, "Entity never initialized in IDE JVM");

                Properties reopened = client.execute(request("open", data));
                check("42".equals(reopened.getProperty("cell.0." + id)), "Read persisted value in a new child JVM");
                Properties update = mutation("update", data, reopened, id);
                update.setProperty("row", "0");
                update.setProperty("value." + name, "Ana");
                update.setProperty("value." + active, "false");
                Properties updated = client.execute(update);
                check("Ana".equals(updated.getProperty("cell.0." + name)), "Update record");
                check("false".equals(updated.getProperty("cell.0." + active)), "Update boolean");

                Properties staleDelete = mutation("delete", data, reopened, id);
                staleDelete.setProperty("row", "0");
                byte[] updatedBytes = Files.readAllBytes(data);
                expectFailure(() -> client.execute(staleDelete), "Stale revision rejected across child JVMs");
                check(Arrays.equals(updatedBytes, Files.readAllBytes(data)), "Stale operation preserves bytes");

                Properties deletion = mutation("delete", data, updated, id);
                deletion.setProperty("row", "0");
                Properties deleted = client.execute(deletion);
                check("0".equals(deleted.getProperty("row.count")), "Delete record");
                check(Files.exists(data), "Deleting a record retains the repository file");

                Path corrupt = projectFolder.resolve("corrupt.bin");
                Files.writeString(corrupt, "not serialized");
                expectFailure(() -> client.execute(request("open", corrupt)), "Worker error reaches client");
            }
            expectFailure(() -> client.execute(request("discover", null)), "Closed client rejects requests");
            System.out.println("RepositoryClientIntegrationTest: " + assertions + " assertions passed.");
        } finally {
            // Only this test-created temporary directory is removed.
            try (var files = Files.walk(projectFolder)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static Properties request(String operation, Path file) {
        Properties request = new Properties();
        request.setProperty("op", operation);
        request.setProperty("type", ENTITY);
        if (file != null) request.setProperty("file", file.toString());
        return request;
    }

    private static Properties mutation(String op, Path file, Properties snapshot, int id) {
        Properties request = request(op, file);
        request.setProperty("revision", snapshot.getProperty("revision"));
        request.setProperty("idField", snapshot.getProperty("field." + id + ".key"));
        return request;
    }

    private static int field(Properties schema, String name) {
        for (int i = 0; i < Integer.parseInt(schema.getProperty("field.count")); i++) {
            if (name.equals(schema.getProperty("field." + i + ".name"))) return i;
        }
        throw new AssertionError("Missing field: " + name);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(CheckedAction action, String message) throws Exception {
        try { action.run(); }
        catch (IOException expected) { assertions++; return; }
        throw new AssertionError(message);
    }

    private interface CheckedAction { void run() throws Exception; }

    /** Instantiated by Lookup.getDefault() in this test process only. */
    public static final class EmptyLookup extends ProxyLookup {
        public EmptyLookup() { super(); }
    }

    private static Project fakeProject(Path folder) throws Exception {
        LocalFileSystem filesystem = new LocalFileSystem();
        filesystem.setRootDirectory(folder.toFile());
        FileObject directory = filesystem.getRoot();
        FileObject root = directory.getFileObject("src/main/java");
        SourceGroup group = new SourceGroup() {
            @Override public FileObject getRootFolder() { return root; }
            @Override public String getName() { return "main"; }
            @Override public String getDisplayName() { return "Main sources"; }
            @Override public Icon getIcon(boolean opened) { return null; }
            @Override public boolean contains(FileObject file) { return root.equals(file) || FileUtil.isParentOf(root, file); }
            @Override public void addPropertyChangeListener(PropertyChangeListener listener) { }
            @Override public void removePropertyChangeListener(PropertyChangeListener listener) { }
        };
        Sources sources = new Sources() {
            @Override public SourceGroup[] getSourceGroups(String type) {
                return JavaProjectConstants.SOURCES_TYPE_JAVA.equals(type)
                        ? new SourceGroup[]{group} : new SourceGroup[0];
            }
            @Override public void addChangeListener(ChangeListener listener) { }
            @Override public void removeChangeListener(ChangeListener listener) { }
        };
        return new Project() {
            private final Lookup lookup = Lookups.fixed(sources);
            @Override public FileObject getProjectDirectory() { return directory; }
            @Override public Lookup getLookup() { return lookup; }
        };
    }
}
