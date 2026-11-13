package co.edu.cecar.objectrepositorymanager;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.openide.filesystems.FileUtil;

/** Project classes and records. Application code runs only in the helper JVM. */
final class RepositoryManagerPanel extends JPanel {
    private final RepositoryClient client;
    private final File projectDirectory;
    private final DefaultMutableTreeNode navigationRoot = new DefaultMutableTreeNode("Clases y objetos");
    private final DefaultTreeModel navigationModel = new DefaultTreeModel(navigationRoot);
    private final JTree navigationTree = new JTree(navigationModel);
    private final java.util.Map<String, DefaultMutableTreeNode> classNodes = new java.util.LinkedHashMap<>();
    private final DefaultListModel<RepositoryFile> objectFiles = new DefaultListModel<>();
    private final JButton discover = new JButton("Actualizar clases");
    private final JButton open = new JButton("Abrir archivo…");
    private final JButton create = new JButton("Nuevo archivo…");
    private final JButton reload = new JButton("Recargar");
    private final JButton insert = new JButton("Agregar");
    private final JButton edit = new JButton("Editar");
    private final JButton delete = new JButton("Eliminar");
    private final JComboBox<FieldInfo> idField = new JComboBox<>();
    private final JTextField filter = new JTextField(20);
    private final JTextField fileLocation = new JTextField();
    private final JLabel classHeading = new JLabel("Selecciona una clase");
    private final JTextArea status = new JTextArea(2, 60);
    private final JLabel count = new JLabel("0 registros");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultTableModel rows = new DefaultTableModel() {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(rows);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(rows);
    private final List<FieldInfo> fields = new ArrayList<>();
    private Properties snapshot = new Properties();
    private String selectedClass;
    private File selectedFile;
    private boolean busy;
    private boolean closed;
    private boolean updatingClasses;
    private SwingWorker<Properties, Void> task;

    RepositoryManagerPanel(Project project) {
        this(project, new RepositoryClient(project), true);
    }

    RepositoryManagerPanel(Project project, RepositoryClient client, boolean autoDiscover) {
        super(new BorderLayout(10, 10));
        this.client = client;
        projectDirectory = FileUtil.toFile(project.getProjectDirectory());
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setPreferredSize(new Dimension(720, 420));
        setMinimumSize(new Dimension(540, 300));
        JPanel heading = new JPanel(new BorderLayout(6, 6));
        JLabel title = new JLabel(ProjectUtils.getInformation(project).getDisplayName());
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 18f));
        heading.add(title, BorderLayout.NORTH);
        heading.add(new JLabel("Explora tus clases y administra sus registros."), BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        JPanel navigation = new JPanel(new BorderLayout(6, 6));
        navigationTree.setRootVisible(false);
        navigationTree.setShowsRootHandles(true);
        navigationTree.getAccessibleContext().setAccessibleName("Clases y archivos de objetos");
        navigationTree.setRowHeight(28);
        navigation.setMinimumSize(new Dimension(150, 100));
        navigation.add(new JLabel("Clases y archivos"), BorderLayout.NORTH);
        navigation.add(new JScrollPane(navigationTree), BorderLayout.CENTER);
        navigation.add(discover, BorderLayout.SOUTH);
        JPanel records = new JPanel(new BorderLayout(6, 6));
        JPanel controls = new JPanel(new BorderLayout(4, 8));
        classHeading.setFont(classHeading.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        controls.add(classHeading, BorderLayout.NORTH);
        fileLocation.setEditable(false);
        fileLocation.getAccessibleContext().setAccessibleName("Archivo de objetos seleccionado");
        controls.add(fileLocation, BorderLayout.CENTER);
        JPanel fileActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        fileActions.add(open); fileActions.add(create); fileActions.add(reload);
        controls.add(fileActions, BorderLayout.SOUTH);
        records.add(controls, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(30, table.getRowHeight()));
        table.setShowVerticalLines(false);
        table.getTableHeader().setFont(table.getFont().deriveFont(java.awt.Font.BOLD));
        table.getAccessibleContext().setAccessibleName("Registros del archivo");
        records.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel recordControls = new JPanel(new BorderLayout(4, 8));
        JPanel search = new JPanel(new BorderLayout(8, 0));
        JLabel searchLabel = new JLabel("Buscar:"); searchLabel.setLabelFor(filter);
        search.add(searchLabel, BorderLayout.WEST);
        search.add(filter, BorderLayout.CENTER);
        search.add(count, BorderLayout.EAST);
        recordControls.add(search, BorderLayout.NORTH);
        JPanel mutations = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel identifierLabel = new JLabel("Identificador:"); identifierLabel.setLabelFor(idField);
        idField.setPreferredSize(new Dimension(155, 27));
        idField.setToolTipText("Campo usado por tu repositorio como identificador único");
        mutations.add(identifierLabel); mutations.add(idField);
        mutations.add(insert); mutations.add(edit); mutations.add(delete);
        recordControls.add(mutations, BorderLayout.CENTER);
        recordControls.add(new JLabel("Agregar, editar y eliminar guardan el archivo al confirmar."), BorderLayout.SOUTH);
        records.add(recordControls, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigation, records);
        split.setResizeWeight(0.25); split.setDividerLocation(255);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        add(split, BorderLayout.CENTER);
        status.setEditable(false); status.setLineWrap(true); status.setWrapStyleWord(true);
        status.setOpaque(false); status.setFont(new JLabel().getFont());
        status.getAccessibleContext().setAccessibleName("Estado del gestor");
        progress.setPreferredSize(new Dimension(95, 16)); progress.setVisible(false);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.add(status, BorderLayout.CENTER); footer.add(progress, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        discover.addActionListener(event -> discoverClasses());
        navigationTree.addTreeSelectionListener(event -> {
            if (updatingClasses || busy) return;
            Object selected = navigationTree.getLastSelectedPathComponent();
            if (selected instanceof DefaultMutableTreeNode node) {
                Object value = node.getUserObject();
                if (value instanceof RepositoryFile file) openListedObject(file);
                else if (value instanceof String type) {
                    selectedClass = type;
                    selectClass();
                }
            }
        });
        open.addActionListener(event -> chooseFile(false));
        create.addActionListener(event -> chooseFile(true));
        reload.addActionListener(event -> run(request("open"), "Leyendo registros…", result -> {
            applySnapshot(result); status.setText("Archivo recargado.");
        }));
        insert.addActionListener(event -> editRecord(true));
        edit.addActionListener(event -> editRecord(false));
        delete.addActionListener(event -> deleteRecord());
        table.getSelectionModel().addListSelectionListener(event -> updateEnabled());
        idField.addActionListener(event -> updateEnabled());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && edit.isEnabled()) editRecord(false);
            }
        });
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filterRows(); }
            @Override public void removeUpdate(DocumentEvent event) { filterRows(); }
            @Override public void changedUpdate(DocumentEvent event) { filterRows(); }
        });
        updateEnabled();
        if (autoDiscover) SwingUtilities.invokeLater(this::discoverClasses);
    }

    private void discoverClasses() {
        if (closed || busy) return;
        run(request("discover"), "Buscando clases compiladas…", result -> {
            updatingClasses = true;
            navigationRoot.removeAllChildren();
            classNodes.clear();
            resetSelection();
            for (int i = 0; i < number(result, "class.count"); i++) {
                String type = result.getProperty("class." + i);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(type);
                classNodes.put(type, node);
                navigationRoot.add(node);
            }
            for (int i = 0; i < objectFiles.size(); i++) addObjectNode(objectFiles.get(i));
            for (int i = 0; i < number(result, "object.count"); i++) {
                File file = new File(result.getProperty("object." + i + ".file"));
                String type = result.getProperty("object." + i + ".type");
                rememberObject(file, type);
            }
            navigationModel.reload();
            navigationTree.clearSelection();
            updatingClasses = false;
            String warnings = result.getProperty("warnings", "");
            status.setText(classNodes.isEmpty()
                    ? "No se encontraron clases Serializable compiladas. Ejecuta Clean and Build en el proyecto y actualiza la lista. " + warnings
                    : classNodes.size() + " clases detectadas. Selecciona una clase o uno de sus archivos. " + warnings);
        });
    }

    private void selectClass() {
        resetSelection();
        if (selectedClass == null) return;
        classHeading.setText(selectedClass); classHeading.setToolTipText(selectedClass);
        run(request("describe"), "Leyendo estructura de la clase…", result -> {
            applySnapshot(result);
            status.setText("Selecciona Abrir archivo o Nuevo archivo. El identificador debe coincidir con el usado por tu repositorio.");
        });
    }

    private void openListedObject(RepositoryFile entry) {
        updatingClasses = true;
        DefaultMutableTreeNode typeNode = classNodes.get(entry.type);
        if (typeNode != null) navigationTree.setSelectionPath(new TreePath(typeNode.getPath()));
        updatingClasses = false;
        selectedClass = entry.type;
        resetRecordView();
        classHeading.setText(entry.type);
        classHeading.setToolTipText(entry.type);
        run(request("describe"), "Leyendo estructura de la clase…", schema -> {
            applySnapshot(schema);
            selectedFile = entry.file;
            fileLocation.setText(entry.file.getPath());
            fileLocation.setToolTipText(entry.file.getPath());
            run(request("open"), "Abriendo " + entry.file.getName() + "…", result -> {
                applySnapshot(result);
                status.setText("Archivo restaurado desde la lista Objetos.");
            });
        });
    }

    private void resetRecordView() {
        selectedFile = null;
        snapshot = new Properties();
        fields.clear();
        idField.removeAllItems();
        rows.setDataVector(new Object[0][0], new Object[0]);
        fileLocation.setText("");
        filter.setText("");
        updateCount();
        updateEnabled();
    }

    private void resetSelection() {
        selectedClass = null; selectedFile = null; snapshot = new Properties();
        fields.clear(); idField.removeAllItems();
        rows.setDataVector(new Object[0][0], new Object[0]);
        classHeading.setText("Selecciona una clase"); fileLocation.setText(""); filter.setText("");
        updateCount(); updateEnabled();
    }

    private void chooseFile(boolean newFile) {
        JFileChooser chooser = new JFileChooser(selectedFile == null ? projectDirectory : selectedFile.getParentFile());
        chooser.setDialogTitle(newFile ? "Crear archivo de objetos" : "Abrir archivo de objetos");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (newFile) chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), selectedClass.substring(selectedClass.lastIndexOf('.') + 1) + ".bin"));
        int choice = newFile ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
        if (choice != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile().getAbsoluteFile();
        if (newFile && file.exists()) { error("El archivo ya existe. Usa Abrir archivo o elige un nombre nuevo."); return; }
        if (!newFile && !file.isFile()) { error("Selecciona un archivo existente."); return; }
        Properties request = request(newFile ? "create" : "open");
        request.setProperty("file", file.getPath());
        if (newFile) request.setProperty("revision", "MISSING");
        run(request, newFile ? "Creando archivo…" : "Leyendo registros…", result -> {
            selectedFile = file; fileLocation.setText(file.getPath()); fileLocation.setToolTipText(file.getPath());
            applySnapshot(result);
            rememberObject(file, selectedClass);
            status.setText(newFile ? "Archivo creado. Puedes agregar registros." : "Archivo abierto. Selecciona un registro para editarlo o eliminarlo.");
        });
    }

    private void rememberObject(File file, String type) {
        for (int i = 0; i < objectFiles.size(); i++) {
            RepositoryFile entry = objectFiles.get(i);
            if (entry.file.equals(file) && entry.type.equals(type)) {
                selectObjectNode(entry);
                return;
            }
        }
        RepositoryFile entry = new RepositoryFile(file, type);
        objectFiles.addElement(entry);
        addObjectNode(entry);
        navigationModel.reload();
        selectObjectNode(entry);
    }

    private void addObjectNode(RepositoryFile entry) {
        DefaultMutableTreeNode parent = classNodes.get(entry.type);
        if (parent != null) parent.add(new DefaultMutableTreeNode(entry));
    }

    private void selectObjectNode(RepositoryFile entry) {
        DefaultMutableTreeNode parent = classNodes.get(entry.type);
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (child.getUserObject() == entry) {
                updatingClasses = true;
                navigationTree.setSelectionPath(new TreePath(child.getPath()));
                updatingClasses = false;
                return;
            }
        }
    }

    private void applySnapshot(Properties result) {
        String previousId = idField.getSelectedItem() instanceof FieldInfo field ? field.key : null;
        snapshot = result; fields.clear(); idField.removeAllItems();
        Object[] columns = new Object[number(result, "field.count") + 1]; columns[0] = "#";
        FieldInfo preferred = null;
        for (int i = 0; i < columns.length - 1; i++) {
            FieldInfo field = new FieldInfo(i, result); fields.add(field); columns[i + 1] = field.name;
            if (field.identifier) idField.addItem(field);
            if (field.identifier && field.key.equals(previousId)) preferred = field;
            else if (field.identifier && previousId == null && (field.name.equalsIgnoreCase("id") || field.name.equalsIgnoreCase("codigo") || field.name.equalsIgnoreCase("sku") || field.name.equalsIgnoreCase("numero"))) preferred = field;
        }
        idField.setSelectedItem(preferred);
        Object[][] data = new Object[number(result, "row.count")][columns.length];
        for (int r = 0; r < data.length; r++) {
            data[r][0] = r + 1;
            for (int f = 0; f < fields.size(); f++) data[r][f + 1] = isNull(r, f) ? "<null>" : result.getProperty("cell." + r + "." + f, "");
        }
        rows.setDataVector(data, columns);
        for (int i = 0; i < columns.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 45 : 150);
        filterRows(); updateEnabled();
    }

    private void editRecord(boolean adding) {
        int row = adding ? -1 : selectedRow();
        if (!adding && row < 0) return;
        JPanel body = new JPanel(new BorderLayout(5, 8));
        JPanel form = new JPanel(new GridBagLayout());
        List<FormInput> inputs = new ArrayList<>();
        JComboBox<ConstructorInfo> constructors = new JComboBox<>();
        if (adding) {
            for (int i = 0; i < number(snapshot, "ctor.count"); i++) constructors.addItem(new ConstructorInfo(i, snapshot));
            JPanel constructorHeader = new JPanel(new BorderLayout(5, 5));
            constructorHeader.add(new JLabel("Constructor:"), BorderLayout.WEST);
            constructorHeader.add(constructors, BorderLayout.CENTER);
            body.add(constructorHeader, BorderLayout.NORTH);
        }
        Runnable populate = () -> {
            inputs.clear(); form.removeAll();
            ConstructorInfo constructor = adding ? (ConstructorInfo) constructors.getSelectedItem() : null;
            if (constructor != null && constructor.parameters > 0) {
                for (int p = 0; p < constructor.parameters; p++) {
                    String prefix = "ctor." + constructor.index + ".param." + p + ".";
                    inputs.add(addInput(form, p, snapshot.getProperty(prefix + "name", "arg" + p),
                            snapshot.getProperty(prefix + "type"), true,
                            Boolean.parseBoolean(snapshot.getProperty(prefix + "nullable", "false")),
                            snapshot.getProperty(prefix + "enum", ""), "", false));
                    if (Boolean.parseBoolean(snapshot.getProperty(prefix + "reference", "false"))) {
                        FormInput input = inputs.get(inputs.size() - 1);
                        ((JTextField) input.editor).setEditable(false);
                        JButton choose = new JButton("Seleccionar objeto…");
                        String referenceType = snapshot.getProperty(prefix + "type");
                        choose.addActionListener(e -> chooseReference(input, referenceType));
                        GridBagConstraints position = new GridBagConstraints();
                        position.gridx = 3; position.gridy = p; position.insets = new Insets(5, 5, 5, 5);
                        form.add(choose, position);
                    }
                }
            } else {
                for (FieldInfo field : fields) {
                    String current = adding ? "" : snapshot.getProperty("cell." + row + "." + field.index, "");
                    inputs.add(addInput(form, field.index, field.name, field.type, field.editable, field.nullable,
                            field.enums, current, !adding && isNull(row, field.index)));
                }
            }
            form.revalidate(); form.repaint();
        };
        populate.run();
        constructors.addActionListener(event -> populate.run());
        JScrollPane scroll = new JScrollPane(form);
        scroll.setPreferredSize(new Dimension(650, 350));
        body.add(scroll, BorderLayout.CENTER);
        body.add(new JLabel("Confirma para guardar el registro en el archivo."), BorderLayout.SOUTH);
        int result = JOptionPane.showConfirmDialog(this, body, adding ? "Agregar registro" : "Editar registro",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        Properties request = request(adding ? "insert" : "update");
        if (!adding) request.setProperty("row", Integer.toString(row));
        ConstructorInfo constructor = adding ? (ConstructorInfo) constructors.getSelectedItem() : null;
        if (constructor != null) request.setProperty("constructor", constructor.key);
        boolean arguments = constructor != null && constructor.parameters > 0;
        for (FormInput input : inputs) {
            if (!input.editable) continue;
            if (arguments && input.editor.getClientProperty("referenceFile") != null) {
                request.setProperty("argFile." + input.index, (String) input.editor.getClientProperty("referenceFile"));
                request.setProperty("argRevision." + input.index, (String) input.editor.getClientProperty("referenceRevision"));
                request.setProperty("argRow." + input.index, (String) input.editor.getClientProperty("referenceRow"));
            }
            request.setProperty((arguments ? "argNull." : "null.") + input.index, Boolean.toString(input.nullValue.isSelected()));
            request.setProperty((arguments ? "arg." : "value.") + input.index, input.editor instanceof JTextField text
                    ? text.getText() : String.valueOf(((JComboBox<?>) input.editor).getSelectedItem()));
        }
        run(request, "Guardando registro…", response -> {
            applySnapshot(response); status.setText(adding ? "Registro agregado y guardado." : "Registro actualizado y guardado.");
        });
    }

    private void chooseReference(FormInput input, String type) {
        JFileChooser chooser = new JFileChooser(new File(projectDirectory, "data"));
        chooser.setDialogTitle("Seleccionar archivo de " + type);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        String path = chooser.getSelectedFile().getAbsolutePath();
        Properties query = new Properties();
        query.setProperty("op", "open"); query.setProperty("type", type); query.setProperty("file", path);
        run(query, "Leyendo objetos relacionados…", result -> {
            int size = number(result, "row.count");
            if (size == 0) { JOptionPane.showMessageDialog(this, "El archivo no contiene registros."); return; }
            String[] options = new String[size];
            for (int r = 0; r < size; r++) {
                StringBuilder label = new StringBuilder("#" + (r + 1));
                for (int f = 0; f < number(result, "field.count"); f++)
                    label.append(" | ").append(result.getProperty("field." + f + ".name")).append(": ")
                            .append(result.getProperty("cell." + r + "." + f, "null"));
                options[r] = label.toString();
            }
            Object selection = JOptionPane.showInputDialog(this, "Selecciona el objeto", "Objeto relacionado",
                    JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (selection == null) return;
            int row = java.util.Arrays.asList(options).indexOf(selection);
            input.editor.putClientProperty("referenceFile", path);
            input.editor.putClientProperty("referenceRevision", result.getProperty("revision"));
            input.editor.putClientProperty("referenceRow", Integer.toString(row));
            ((JTextField) input.editor).setText(selection.toString());
            input.nullValue.setSelected(false); input.editor.setEnabled(true);
        });
    }

    private FormInput addInput(JPanel form, int index, String name, String type, boolean editable,
            boolean nullable, String enums, String current, boolean isNull) {
        JComponent editor;
        if (!enums.isEmpty()) {
            JComboBox<String> choices = new JComboBox<>(enums.split(",", -1));
            if (!current.isEmpty()) choices.setSelectedItem(current);
            editor = choices;
        } else if (type.equals("boolean") || type.equals("java.lang.Boolean")) {
            JComboBox<String> choices = new JComboBox<>(new String[]{"false", "true"});
            choices.setSelectedItem(current.isEmpty() ? "false" : current); editor = choices;
        } else {
            editor = new JTextField(current, 26);
        }
        JCheckBox nullValue = new JCheckBox("null");
        nullValue.setVisible(nullable); nullValue.setSelected(isNull); nullValue.setEnabled(editable);
        editor.setEnabled(editable && !isNull);
        nullValue.addActionListener(event -> editor.setEnabled(editable && !nullValue.isSelected()));
        JLabel label = new JLabel(name + " : " + type.substring(type.lastIndexOf('.') + 1) + (editable ? "" : " (solo lectura)"));
        label.setLabelFor(editor); label.setToolTipText(type); editor.setToolTipText(type);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = index; c.insets = new Insets(5, 5, 5, 5); c.anchor = GridBagConstraints.WEST;
        form.add(label, c); c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(editor, c); c.gridx = 2; c.weightx = 0; form.add(nullValue, c);
        return new FormInput(index, editable, editor, nullValue);
    }

    private void deleteRecord() {
        int row = selectedRow(); if (row < 0) return;
        int confirm = JOptionPane.showConfirmDialog(this,
                "¿Eliminar el registro " + (row + 1) + " de " + selectedFile.getName() + "?\nEl cambio se guardará en el archivo.",
                "Eliminar registro", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        Properties request = request("delete"); request.setProperty("row", Integer.toString(row));
        run(request, "Eliminando registro…", result -> {
            applySnapshot(result); status.setText("Registro eliminado y archivo guardado.");
        });
    }

    private Properties request(String operation) {
        Properties request = new Properties(); request.setProperty("op", operation);
        if (selectedClass != null) request.setProperty("type", selectedClass);
        if (selectedFile != null) request.setProperty("file", selectedFile.getPath());
        if (snapshot.containsKey("revision")) request.setProperty("revision", snapshot.getProperty("revision"));
        if (idField.getSelectedItem() instanceof FieldInfo field) request.setProperty("idField", field.key);
        return request;
    }

    private void run(Properties request, String message, Consumer<Properties> success) {
        if (closed || busy) return;
        busy = true; status.setText(message); updateEnabled();
        task = new SwingWorker<>() {
            @Override protected Properties doInBackground() throws Exception { return client.execute(request); }
            @Override protected void done() {
                if (closed) return;
                busy = false;
                try { success.accept(get()); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); error("Operación interrumpida."); }
                catch (CancellationException exception) { status.setText("Operación cancelada."); }
                catch (ExecutionException exception) {
                    Throwable cause = exception.getCause(); error(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                } finally { updateEnabled(); }
            }
        };
        task.execute();
    }

    private void updateEnabled() {
        boolean idle = !busy && !closed;
        boolean described = selectedClass != null && snapshot.containsKey("field.count");
        boolean loaded = described && selectedFile != null;
        boolean hasId = idField.getSelectedItem() != null;
        discover.setEnabled(idle); navigationTree.setEnabled(idle);
        open.setEnabled(idle && described); create.setEnabled(idle && described); reload.setEnabled(idle && loaded);
        insert.setEnabled(idle && loaded && hasId && Boolean.parseBoolean(snapshot.getProperty("creatable", "false")));
        edit.setEnabled(idle && loaded && hasId && selectedRow() >= 0 && fields.stream().anyMatch(field -> field.editable));
        delete.setEnabled(idle && loaded && hasId && selectedRow() >= 0);
        idField.setEnabled(idle && described); filter.setEnabled(idle && loaded); table.setEnabled(idle && loaded);
        progress.setVisible(busy); progress.setIndeterminate(busy);
        insert.setToolTipText(Boolean.parseBoolean(snapshot.getProperty("creatable", "false"))
                ? "Crear y guardar un registro" : "La clase necesita un constructor compatible con el editor para crear registros");
    }

    private int selectedRow() { return table.getSelectedRow() < 0 ? -1 : table.convertRowIndexToModel(table.getSelectedRow()); }
    private void filterRows() {
        sorter.setRowFilter(filter.getText().isEmpty() ? null : RowFilter.regexFilter("(?iu)" + Pattern.quote(filter.getText())));
        updateCount();
    }
    private void updateCount() { count.setText(table.getRowCount() + " / " + rows.getRowCount() + " registros"); }
    private boolean isNull(int row, int field) { return Boolean.parseBoolean(snapshot.getProperty("cell." + row + "." + field + ".null", "false")); }
    private static int number(Properties data, String key) { return Integer.parseInt(data.getProperty(key, "0")); }
    private void error(String message) {
        status.setText(message); JOptionPane.showMessageDialog(this, message, "Object Repository Manager", JOptionPane.ERROR_MESSAGE);
    }
    void close() { closed = true; if (task != null) task.cancel(true); client.close(); }

    private static final class FieldInfo {
        final int index;
        final String key, name, type, enums;
        final boolean editable, nullable, identifier;
        FieldInfo(int index, Properties data) {
            this.index = index; String prefix = "field." + index + ".";
            key = data.getProperty(prefix + "key"); name = data.getProperty(prefix + "name"); type = data.getProperty(prefix + "type");
            editable = Boolean.parseBoolean(data.getProperty(prefix + "editable", "false"));
            nullable = Boolean.parseBoolean(data.getProperty(prefix + "nullable", "false"));
            identifier = Boolean.parseBoolean(data.getProperty(prefix + "identifier", "false"));
            enums = data.getProperty(prefix + "enum", "");
        }
        @Override public String toString() { return name; }
    }
    private static final class ConstructorInfo {
        final int index, parameters;
        final String key, label;
        ConstructorInfo(int index, Properties data) {
            this.index = index; String prefix = "ctor." + index + ".";
            key = data.getProperty(prefix + "key"); label = data.getProperty(prefix + "label");
            parameters = number(data, prefix + "param.count");
        }
        @Override public String toString() { return label; }
    }
    private record FormInput(int index, boolean editable, JComponent editor, JCheckBox nullValue) { }
    private record RepositoryFile(File file, String type) {
        @Override public String toString() {
            return file.getName() + " — " + type.substring(type.lastIndexOf('.') + 1);
        }
    }
}
