package co.edu.cecar.objectrepositorymanager.worker;

import java.io.*;
import java.lang.reflect.*;
import java.math.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Runs in a separate JVM; application classes never enter the NetBeans process. */
public final class RepositoryWorker {
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ROWS = 10000;
    private RepositoryWorker() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Se esperan solicitud y respuesta.");
        Properties response;
        try {
            Properties request = new Properties();
            try (InputStream in = Files.newInputStream(Path.of(args[0]))) { request.load(in); }
            response = execute(request);
            response.setProperty("ok", "true");
        } catch (Throwable failure) {
            response = new Properties();
            response.setProperty("ok", "false");
            Throwable cause = failure;
            while (cause instanceof InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
            response.setProperty("error", cause.getClass().getSimpleName() + ": "
                    + Objects.toString(cause.getMessage(), "No se pudo completar la operación."));
        }
        try (OutputStream out = Files.newOutputStream(Path.of(args[1]))) { response.store(out, null); }
    }

    static Properties execute(Properties request) throws Exception {
        String operation = request.getProperty("op", request.getProperty("operation"));
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("Falta el parámetro op.");
        if ("discover".equals(operation)) return discover(request);
        if (!Set.of("describe", "open", "create", "insert", "update", "delete").contains(operation))
            throw new IllegalArgumentException("Operación desconocida: " + operation);
        Class<?> type = Class.forName(required(request, "type"), false, RepositoryWorker.class.getClassLoader());
        if (!Serializable.class.isAssignableFrom(type) || type.isInterface() || Modifier.isAbstract(type.getModifiers()))
            throw new IllegalArgumentException("Selecciona una clase concreta que implemente Serializable.");
        List<Field> fields = fields(type);
        if ("describe".equals(operation)) return snapshot(type, fields, List.of(), "MISSING");
        Path file = Path.of(required(request, "file")).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(file)) throw new IOException("Selecciona el archivo original, no un enlace simbólico.");
        if (file.getParent() == null || !Files.isDirectory(file.getParent()))
            throw new IOException("La carpeta del archivo no existe.");
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = acquireLock(channel)) {
            if ("create".equals(operation)) {
                if (!"MISSING".equals(required(request, "revision")))
                    throw new IOException("La creación requiere una ruta nueva.");
                byte[] bytes = serialize(new ArrayList<>());
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return snapshot(type, fields, List.of(), digest(bytes));
            }
            byte[] original = readBytes(file);
            String revision = digest(original);
            List<Object> rows = readRows(original, type);
            if ("open".equals(operation)) return snapshot(type, fields, rows, revision);
            if (!revision.equals(required(request, "revision")))
                throw new IOException("El archivo cambió desde la última consulta. Actualiza los registros antes de guardar.");
            Field id = fields.stream().filter(f -> key(f).equals(request.getProperty("idField"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Selecciona un campo identificador."));
            if (!supported(id.getType()) || !id.trySetAccessible())
                throw new IllegalArgumentException("El identificador debe ser un campo simple accesible.");
            validateIds(rows, id);
            switch (operation) {
                case "insert":
                    if (!creatable(type, fields)) throw new IllegalArgumentException(
                            "Esta clase necesita un constructor público con parámetros simples compatibles para crear registros.");
                    if (rows.size() >= MAX_ROWS) throw new IOException("Se alcanzó el límite de " + MAX_ROWS + " registros.");
                    Object added = createObject(type, fields, request);
                    rows.add(added);
                    break;
                case "update":
                    applyValues(rows.get(rowIndex(request, rows.size())), fields, request);
                    break;
                case "delete":
                    rows.remove(rowIndex(request, rows.size()));
                    break;
                default: throw new IllegalArgumentException("Operación inválida.");
            }
            validateIds(rows, id);
            byte[] updated = serialize(new ArrayList<>(rows));
            // Validate the bytes that will actually be stored, including custom writeObject/readResolve.
            List<Object> persistedRows = readRows(updated, type);
            validateIds(persistedRows, id);
            Properties result = snapshot(type, fields, persistedRows, digest(updated));
            persist(file, original, updated);
            return result;
        }
    }

    private static Properties discover(Properties request) throws IOException {
        SortedSet<String> candidates = new TreeSet<>();
        SortedSet<String> found = new TreeSet<>();
        List<String> warnings = new ArrayList<>();
        String roots = request.getProperty("roots", "");
        for (String rootText : roots.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (rootText.isBlank()) continue;
            Path root = Path.of(rootText).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class"))
                        .forEach(p -> {
                            String name = root.relativize(p).toString().replace(File.separatorChar, '.');
                            name = name.substring(0, name.length() - 6);
                            if (!name.equals("module-info") && !name.endsWith("package-info")) candidates.add(name);
                        });
            }
        }
        for (String name : candidates) {
            try {
                Class<?> type = Class.forName(name, false, RepositoryWorker.class.getClassLoader());
                if (Serializable.class.isAssignableFrom(type) && !type.isInterface() && !type.isSynthetic() && !type.isEnum()
                        && !"ObjectRepository".equals(type.getSimpleName())
                        && !type.getName().contains(".ObjectRepository$")
                        && !java.awt.Component.class.isAssignableFrom(type) && !Throwable.class.isAssignableFrom(type)
                        && !type.isAnonymousClass() && !type.isLocalClass() && !Modifier.isAbstract(type.getModifiers())
                        && (!type.isMemberClass() || Modifier.isStatic(type.getModifiers()))) found.add(name);
            } catch (LinkageError | ReflectiveOperationException | SecurityException failure) {
                if (warnings.size() < 20) warnings.add(name + ": " + failure.getClass().getSimpleName());
            }
        }
        Properties result = new Properties();
        result.setProperty("class.count", Integer.toString(found.size()));
        int index = 0;
        for (String name : found) result.setProperty("class." + index++, name);
        List<Map.Entry<Path, String>> dataObjects = discoverDataObjects(request.getProperty("dataDirectory"), found, warnings);
        result.setProperty("object.count", Integer.toString(dataObjects.size()));
        for (int i = 0; i < dataObjects.size(); i++) {
            result.setProperty("object." + i + ".file", dataObjects.get(i).getKey().toString());
            result.setProperty("object." + i + ".type", dataObjects.get(i).getValue());
        }
        result.setProperty("warnings", String.join("\n", warnings));
        return result;
    }

    private static List<Map.Entry<Path, String>> discoverDataObjects(String directory, Set<String> entityTypes,
            List<String> warnings) {
        if (directory == null) return List.of();
        Path data = Path.of(directory).toAbsolutePath().normalize();
        if (!Files.isDirectory(data)) return List.of();
        List<Map.Entry<Path, String>> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(data)) {
            for (Path file : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bin"))
                    .sorted().toList()) {
                if (Files.isSymbolicLink(file) || Files.size(file) == 0 || Files.size(file) > MAX_FILE_BYTES) continue;
                try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
                    in.setObjectInputFilter(info -> info.depth() > 64 || info.references() > 100000
                            || info.arrayLength() > 1000000 || info.streamBytes() > MAX_FILE_BYTES
                            ? ObjectInputFilter.Status.REJECTED : ObjectInputFilter.Status.UNDECIDED);
                    Object value = in.readObject();
                    if (!(value instanceof List<?> records) || records.isEmpty()) continue;
                    Object first = records.get(0);
                    if (first == null) continue;
                    String type = first.getClass().getName();
                    if (entityTypes.contains(type) && records.stream().allMatch(row -> row != null && row.getClass() == first.getClass()))
                        result.add(Map.entry(file.toAbsolutePath().normalize(), type));
                } catch (IOException | ClassNotFoundException | RuntimeException ex) {
                    if (warnings.size() < 20) warnings.add(file.getFileName() + ": no se pudo identificar el tipo de objeto");
                }
            }
        } catch (IOException | SecurityException ex) {
            if (warnings.size() < 20) warnings.add("data: no se pudo leer la carpeta de archivos predeterminados");
        }
        return result;
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null && Serializable.class.isAssignableFrom(current);
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && !field.isSynthetic()) result.add(field);
            }
        }
        result.sort(Comparator.comparing(RepositoryWorker::key));
        return result;
    }

    private static String key(Field field) { return field.getDeclaringClass().getName() + "#" + field.getName(); }

    private static boolean editable(Field field) {
        return supported(field.getType()) && !Modifier.isFinal(field.getModifiers()) && field.trySetAccessible();
    }

    private static boolean creatable(Class<?> type, List<Field> fields) {
        return !constructors(type, fields).isEmpty();
    }

    private static List<Constructor<?>> constructors(Class<?> type, List<Field> fields) {
        if (type.isEnum() || type.isRecord()) return List.of();
        List<Constructor<?>> result = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isSynthetic() || !Modifier.isPublic(constructor.getModifiers())) continue;
            if (!Arrays.stream(constructor.getParameterTypes()).allMatch(RepositoryWorker::supported)) continue;
            if (constructor.getParameterCount() == 0 && fields.stream().anyMatch(f -> !editable(f))) continue;
            if (constructor.trySetAccessible()) result.add(constructor);
        }
        result.sort(Comparator.comparing(RepositoryWorker::constructorKey));
        return result;
    }

    private static String constructorKey(Constructor<?> constructor) {
        return constructor.getDeclaringClass().getName() + "(" + String.join(",",
                Arrays.stream(constructor.getParameterTypes()).map(Class::getName).toArray(String[]::new)) + ")";
    }

    private static Object createObject(Class<?> type, List<Field> fields, Properties request) throws Exception {
        List<Constructor<?>> available = constructors(type, fields);
        String selected = request.getProperty("constructor");
        Constructor<?> constructor = available.stream()
                .filter(c -> selected == null ? c.getParameterCount() == 0 : constructorKey(c).equals(selected))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Selecciona un constructor disponible."));
        Object[] arguments = new Object[constructor.getParameterCount()];
        for (int i = 0; i < arguments.length; i++) {
            Class<?> parameterType = constructor.getParameterTypes()[i];
            boolean isNull = Boolean.parseBoolean(request.getProperty("argNull." + i, "false"));
            if (isNull && parameterType.isPrimitive()) throw new IllegalArgumentException("El argumento " + (i + 1) + " no admite null.");
            try { arguments[i] = isNull ? null : parse(parameterType, request.getProperty("arg." + i, "")); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("Argumento " + (i + 1) + " inválido: " + ex.getMessage(), ex); }
        }
        Object created = constructor.newInstance(arguments);
        if (arguments.length == 0) applyValues(created, fields, request);
        return created;
    }

    private static boolean supported(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || Set.of(String.class, Boolean.class, Character.class,
                Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
                BigDecimal.class, BigInteger.class, UUID.class, LocalDate.class, LocalDateTime.class,
                Instant.class).contains(type);
    }

    private static Properties snapshot(Class<?> type, List<Field> fields, List<?> rows, String revision) throws Exception {
        Properties result = new Properties();
        result.setProperty("type", type.getName());
        result.setProperty("creatable", Boolean.toString(creatable(type, fields)));
        result.setProperty("revision", revision);
        result.setProperty("field.count", Integer.toString(fields.size()));
        result.setProperty("row.count", Integer.toString(rows.size()));
        List<Constructor<?>> constructors = constructors(type, fields);
        result.setProperty("ctor.count", Integer.toString(constructors.size()));
        for (int c = 0; c < constructors.size(); c++) {
            Constructor<?> constructor = constructors.get(c);
            String prefix = "ctor." + c + ".";
            List<String> parameterNames = constructorParameterNames(constructor);
            result.setProperty(prefix + "key", constructorKey(constructor));
            result.setProperty(prefix + "label", type.getSimpleName() + "(" + String.join(", ",
                    java.util.stream.IntStream.range(0, constructor.getParameterCount())
                            .mapToObj(i -> constructor.getParameterTypes()[i].getSimpleName() + " " + parameterNames.get(i))
                            .toArray(String[]::new)) + ")");
            result.setProperty(prefix + "param.count", Integer.toString(constructor.getParameterCount()));
            for (int p = 0; p < constructor.getParameterCount(); p++) {
                Parameter parameter = constructor.getParameters()[p];
                String parameterPrefix = prefix + "param." + p + ".";
                result.setProperty(parameterPrefix + "name", parameterNames.get(p));
                result.setProperty(parameterPrefix + "type", parameter.getType().getTypeName());
                result.setProperty(parameterPrefix + "nullable", Boolean.toString(!parameter.getType().isPrimitive()));
                if (parameter.getType().isEnum()) result.setProperty(parameterPrefix + "enum", String.join(",",
                        Arrays.stream(parameter.getType().getEnumConstants()).map(v -> ((Enum<?>) v).name()).toArray(String[]::new)));
            }
        }
        for (int f = 0; f < fields.size(); f++) {
            Field field = fields.get(f);
            String prefix = "field." + f + ".";
            result.setProperty(prefix + "key", key(field));
            result.setProperty(prefix + "name", field.getName());
            result.setProperty(prefix + "type", field.getType().getTypeName());
            result.setProperty(prefix + "editable", Boolean.toString(editable(field)));
            result.setProperty(prefix + "nullable", Boolean.toString(!field.getType().isPrimitive()));
            result.setProperty(prefix + "identifier", Boolean.toString(supported(field.getType()) && field.trySetAccessible()));
            if (field.getType().isEnum()) {
                List<String> names = new ArrayList<>();
                for (Object value : field.getType().getEnumConstants()) names.add(((Enum<?>) value).name());
                result.setProperty(prefix + "enum", String.join(",", names));
            }
            boolean accessible = field.trySetAccessible();
            for (int r = 0; r < rows.size(); r++) {
                String cell = "cell." + r + "." + f;
                Object value = accessible ? field.get(rows.get(r)) : null;
                if (!accessible) result.setProperty(cell, "[No accesible]");
                else if (value == null) { result.setProperty(cell, ""); result.setProperty(cell + ".null", "true"); }
                else result.setProperty(cell, supported(field.getType()) ? format(value) : "[" + value.getClass().getTypeName() + "]");
            }
        }
        return result;
    }

    /** Uses MethodParameters when present, otherwise reads javac's debug local-variable names. */
    private static List<String> constructorParameterNames(Constructor<?> constructor) {
        Parameter[] parameters = constructor.getParameters();
        List<String> names = Arrays.stream(parameters).map(Parameter::getName).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (Arrays.stream(parameters).allMatch(Parameter::isNamePresent)) return names;
        try {
            Map<Integer, String> slots = localVariableNames(constructor.getDeclaringClass(), constructorDescriptor(constructor));
            int slot = 1; // slot zero contains `this`.
            for (int i = 0; i < parameters.length; i++) {
                String name = slots.get(slot);
                if (name != null && !name.isBlank() && !name.matches("arg\\d+")) names.set(i, name);
                Class<?> parameterType = parameters[i].getType();
                slot += parameterType == long.class || parameterType == double.class ? 2 : 1;
            }
        } catch (IOException | RuntimeException ignored) {
            // Keep reflection's arg0/arg1 names when debug information is absent.
        }
        return names;
    }

    private static String constructorDescriptor(Constructor<?> constructor) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : constructor.getParameterTypes()) descriptor.append(typeDescriptor(parameter));
        return descriptor.append(")V").toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        return "D";
    }

    private static Map<Integer, String> localVariableNames(Class<?> type, String descriptor) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream bytes = type.getResourceAsStream(resource)) {
            if (bytes == null) return Map.of();
            DataInputStream in = new DataInputStream(bytes);
            if (in.readInt() != 0xCAFEBABE) return Map.of();
            in.readUnsignedShort(); in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> { int length = in.readUnsignedShort(); byte[] value = new byte[length + 2]; value[0] = (byte) (length >>> 8); value[1] = (byte) length; in.readFully(value, 2, length); utf8[i] = new DataInputStream(new ByteArrayInputStream(value)).readUTF(); }
                    case 3, 4 -> in.skipNBytes(4);
                    case 5, 6 -> { in.skipNBytes(8); i++; }
                    case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 15 -> in.skipNBytes(3);
                    default -> throw new IOException("Unknown class-file constant tag " + tag);
                }
            }
            in.skipNBytes(6);
            int interfaces = in.readUnsignedShort(); in.skipNBytes(2L * interfaces);
            skipMembers(in); // fields
            int methods = in.readUnsignedShort();
            for (int m = 0; m < methods; m++) {
                in.readUnsignedShort();
                int name = in.readUnsignedShort(), desc = in.readUnsignedShort();
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++) {
                    int attrName = in.readUnsignedShort();
                    byte[] data = in.readNBytes(in.readInt());
                    if ("<init>".equals(utf8[name]) && descriptor.equals(utf8[desc])
                            && "Code".equals(utf8[attrName])) {
                        return parseLocalVariableTable(data, utf8);
                    }
                }
            }
        }
        return Map.of();
    }

    private static void skipMembers(DataInputStream in) throws IOException {
        int fields = in.readUnsignedShort();
        for (int f = 0; f < fields; f++) {
            in.skipNBytes(6);
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) { in.skipNBytes(2); in.skipNBytes(Integer.toUnsignedLong(in.readInt())); }
        }
    }

    private static Map<Integer, String> parseLocalVariableTable(byte[] codeAttribute, String[] utf8) throws IOException {
        DataInputStream code = new DataInputStream(new ByteArrayInputStream(codeAttribute));
        code.skipNBytes(4);
        code.skipNBytes(Integer.toUnsignedLong(code.readInt()));
        int exceptions = code.readUnsignedShort(); code.skipNBytes(8L * exceptions);
        int attributes = code.readUnsignedShort();
        Map<Integer, String> names = new HashMap<>();
        for (int a = 0; a < attributes; a++) {
            int attributeName = code.readUnsignedShort();
            byte[] data = code.readNBytes(code.readInt());
            if (!"LocalVariableTable".equals(utf8[attributeName])) continue;
            DataInputStream table = new DataInputStream(new ByteArrayInputStream(data));
            int entries = table.readUnsignedShort();
            for (int e = 0; e < entries; e++) {
                int start = table.readUnsignedShort(); table.readUnsignedShort();
                int name = table.readUnsignedShort(); table.readUnsignedShort();
                int slot = table.readUnsignedShort();
                if (start == 0 && name < utf8.length && utf8[name] != null) names.putIfAbsent(slot, utf8[name]);
            }
        }
        return names;
    }

    private static String format(Object value) {
        return value instanceof Enum<?> ? ((Enum<?>) value).name() : value.toString();
    }

    private static void applyValues(Object row, List<Field> fields, Properties request) throws Exception {
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (!editable(field)) continue;
            if (!request.containsKey("value." + i) && !request.containsKey("null." + i)) continue;
            boolean isNull = Boolean.parseBoolean(request.getProperty("null." + i, "false"));
            if (isNull && field.getType().isPrimitive())
                throw new IllegalArgumentException("El campo " + field.getName() + " no admite null.");
            try {
                Object value = isNull ? null : parse(field.getType(), request.getProperty("value." + i, ""));
                String setterName = "set" + Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
                try {
                    Method setter = row.getClass().getMethod(setterName, field.getType());
                    if (Modifier.isStatic(setter.getModifiers())) throw new NoSuchMethodException();
                    setter.invoke(row, value);
                } catch (NoSuchMethodException noSetter) { field.set(row, value); }
            }
            catch (RuntimeException ex) { throw new IllegalArgumentException("Valor inválido para " + field.getName()
                    + " (" + field.getType().getSimpleName() + "): " + ex.getMessage(), ex); }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parse(Class<?> type, String text) {
        if (type == String.class) return text;
        if (type == char.class || type == Character.class) {
            if (text.length() != 1) throw new IllegalArgumentException("Debe contener un carácter.");
            return text.charAt(0);
        }
        text = text.trim();
        if (type == boolean.class || type == Boolean.class) {
            if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false"))
                throw new IllegalArgumentException("Usa true o false.");
            return Boolean.valueOf(text);
        }
        String number = normalizeNumber(text);
        if (type == byte.class || type == Byte.class) return Byte.valueOf(number);
        if (type == short.class || type == Short.class) return Short.valueOf(number);
        if (type == int.class || type == Integer.class) return Integer.valueOf(number);
        if (type == long.class || type == Long.class) return Long.valueOf(number);
        if (type == float.class || type == Float.class) {
            float value = Float.parseFloat(number);
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Debe ser un número finito.");
            return value;
        }
        if (type == double.class || type == Double.class) {
            double value = Double.parseDouble(number);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Debe ser un número finito.");
            return value;
        }
        if (type == BigDecimal.class) return new BigDecimal(number);
        if (type == BigInteger.class) return new BigInteger(number);
        if (type == UUID.class) return UUID.fromString(text);
        if (type == LocalDate.class) return LocalDate.parse(text);
        if (type == LocalDateTime.class) return LocalDateTime.parse(text);
        if (type == Instant.class) return Instant.parse(text);
        if (type.isEnum()) return Enum.valueOf((Class<? extends Enum>) type, text);
        throw new IllegalArgumentException("Tipo no editable.");
    }

    /** Accept Colombian grouping (270.100), decimal comma (270,5), and either combined format. */
    private static String normalizeNumber(String text) {
        text = text.replace("\u00a0", "").replace(" ", "");
        int comma = text.lastIndexOf(',');
        int dot = text.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            return comma > dot ? text.replace(".", "").replace(',', '.') : text.replace(",", "");
        }
        if (comma >= 0) {
            String[] chunks = text.split(",", -1);
            if (chunks.length > 2 || (chunks.length == 2 && chunks[1].length() == 3 && chunks[1].matches("\\d{3}")))
                return text.replace(",", "");
            return text.replace(',', '.');
        }
        if (dot >= 0) {
            String[] chunks = text.split("\\.", -1);
            if (chunks.length > 2 || (chunks.length == 2 && chunks[1].length() == 3 && chunks[1].matches("\\d{3}")))
                return text.replace(".", "");
        }
        return text;
    }

    private static int rowIndex(Properties request, int size) {
        int row = Integer.parseInt(required(request, "row"));
        if (row < 0 || row >= size) throw new IllegalArgumentException("El registro seleccionado ya no existe.");
        return row;
    }

    private static void validateIds(List<?> rows, Field id) throws IllegalAccessException {
        Set<Object> ids = new HashSet<>();
        for (Object row : rows) {
            Object value = id.get(row);
            if (value == null) throw new IllegalArgumentException("El identificador " + id.getName() + " no admite null.");
            if (!ids.add(value)) throw new IllegalArgumentException("Identificador duplicado en " + id.getName() + ": " + format(value));
        }
    }

    private static byte[] readBytes(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("El archivo supera el límite de 64 MiB.");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("El archivo supera el límite de 64 MiB.");
            return bytes;
        }
    }

    private static List<Object> readRows(byte[] bytes, Class<?> type) throws IOException, ClassNotFoundException {
        if (bytes.length == 0) return new ArrayList<>();
        ByteArrayInputStream buffer = new ByteArrayInputStream(bytes);
        try (ObjectInputStream in = new ObjectInputStream(buffer)) {
            in.setObjectInputFilter(info -> info.depth() > 64 || info.references() > 100000
                    || info.arrayLength() > 1000000 || info.streamBytes() > MAX_FILE_BYTES
                    ? ObjectInputFilter.Status.REJECTED : ObjectInputFilter.Status.UNDECIDED);
            Object root = in.readObject();
            if (!(root instanceof List<?>)) throw new IOException("Formato incompatible: se esperaba una lista serializada.");
            List<?> stored = (List<?>) root;
            if (stored.size() > MAX_ROWS) throw new IOException("El archivo supera el límite de " + MAX_ROWS + " registros.");
            List<Object> rows = new ArrayList<>(stored.size());
            for (Object row : stored) {
                if (row == null || !type.isInstance(row)) throw new IOException("El archivo contiene objetos incompatibles con " + type.getName() + ".");
                rows.add(row);
            }
            // Refuse trailing objects/data instead of silently dropping them when saving.
            if (buffer.available() != 0) throw new IOException("El archivo contiene datos adicionales después de la lista.");
            return rows;
        }
    }

    private static byte[] serialize(List<?> rows) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) { out.writeObject(rows); }
        if (buffer.size() > MAX_FILE_BYTES) throw new IOException("Los datos superan el límite de 64 MiB.");
        return buffer.toByteArray();
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void persist(Path file, byte[] original, byte[] updated) throws Exception {
        Path temporary = Files.createTempFile(file.getParent(), ".repository-manager-", ".tmp");
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            Files.write(temporary, updated);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            // Check again before replacement in case a writer does not participate in the lock protocol.
            if (!Arrays.equals(original, readBytes(file)))
                throw new IOException("El archivo cambió durante la edición. Actualiza los registros.");
            if (Files.isSymbolicLink(backup)) throw new IOException("La ruta de respaldo es un enlace simbólico.");
            Files.write(backup, original, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static FileLock acquireLock(FileChannel channel) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            FileLock lock = channel.tryLock();
            if (lock != null) return lock;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IOException("El archivo está ocupado por otro proceso. Vuelve a intentarlo.");
    }

    private static String required(Properties request, String key) {
        String value = request.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Falta el parámetro " + key + ".");
        return value;
    }
}
