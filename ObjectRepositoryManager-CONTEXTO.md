# Contexto de ObjectRepositoryManager

Fecha: 12 de noviembre de 2026.

## Proyecto

El usuario creó un módulo de NetBeans en:

`C:\Users\Jose\Documents\NetBeansProjects\ObjectRepositoryManager`

El espacio de trabajo de la conversación original es `D:\Desarrollos\NetBeansProjects\ZentroShop`. El módulo es un proyecto independiente; ZentroShop puede servir como proyecto de prueba.

## Objetivo

Crear un plugin que se instale una vez en NetBeans y se integre con los proyectos Java que el usuario abra. Debe detectar sus clases serializables y permitir gestionar archivos binarios con objetos de esas clases, sin añadir el plugin como dependencia de cada proyecto.

Los archivos objetivo contienen objetos serializados de Java; no se trata de gestionar los archivos compilados `.class`.

## Requisitos expresados por el usuario

- Integrarse en NetBeans al cargar un proyecto Java.
- Detectar automáticamente las clases del proyecto.
- Utilizar la implementación de `java.io.Serializable` como criterio para identificar clases candidatas al almacenamiento.
- Permitir gestionar los archivos binarios asociados a esas clases.

## Flujo propuesto

1. El usuario abre un proyecto Java en NetBeans.
2. El plugin identifica sus raíces de código fuente.
3. Analiza las clases y detecta cuáles implementan `Serializable`, también por herencia.
4. El menú contextual del proyecto ofrece «Gestionar archivos binarios».
5. El usuario selecciona una clase y crea o abre un archivo compatible.
6. Una ventana muestra los registros y permite agregar, editar, eliminar y guardar objetos.
7. El catálogo de clases se actualiza cuando cambia el proyecto.

## Arquitectura propuesta, pendiente de implementar

- Acción contextual: recibe el proyecto seleccionado y abre el gestor.
- Analizador de clases: utiliza las APIs de proyectos y de análisis de Java de NetBeans; la detección debe basarse en tipos, no en buscar texto.
- Modelo de metadatos: nombre de clase, campos, tipos y condiciones de edición.
- Ventana del gestor: selección de clase y archivo, tabla de registros y formulario.
- Proceso Java auxiliar: carga las clases compiladas y sus dependencias para crear objetos y ejecutar la lectura y escritura fuera del proceso del IDE.
- Gestor de persistencia: valida el formato y guarda cambios evitando pérdida de datos.

La detección puede trabajar sobre el código fuente. La manipulación de objetos necesita las clases compiladas y sus dependencias.

## Alcance sugerido para la primera versión

Estas decisiones se propusieron en la conversación, pero requieren validación al diseñar el formato:

- Cada archivo contiene un `ArrayList` de objetos de una clase seleccionada.
- El formulario admite inicialmente textos, números, booleanos y enumeraciones.
- Las clases editables tienen un constructor sin argumentos; esta sería una restricción del editor, no un requisito general de `Serializable`.
- Se excluyen del formulario automático los campos `static` y `transient`.
- Los archivos con otros formatos requieren un adaptador; no deben sobrescribirse suponiendo que son compatibles.
- Los objetos anidados, colecciones y serialización personalizada quedan pendientes de diseño.

## Consideraciones técnicas

- Implementar `Serializable` identifica una candidata, pero no garantiza que todo el grafo de objetos pueda serializarse.
- `Serializable` no define si un archivo contiene una lista, objetos consecutivos u otra estructura.
- Debe contemplarse `serialVersionUID` y la incompatibilidad entre versiones de una clase.
- La serialización personalizada (`writeObject`, `readObject`, entre otros mecanismos) puede diferir de los campos visibles.
- Deserializar puede ejecutar código de las clases. Un proceso auxiliar protege la estabilidad del IDE, pero no constituye por sí solo un aislamiento de seguridad.
- El soporte para proyectos Ant, Maven, Gradle y proyectos con varios módulos debe definirse y probarse por etapas.
- El análisis debe ejecutarse fuera del hilo de interfaz para mantener NetBeans responsivo.

## Próximo paso concreto

Inspeccionar el módulo existente y crear la acción «Gestionar archivos binarios» en el menú contextual de proyectos Java.

Criterios para completar esta primera etapa:

1. El módulo compila y se ejecuta en una instancia de prueba de NetBeans.
2. La acción aparece para un proyecto Java compatible.
3. La acción reconoce el proyecto sobre el que se invoca.
4. Abre una ventana inicial del gestor que identifica ese proyecto.

Después se implementará el análisis y la presentación de clases `Serializable`.

## Estado real y datos pendientes

- El usuario confirmó que ya creó el módulo.
- No se ha inspeccionado su contenido ni se ha implementado código del plugin en esta conversación.
- Los intentos de usar la herramienta de ejecución fallaron al iniciar el proceso, antes de poder leer los archivos.
- Faltan confirmar las versiones de NetBeans y del JDK, el sistema de construcción del módulo y su paquete base.
- Antes de modificar el módulo, leer sus instrucciones locales (`AGENTS.md`, si existe), revisar su estructura y conservar cualquier trabajo existente.
- La ruta del módulo está fuera de las raíces de escritura de la conversación original. Abrir esa carpeta como espacio de trabajo permitirá continuar con el proyecto correspondiente; no asumir acceso de escritura desde ZentroShop.

## Referencia

Tutoriales oficiales de módulos de Apache NetBeans: https://netbeans.apache.org/tutorial/main/tutorials/

## Instrucción para continuar

«Trabaja en ObjectRepositoryManager. Revisa este contexto y el módulo existente. Implementa primero la acción Gestionar archivos binarios para proyectos Java y una ventana inicial que reconozca el proyecto seleccionado. Luego continúa con la detección de clases Serializable utilizando las APIs de NetBeans.»
