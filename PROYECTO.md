# Object Repository Manager

Plugin para Apache NetBeans que permite explorar clases Java serializables y administrar archivos que contienen listas serializadas de objetos.

## Información

- **Versión:** 1.0
- **Identificador Maven:** `co.edu.cecar:ObjectRepositoryManager`
- **Formato:** módulo instalable de NetBeans (`.nbm`)
- **NetBeans:** RELEASE280
- **Java:** código compilado para Java 17
- **Construcción:** Maven

## Funciones

- Detecta clases compiladas que implementan `Serializable` y archivos `.bin` compatibles en la carpeta `data` del proyecto.
- Ofrece una vista acoplada en NetBeans, accesible desde el menú contextual del proyecto y desde **View → Object Repository Manager**.
- Abre y recuerda archivos de objetos en la lista del panel.
- Muestra registros en una tabla con búsqueda y selección de un campo identificador.
- Permite agregar, editar y eliminar registros.
- Admite constructores con argumentos simples y enumeraciones. Para parámetros compuestos, permite elegir un objeto de un archivo serializado compatible; recuerda el último archivo seleccionado por proyecto y tipo.
- Ejecuta la carga de clases y las operaciones de serialización en un proceso Java auxiliar.
- Conserva una copia de respaldo antes de guardar cambios y rechaza escrituras si el archivo cambió desde su lectura.

## Requisitos de los archivos

El formato administrado es una lista (`List`) Java serializada con `ObjectOutputStream`, cuyos elementos son de la clase elegida. El archivo debe ser compatible con las clases compiladas actualmente y sus dependencias.

El panel limita la lectura a archivos de 64 MiB y 10 000 registros. La serialización personalizada, los formatos que no contienen una lista y las relaciones que no puedan resolverse desde otro archivo compatible quedan fuera del formato genérico.

## Compilar e instalar

Desde NetBeans, usa **Clean and Build** en el proyecto. Maven genera el instalador en:

```text
target/nbm/ObjectRepositoryManager-1.0.nbm
```

Para instalarlo, abre **Tools → Plugins → Downloaded**, agrega el `.nbm`, instala el plugin y reinicia NetBeans si lo solicita. Si ya tienes instalada una versión anterior con el mismo número `1.0`, desinstálala antes de instalar la actualización.

También puedes compilar desde PowerShell con Maven y JDK 17 o posterior:

```powershell
mvn clean package
```

## Uso

1. Ejecuta **Clean and Build** en el proyecto Java que contiene las entidades.
2. Abre el gestor desde el menú contextual del proyecto o desde **View → Object Repository Manager**.
3. Pulsa **Actualizar clases**. Los archivos `.bin` detectados en `data` aparecerán debajo de su clase.
4. Selecciona una clase o archivo y usa los controles del panel para abrir, consultar y gestionar sus registros.
5. Al crear registros con parámetros compuestos, selecciona el objeto relacionado desde el archivo compatible. El gestor recuerda esa ruta para el proyecto y tipo de objeto.

## Estructura del código

- `RepositoryManagerTopComponent`: ventana acoplada de NetBeans.
- `RepositoryManagerPanel`: árbol, tabla, búsqueda y formularios CRUD.
- `ManageBinaryFilesAction`: acción contextual del proyecto.
- `OpenRepositoryManagerAction`: acceso desde el menú View y selección de proyecto.
- `RepositoryClient`: comunicación entre el IDE y el proceso auxiliar.
- `worker/RepositoryWorker`: descubrimiento, lectura, validación y escritura de objetos serializados.

## Persistencia y limitaciones

Las clases de la aplicación se cargan en el proceso auxiliar, no dentro de la interfaz de NetBeans. Esto reduce el riesgo de que una operación bloquee el IDE, pero no convierte la deserialización en un entorno seguro para archivos o clases no confiables.

Las validaciones automáticas de campos y las reglas de negocio que existan en servicios o repositorios de la aplicación no se ejecutan al editar campos directamente. Los constructores públicos sí se invocan cuando se crea un registro.
