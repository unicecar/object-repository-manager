# Estado de desarrollo: Object Repository Manager

Última actualización: 12 de noviembre de 2026.

## Objetivo

Módulo independiente de NetBeans que ofrece un visor CRUD para listas de objetos Java serializados pertenecientes al proyecto seleccionado. El menú esperado en proyectos Maven es **Object Repository Manager**.

## Funcionalidad implementada

- Acción contextual registrada en `Projects/Actions` y `Projects/org-netbeans-modules-maven/Actions`.
- Panel para detectar clases `Serializable`, abrir o crear archivos y consultar, buscar, agregar, editar y eliminar registros.
- Lectura y escritura en un proceso Java auxiliar. Al guardar comprueba revisiones e identificadores, crea `.bak` y usa reemplazo atómico.
- Compatibilidad comprobada con listas de objetos de `object-repository-2.0.0.jar`.
- Campos finales y relaciones complejas se muestran como solo lectura.

## Incidente recurrente del menú

El usuario informa que **Object Repository Manager desaparece del menú contextual al cambiar o reinstalar una versión**. La captura más reciente muestra el menú de un proyecto Maven sin la acción, aunque la ventana del gestor había abierto en una versión anterior.

Los registros del módulo compilan una referencia en `Projects/org-netbeans-modules-maven/Actions`, y `ManageBinaryFilesAction` está registrada como `ContextAwareAction`. Versiones recientes usan `lazy=true`. El empaquetado 1.1.4 terminó con `BUILD SUCCESS`. Esto confirma la generación del artefacto, pero no demuestra que la acción aparezca después de actualizar el módulo en una instancia real de NetBeans.

El historial confirma que la acción contextual desaparece en instalaciones recientes, aunque el `.nbm` contiene su ruta Maven. La versión 1.1.6 restaura el `lazy=false` anterior. Para dar al usuario un acceso persistente e independiente del menú contextual, la versión 1.2 añade **View → Object Repository Manager**, que permite seleccionar la carpeta raíz del proyecto Java.

## Regla para próximas versiones

**No entregar una versión como corrección del menú solo porque compile.** Para cambios que puedan afectar el registro de la acción, comprobar como mínimo:

1. El `.nbm` incluye `META-INF/generated-layer.xml` en el JAR del módulo.
2. La capa registra la acción en `Projects/org-netbeans-modules-maven/Actions` con el nombre correcto.
3. La identidad del módulo se mantiene estable (`co.edu.cecar.ObjectRepositoryManager`) y la especificación de versión aumenta.
4. Actualizar o desinstalar la versión previa, reiniciar NetBeans e instalar el nuevo `.nbm`.
5. En el NetBeans real, abrir un proyecto Maven con fuentes Java y confirmar que aparece **Object Repository Manager** en el menú del nodo principal y que abre el panel correspondiente.
6. Repetir tras una compilación limpia y una actualización para comprobar que el menú no desaparece.

Guardar una captura o registrar la versión que carga NetBeans junto con cada comprobación. Si no se dispone de una instancia real de NetBeans, informar claramente que la aparición del menú sigue sin verificarse.

## Artefacto disponible

La versión 1.2.0 incorpora el acceso en el menú **View**; comprobarlo en la instalación real de NetBeans después de actualizar.

## Siguiente paso

Reproducir la instalación y actualización en NetBeans. Comprobar el número de versión instalado, el estado del módulo en Plugins, la capa activa tras reiniciar y si NetBeans solicita actualizar la lista del menú al recargar el proyecto. Corregir la causa identificada, compilar una nueva versión menor y confirmar la acción en la instancia antes de entregarla.
