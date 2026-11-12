package co.edu.cecar.objectrepositorymanager;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.TableRowSorter;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectInformation;
import org.netbeans.spi.project.ProjectInformationProvider;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.LocalFileSystem;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;

/** Standalone, headless interaction checks and a deterministic UI preview. */
public final class RepositoryPanelCheck {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("org.openide.util.Lookup", EmptyLookup.class.getName());
        Path projectPath = Files.createDirectories(Path.of("target", "ui-project").toAbsolutePath());
        LocalFileSystem filesystem = new LocalFileSystem();
        filesystem.setRootDirectory(projectPath.toFile());
        Project project = new DemoProject(filesystem.getRoot());
        BlockingClient client = new BlockingClient(project);
        RepositoryManagerPanel[] holder = new RepositoryManagerPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                RepositoryManagerPanel panel = new RepositoryManagerPanel(project, client, false);
                holder[0] = panel;
                check(!button(panel, "insert").isEnabled(), "Adding is disabled without a class/file");
                set(panel, "selectedClass", "co.edu.cecar.zentroshop.model.Producto");
                set(panel, "selectedFile", projectPath.resolve("productos.bin").toFile());
                ((JLabel) get(panel, "classHeading")).setText("co.edu.cecar.zentroshop.model.Producto");
                ((JTextField) get(panel, "fileLocation")).setText(projectPath.resolve("productos.bin").toString());
                ((JTextArea) get(panel, "status")).setText("Archivo abierto. Selecciona un registro para editarlo o eliminarlo.");
                set(panel, "updatingClasses", true);
                @SuppressWarnings("unchecked") DefaultListModel<String> classes = (DefaultListModel<String>) get(panel, "classes");
                classes.addElement("co.edu.cecar.zentroshop.model.Producto");
                classes.addElement("co.edu.cecar.zentroshop.model.Cliente");
                ((JList<?>) get(panel, "classList")).setSelectedIndex(0);
                set(panel, "updatingClasses", false);
                call(panel, "applySnapshot", new Class<?>[]{Properties.class}, fixture());

                JTable table = (JTable) get(panel, "table");
                // Normally JTable.addNotify installs its header when the IDE
                // displays the window. This headless preview has no peer.
                ((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table))
                        .setColumnHeaderView(table.getTableHeader());
                check(table.getRowCount() == 3, "All records appear");
                check(button(panel, "insert").isEnabled(), "Parameterized entity supports insert");
                check(!button(panel, "delete").isEnabled(), "Delete requires row selection");
                JComboBox<?> id = (JComboBox<?>) get(panel, "idField");
                check("sku".equals(String.valueOf(id.getSelectedItem())), "Final SKU selected as identifier");
                check(!table.isCellEditable(0, 1), "Final ID cannot be edited directly in table");

                @SuppressWarnings("unchecked") TableRowSorter<?> sorter = (TableRowSorter<?>) get(panel, "sorter");
                sorter.setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.ASCENDING)));
                table.setRowSelectionInterval(0, 0);
                check(((Integer) call(panel, "selectedRow", new Class<?>[0])) == 1,
                        "Sorted selection resolves to original record index");
                check(button(panel, "edit").isEnabled() && button(panel, "delete").isEnabled(),
                        "Selected record supports edit/delete");

                JTextField filter = (JTextField) get(panel, "filter");
                filter.setText("mouse");
                check(table.getRowCount() == 1, "Search filters case-insensitively");
                table.setRowSelectionInterval(0, 0);
                check(((Integer) call(panel, "selectedRow", new Class<?>[0])) == 0,
                        "Filtered selection resolves to original record index");
                filter.setText("[");
                check(table.getRowCount() == 0, "Search treats regex syntax literally");
                filter.setText("");
                check(table.getRowCount() == 3, "Clearing filter restores records");
                table.setRowSelectionInterval(0, 0);

                Object readOnlyInput = call(panel, "addInput",
                        new Class<?>[]{JPanel.class, int.class, String.class, String.class,
                            boolean.class, boolean.class, String.class, String.class, boolean.class},
                        new JPanel(), 0, "sku", "java.lang.String", false, true, "", "SKU-002", false);
                check(!((JComponent) get(readOnlyInput, "editor")).isEnabled(), "Final ID editor is read-only");
                check(!((JCheckBox) get(readOnlyInput, "nullValue")).isEnabled(), "Final ID null flag is read-only");

                panel.setSize(1080, 640);
                layout(panel);
                BufferedImage image = new BufferedImage(1080, 640, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                panel.printAll(graphics);
                graphics.dispose();
                ImageIO.write(image, "png", new File("target/crud-panel.png"));

                Properties request = new Properties(); request.setProperty("op", "open");
                call(panel, "run", new Class<?>[]{Properties.class, String.class, Consumer.class},
                        request, "Comprobando cancelación…", (Consumer<Properties>) ignored -> { });
                check(!button(panel, "insert").isEnabled() && !button(panel, "edit").isEnabled()
                        && !button(panel, "delete").isEnabled(), "Mutations disabled while background task runs");
                check(!((JList<?>) get(panel, "classList")).isEnabled(), "Class cannot change during an operation");
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        check(client.started.await(5, TimeUnit.SECONDS), "Operation runs in background client");
        SwingUtilities.invokeAndWait(() -> holder[0].close());
        check(client.closed, "Closing panel closes client");
        check(((SwingWorker<?, ?>) get(holder[0], "task")).isCancelled(), "Closing panel cancels background task");
        System.out.println("RepositoryPanelCheck: " + assertions + " checks passed; target/crud-panel.png");
    }

    private static Properties fixture() {
        Properties data = new Properties();
        data.setProperty("field.count", "4"); data.setProperty("row.count", "3");
        data.setProperty("creatable", "true"); data.setProperty("revision", "fixture-revision");
        String[] names = {"sku", "nombre", "precio", "stock"};
        String[] types = {"java.lang.String", "java.lang.String", "double", "int"};
        for (int i = 0; i < names.length; i++) {
            String prefix = "field." + i + ".";
            data.setProperty(prefix + "key", "co.edu.cecar.zentroshop.model.Producto#" + names[i]);
            data.setProperty(prefix + "name", names[i]); data.setProperty(prefix + "type", types[i]);
            data.setProperty(prefix + "editable", Boolean.toString(i > 1));
            data.setProperty(prefix + "nullable", Boolean.toString(i < 2));
            data.setProperty(prefix + "identifier", "true");
        }
        String[][] records = {{"SKU-002", "Mouse inalámbrico", "29.95", "12"},
            {"SKU-001", "Auriculares", "49.90", "7"}, {"SKU-003", "Teclado mecánico", "89.50", "5"}};
        for (int r = 0; r < records.length; r++) for (int f = 0; f < names.length; f++)
            data.setProperty("cell." + r + "." + f, records[r][f]);
        data.setProperty("ctor.count", "1");
        data.setProperty("ctor.0.key", "co.edu.cecar.zentroshop.model.Producto(java.lang.String,double,int)");
        data.setProperty("ctor.0.label", "Producto(String nombre, double precio, int stock)");
        data.setProperty("ctor.0.param.count", "3");
        for (int i = 0; i < 3; i++) {
            data.setProperty("ctor.0.param." + i + ".name", names[i + 1]);
            data.setProperty("ctor.0.param." + i + ".type", types[i + 1]);
            data.setProperty("ctor.0.param." + i + ".nullable", Boolean.toString(i == 0));
        }
        return data;
    }

    private static JButton button(Object object, String name) throws Exception { return (JButton) get(object, name); }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static Object call(Object object, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, parameterTypes); method.setAccessible(true); return method.invoke(object, args);
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static void check(boolean passed, String label) {
        assertions++; if (!passed) throw new AssertionError(label);
    }
    public static final class EmptyLookup extends ProxyLookup {
        public EmptyLookup() {
            super(Lookups.singleton((ProjectInformationProvider) project -> project.getLookup().lookup(ProjectInformation.class)));
        }
    }
    private static final class BlockingClient extends RepositoryClient {
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean closed;
        BlockingClient(Project project) { super(project); }
        @Override public Properties execute(Properties request) throws Exception {
            started.countDown(); new CountDownLatch(1).await(10, TimeUnit.SECONDS); return fixture();
        }
        @Override public void close() { closed = true; }
    }
    private static final class DemoProject implements Project, ProjectInformation {
        private final FileObject directory;
        DemoProject(FileObject directory) { this.directory = directory; }
        @Override public FileObject getProjectDirectory() { return directory; }
        @Override public Lookup getLookup() { return Lookups.singleton(this); }
        @Override public String getName() { return "ZentroShop"; }
        @Override public String getDisplayName() { return getName(); }
        @Override public Icon getIcon() { return null; }
        @Override public Project getProject() { return this; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) { }
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) { }
    }
}
