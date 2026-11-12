package co.edu.cecar.objectrepositorymanager.worker;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;

/** Standalone integration suite: every request starts the same worker JVM used by the UI. */
public final class RepositoryWorkerIntegrationTest {
    public static class Entity implements Serializable {
        private static final long serialVersionUID = 1;
        public int id;
        public String name;
        public double price;
        public Entity() { }
    }
    public static class Product implements Serializable {
        private static final long serialVersionUID = 1;
        public final int id;
        public final String name;
        private double price;
        public Product(int id, String name, double price) {
            this.id = id; this.name = name; setPrice(price);
        }
        public void setPrice(double price) {
            if (price < 0) throw new IllegalArgumentException("El precio no puede ser negativo.");
            this.price = price;
        }
    }
    public static class Nested implements Serializable {
        private static final long serialVersionUID = 1;
        public int id;
        public String name;
        public ArrayList<String> details;
        public Nested() { }
    }
    public static class Base implements Serializable {
        private static final long serialVersionUID = 1;
        public int id;
    }
    public static class Child extends Base {
        private static final long serialVersionUID = 1;
        public String name;
        public Child() { }
    }

    private static Path directory;
    private static int count;
    private static String extraClasspath = "";

    public static void main(String[] args) throws Exception {
        directory = Files.createTempDirectory("object-repository-worker-test-");
        try {
            crud();
            invalidFiles();
            constructorAndSetter();
            preserveNested();
            inheritanceAndDiscovery();
            if (args.length > 0) actualRepository(Path.of(args[0]));
            System.out.println("PASS: " + count + " comprobaciones de integración, procesos Java separados.");
        } finally {
            // Test owns exactly this fresh temporary directory.
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void crud() throws Exception {
        Path file = directory.resolve("entities.bin");
        Properties schema = success(call(request("describe", Entity.class, file)));
        check(schema.getProperty("creatable").equals("true"), "constructor vacío disponible");
        Properties create = request("create", Entity.class, file);
        create.setProperty("revision", "MISSING");
        Properties empty = success(call(create));
        check(empty.getProperty("row.count").equals("0"), "archivo nuevo vacío");
        check(!Boolean.parseBoolean(call(create).getProperty("ok")), "crear no reemplaza un archivo existente");
        Properties insert = mutation("insert", Entity.class, file, empty);
        value(insert, schema, "id", "1"); value(insert, schema, "name", "Café = áéí\nsegunda línea"); value(insert, schema, "price", "12.5");
        Properties one = success(call(insert));
        check(one.getProperty("row.count").equals("1"), "insertar guarda una fila");
        check(one.getProperty("cell.0." + field(schema, "name")).contains("Café = áéí"), "protocolo conserva Unicode");
        byte[] before = Files.readAllBytes(file);
        Properties duplicate = mutation("insert", Entity.class, file, one);
        value(duplicate, schema, "id", "1");
        expectFailureUnchanged(duplicate, file, before, "identificador duplicado");
        Properties stale = mutation("delete", Entity.class, file, empty); stale.setProperty("row", "0");
        expectFailureUnchanged(stale, file, before, "revisión obsoleta");
        Properties update = mutation("update", Entity.class, file, one); update.setProperty("row", "0");
        value(update, schema, "name", "Actualizado");
        Properties updated = success(call(update));
        check(updated.getProperty("cell.0." + field(schema, "name")).equals("Actualizado"), "actualizar persiste");
        check(Arrays.equals(before, Files.readAllBytes(file.resolveSibling("entities.bin.bak"))), "respaldo contiene versión anterior");
        Properties delete = mutation("delete", Entity.class, file, updated); delete.setProperty("row", "0");
        check(success(call(delete)).getProperty("row.count").equals("0"), "eliminar persiste");
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            check(in.readObject() instanceof ArrayList<?>, "archivo es ArrayList serializado estándar");
        }
    }

    private static void invalidFiles() throws Exception {
        Path file = directory.resolve("corrupt.bin");
        Files.writeString(file, "not serialized");
        Properties open = request("open", Entity.class, file);
        expectFailureUnchanged(open, file, Files.readAllBytes(file), "archivo corrupto");
        write(file, "root is not List");
        expectFailureUnchanged(open, file, Files.readAllBytes(file), "raíz incorrecta");
        write(file, new ArrayList<>(List.of("wrong entity")));
        expectFailureUnchanged(open, file, Files.readAllBytes(file), "clase incompatible");
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) {
            out.writeObject(new ArrayList<Entity>()); out.writeObject("extra data");
        }
        expectFailureUnchanged(open, file, Files.readAllBytes(file), "datos adicionales");
        write(file, new ArrayList<Entity>());
        Files.write(file, new byte[]{0x73}, StandardOpenOption.APPEND);
        expectFailureUnchanged(open, file, Files.readAllBytes(file), "objeto adicional truncado");
        Files.write(file, new byte[0]);
        check(success(call(open)).getProperty("row.count").equals("0"), "archivo vacío compatible");
        check(!Boolean.parseBoolean(call(request("open", Entity.class, directory.resolve("missing.bin"))).getProperty("ok")), "archivo inexistente informa error");
    }

    private static void constructorAndSetter() throws Exception {
        Path file = directory.resolve("products.bin");
        Properties create = request("create", Product.class, file); create.setProperty("revision", "MISSING");
        Properties schema = success(call(create));
        check(schema.getProperty("creatable").equals("true"), "constructor con parámetros y campos finales disponible");
        check(schema.getProperty("field." + field(schema, "id") + ".editable").equals("false"), "identificador final no editable");
        Properties insert = mutation("insert", Product.class, file, schema);
        insert.setProperty("constructor", schema.getProperty("ctor.0.key"));
        insert.setProperty("arg.0", "77"); insert.setProperty("arg.1", "Producto"); insert.setProperty("arg.2", "25");
        Properties inserted = success(call(insert));
        check(inserted.getProperty("cell.0." + field(schema, "id")).equals("77"), "constructor conserva identificador final");
        byte[] before = Files.readAllBytes(file);
        Properties update = mutation("update", Product.class, file, inserted); update.setProperty("row", "0");
        value(update, schema, "price", "-1");
        expectFailureUnchanged(update, file, before, "setter rechaza precio inválido");
        value(update, schema, "price", "30");
        check(success(call(update)).getProperty("cell.0." + field(schema, "price")).equals("30.0"), "setter acepta precio válido");
    }

    private static void preserveNested() throws Exception {
        Path file = directory.resolve("nested.bin");
        Nested row = new Nested(); row.id = 1; row.name = "Antes"; row.details = new ArrayList<>(List.of("dato anidado"));
        write(file, new ArrayList<>(List.of(row)));
        Properties schema = success(call(request("open", Nested.class, file)));
        check(schema.getProperty("creatable").equals("false"), "no ofrece creación vacía con campos complejos");
        Properties update = mutation("update", Nested.class, file, schema); update.setProperty("row", "0"); value(update, schema, "name", "Después");
        success(call(update));
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            Nested loaded = (Nested) ((List<?>) in.readObject()).get(0);
            check(loaded.details.equals(row.details), "actualizar conserva datos anidados");
        }
    }

    private static void inheritanceAndDiscovery() throws Exception {
        Properties describe = success(call(request("describe", Child.class, directory.resolve("unused.bin"))));
        check(field(describe, "id") >= 0 && field(describe, "name") >= 0, "campos heredados incluidos");
        Properties discover = new Properties(); discover.setProperty("op", "discover");
        discover.setProperty("roots", Path.of(RepositoryWorkerIntegrationTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Properties found = success(call(discover));
        check(found.values().contains(Child.class.getName()), "Serializable por herencia detectado");
    }

    private static void actualRepository(Path jar) throws Exception {
        Path source = directory.resolve("LibraryFixture.java");
        String code = """
                import java.io.*;
                import co.edu.cecar.objectrepository.ObjectRepository;
                public class LibraryFixture {
                    public static class Bean implements Serializable {
                        private static final long serialVersionUID = 1L;
                        public int id; public String name;
                        public Bean() { }
                    }
                    public static void main(String[] args) {
                        var repository = new ObjectRepository<Bean,Integer>(args[1], bean -> bean.id) {};
                        if (args[0].equals("create")) {
                            Bean bean = new Bean(); bean.id = 9; bean.name = "Biblioteca"; repository.save(bean);
                        } else if (!repository.getAll().get(0).name.equals("Plugin")) throw new AssertionError("Lectura biblioteca incompatible");
                    }
                }
                """;
        Files.writeString(source, code);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", jar.toString(), "-d", directory.toString(), source.toString());
        check(compiled == 0, "fixture compila contra biblioteca real");
        extraClasspath = File.pathSeparator + directory + File.pathSeparator + jar;
        Path file = directory.resolve("actual-library.bin");
        runJava("LibraryFixture", "create", file.toString());
        Properties open = new Properties(); open.setProperty("op", "open"); open.setProperty("type", "LibraryFixture$Bean"); open.setProperty("file", file.toString());
        Properties loaded = success(call(open));
        check(loaded.getProperty("row.count").equals("1"), "lee archivo creado por object-repository real");
        Properties update = new Properties(); update.putAll(open); update.setProperty("op", "update");
        update.setProperty("revision", loaded.getProperty("revision")); update.setProperty("row", "0");
        update.setProperty("idField", loaded.getProperty("field." + field(loaded, "id") + ".key"));
        value(update, loaded, "name", "Plugin"); success(call(update));
        runJava("LibraryFixture", "verify", file.toString());
        check(true, "biblioteca real lee modificación del plugin");
    }

    private static Properties request(String operation, Class<?> type, Path file) {
        Properties request = new Properties(); request.setProperty("op", operation);
        request.setProperty("type", type.getName()); request.setProperty("file", file.toString()); return request;
    }
    private static Properties mutation(String op, Class<?> type, Path file, Properties snapshot) {
        Properties request = request(op, type, file);
        request.setProperty("revision", snapshot.getProperty("revision"));
        request.setProperty("idField", snapshot.getProperty("field." + field(snapshot, "id") + ".key")); return request;
    }
    private static int field(Properties schema, String name) {
        for (int i = 0; i < Integer.parseInt(schema.getProperty("field.count")); i++)
            if (name.equals(schema.getProperty("field." + i + ".name"))) return i;
        throw new AssertionError("Campo no encontrado: " + name);
    }
    private static void value(Properties request, Properties schema, String name, String value) { request.setProperty("value." + field(schema, name), value); }
    private static void write(Path file, Object root) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(file))) { out.writeObject(root); }
    }
    private static Properties call(Properties request) throws Exception {
        Path input = Files.createTempFile(directory, "request-", ".properties");
        Path output = Files.createTempFile(directory, "response-", ".properties");
        try (OutputStream stream = Files.newOutputStream(input)) { request.store(stream, null); }
        runJava(RepositoryWorker.class.getName(), input.toString(), output.toString());
        Properties result = new Properties(); try (InputStream stream = Files.newInputStream(output)) { result.load(stream); }
        return result;
    }
    private static void runJava(String main, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path") + extraClasspath, main));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Timeout worker"); }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new AssertionError(output);
    }
    private static Properties success(Properties result) {
        if (!Boolean.parseBoolean(result.getProperty("ok"))) throw new AssertionError(result.getProperty("error"));
        return result;
    }
    private static void expectFailureUnchanged(Properties request, Path file, byte[] before, String description) throws Exception {
        check(!Boolean.parseBoolean(call(request).getProperty("ok")), description + " rechazado");
        check(Arrays.equals(before, Files.readAllBytes(file)), description + " conserva archivo");
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        count++;
    }
}
