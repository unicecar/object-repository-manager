package co.edu.cecar.objectrepositorymanager;

import co.edu.cecar.objectrepositorymanager.worker.RepositoryWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

/** Optional compatibility check: original file is only copied, never opened by the worker. */
public final class ZentroShopCompatibilityCheck {
    private static Path work;
    private static final String TYPE = "co.zentroshop.app.entity.Producto";

    public static void main(String[] args) throws Exception {
        work = Files.createTempDirectory("orm-zentro-check-");
        try {
            Path copy = work.resolve("products-copy.bin");
            Files.copy(Path.of(args[0]), copy);
            Properties open = call(request("open", copy));
            int originalCount = Integer.parseInt(open.getProperty("row.count"));
            check(Integer.parseInt(open.getProperty("ctor.count")) > 0, "Producto constructor disponible");
            Properties insert = mutation("insert", copy, open);
            insert.setProperty("constructor", TYPE + "(java.lang.String,double,int)");
            insert.setProperty("arg.0", "Producto de prueba del gestor");
            insert.setProperty("arg.1", "1500");
            insert.setProperty("arg.2", "8");
            Properties added = call(insert);
            check(Integer.parseInt(added.getProperty("row.count")) == originalCount + 1, "Insertar producto");
            int price = field(added, "precio");
            int sku = field(added, "sku");
            check(!Boolean.parseBoolean(added.getProperty("field." + sku + ".editable")), "SKU final protegido");
            byte[] before = Files.readAllBytes(copy);
            Properties update = mutation("update", copy, added);
            update.setProperty("row", Integer.toString(originalCount));
            update.setProperty("value." + price, "100");
            Properties rejected = invoke(update);
            check("false".equals(rejected.getProperty("ok")), "Validación setPrecio");
            check(java.util.Arrays.equals(before, Files.readAllBytes(copy)), "Precio inválido conserva archivo");
            update.setProperty("value." + price, "2000");
            Properties edited = call(update);
            check("2000.0".equals(edited.getProperty("cell." + originalCount + "." + price)), "Editar precio");
            Properties delete = mutation("delete", copy, edited);
            delete.setProperty("row", Integer.toString(originalCount));
            Properties deleted = call(delete);
            check(Integer.parseInt(deleted.getProperty("row.count")) == originalCount, "Eliminar producto");
            check(Files.isRegularFile(copy.resolveSibling("products-copy.bin.bak")), "Respaldo creado");
            System.out.println("PASS: CRUD con Producto real y copia de product.bin; original sin modificar.");
        } finally {
            try (var files = Files.walk(work)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static int field(Properties snapshot, String name) {
        for (int i = 0; i < Integer.parseInt(snapshot.getProperty("field.count")); i++)
            if (name.equals(snapshot.getProperty("field." + i + ".name"))) return i;
        throw new AssertionError("Missing field " + name);
    }

    private static Properties request(String op, Path file) {
        Properties p = new Properties();
        p.setProperty("op", op);
        p.setProperty("type", TYPE);
        p.setProperty("file", file.toString());
        return p;
    }

    private static Properties mutation(String op, Path file, Properties snapshot) {
        Properties p = request(op, file);
        p.setProperty("revision", snapshot.getProperty("revision"));
        p.setProperty("idField", TYPE + "#sku");
        return p;
    }

    private static Properties call(Properties p) throws Exception {
        Properties response = invoke(p);
        check("true".equals(response.getProperty("ok")), response.getProperty("error", "Worker falló"));
        return response;
    }

    private static Properties invoke(Properties p) throws Exception {
        Path input = work.resolve("request.properties"), output = work.resolve("response.properties");
        try (var stream = Files.newOutputStream(input)) { p.store(stream, null); }
        RepositoryWorker.main(new String[]{input.toString(), output.toString()});
        Properties response = new Properties();
        try (var stream = Files.newInputStream(output)) { response.load(stream); }
        return response;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
