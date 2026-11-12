# Object Repository Manager

Gestor CRUD de archivos de objetos serializados integrado en NetBeans. La versión 1.1 incorpora un catálogo de clases, tabla de registros con búsqueda y formularios para crear, editar y eliminar registros.

## Instalación y uso

1. Instalar `target/nbm/ObjectRepositoryManager-1.3.6-SNAPSHOT.nbm` desde **Herramientas → Plugins → Descargados → Agregar plugins**. Reiniciar NetBeans si lo solicita.
2. Ejecutar **Clean and Build** en el proyecto Java que contiene las entidades.
3. Abrir **View → Object Repository Manager** y elegir la carpeta raíz del proyecto Java; el menú contextual del proyecto también ofrece un acceso directo.
4. En el árbol **Clases / entidades y objetos**, seleccionar una clase o uno de los archivos abiertos debajo de ella. **Actualizar clases** vuelve a consultar las salidas compiladas del proyecto.
5. Usar **Abrir archivo** para una lista serializada existente o crear un archivo nuevo.
6. Elegir el campo identificador que utiliza el repositorio de la aplicación (por ejemplo, `sku` para `Producto` en ZentroShop).
7. Consultar y buscar registros; **Nuevo**, **Editar** y **Eliminar** guardan cada operación confirmada en el archivo. Eliminar requiere confirmación.

La herramienta trabaja con los archivos binarios de datos, no con los `.class` ni con una base SQL. El archivo debe contener una única `List` serializada mediante `ObjectOutputStream`, como la biblioteca `object-repository-2.0.0.jar` examinada. Una lista de otra clase o un formato diferente se rechaza sin sobrescribirlo.

## Formularios y compatibilidad

- Catálogo de clases compiladas que implementan `Serializable`, también por herencia; excluye componentes Swing y excepciones. Las salidas del proyecto y sus dependencias se consultan mediante las APIs de NetBeans.
- Campos heredados incluidos; campos `static`, `transient` y sintéticos excluidos.
- Edición de textos, números, booleanos, caracteres, enumeraciones, `BigDecimal`, `BigInteger`, UUID y fechas `LocalDate`, `LocalDateTime` e `Instant` en formato ISO.
- Los campos `final` y los tipos complejos se muestran como solo lectura. Los valores complejos existentes se conservan al editar otros campos.
- Para crear registros se ofrecen constructores públicos con parámetros de tipos admitidos. Un constructor sin argumentos usa un formulario de campos cuando todos son editables. Los constructores con argumentos reciben los valores del formulario y mantienen sus validaciones y valores calculados.
- Si la clase se compiló sin `-parameters`, Java puede mostrar `arg0`, `arg1`, etc. en el formulario del constructor, junto al tipo y el orden de cada parámetro. Compilar con nombres de parámetros mejora esas etiquetas.
- Al editar se utiliza el setter público correspondiente cuando existe; en otro caso se asigna el campo mutable directamente. Esto no reproduce las reglas de una capa de servicios de la aplicación.
- En ZentroShop, `Producto(String nombre, double precio, int stock)` permite crear productos con SKU generado por la clase. `Venta` contiene una referencia a `Producto` y campos finales: esta versión permite consultarla y eliminar registros, pero no crear ventas ni editar esas relaciones.
- Clases compiladas con un JDK posterior al que ejecuta NetBeans necesitan que el IDE use un JDK compatible. La herramienta no compila automáticamente el proyecto ni recarga fuentes sin compilar.
- Abrir el módulo Java concreto en proyectos Maven con varios módulos. Los formatos de persistencia personalizados, la edición de relaciones y los registros inmutables no están cubiertos por el formulario genérico.

## Persistencia

La deserialización, los constructores y los setters se ejecutan en un proceso Java auxiliar. El proceso usa el JDK de NetBeans, con límite de tiempo y memoria; no es un aislamiento de seguridad para código no confiable.

Cada modificación verifica la revisión del archivo, toma el bloqueo hermano `archivo.lock`, valida identificadores no nulos y únicos, escribe un respaldo `archivo.bak` de la versión anterior y reemplaza el archivo mediante movimiento atómico. El respaldo conserva la versión inmediatamente anterior a la última operación. Si el sistema de archivos no permite movimiento atómico, la operación falla y conserva el archivo original. Actualizar registros permite recuperar cambios realizados por otra aplicación.

El visor limita los archivos a 64 MiB y 10 000 registros. Hay límites adicionales de profundidad y referencias durante la deserialización. Los datos y metadatos viajan entre el proceso auxiliar y el IDE como texto en archivos temporales, que se eliminan al finalizar la operación.

## Compilación

Requiere Maven y JDK 17 o posterior; utiliza APIs de NetBeans RELEASE280.

```powershell
mvn clean package
```

La configuración activa el procesamiento de anotaciones para generar las etiquetas y el registro de acciones, incluso al compilar con JDK recientes.

Referencias: [BinaryForSourceQuery](https://bits.netbeans.org/29/javadoc/org-netbeans-api-java-classpath/org/netbeans/api/java/queries/BinaryForSourceQuery.html), [acciones de proyecto](https://netbeans.apache.org/wiki/main/netbeansdevelopperfaq/DevFaqActionAddProjectTypePopUp/).

## Verificaciones ejecutables

Las comprobaciones son programas Java independientes de JUnit y se ejecutan después de `mvn test-compile`:

```powershell
./scripts/verify.ps1 -Java 'java' -MavenRepository '.maven-repository'
```

Indicar la carpeta de repositorio Maven que se haya utilizado para compilar. El parámetro opcional `-ObjectRepositoryJar` permite probar compatibilidad bidireccional con el JAR de la biblioteca original. La suite cubre CRUD real en procesos separados, protección de archivos ante errores, revisión obsoleta, respaldos, constructores y setters, rutas Unicode, búsqueda y selección en la tabla, y cierre del panel. Genera una imagen del panel con datos de prueba en `target/crud-panel.png`.

`ZentroShopCompatibilityCheck` es una prueba adicional opcional con las clases de ZentroShop en el classpath y la ruta de `product.bin` como argumento. Copia el archivo a una carpeta temporal antes de probar; nunca edita el original.
