package co.edu.cecar.objectrepositorymanager;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbBundle.Messages;

/** Opens the docked manager from a stable View-menu entry. */
@ActionID(category = "View", id = "co.edu.cecar.objectrepositorymanager.OpenRepositoryManagerAction")
@ActionRegistration(displayName = "#CTL_OpenRepositoryManager", lazy = false)
@ActionReference(path = "Menu/View", position = 1770)
@Messages({
    "CTL_OpenRepositoryManager=Object Repository Manager",
    "TTL_SelectJavaProject=Seleccionar proyecto Java para Object Repository Manager",
    "ERR_InvalidProject=Selecciona la carpeta raíz de un proyecto Java reconocido por NetBeans."
})
public final class OpenRepositoryManagerAction extends AbstractAction {
    public OpenRepositoryManagerAction() {
        super(Bundle.CTL_OpenRepositoryManager());
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(Bundle.TTL_SelectJavaProject());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        FileObject directory = FileUtil.toFileObject(chooser.getSelectedFile().getAbsoluteFile());
        Project project;
        try {
            project = directory == null ? null : ProjectManager.getDefault().findProject(directory);
        } catch (IOException | IllegalArgumentException ex) {
            project = null;
        }
        if (project == null || ManageBinaryFilesAction.javaSources(project).length == 0) {
            JOptionPane.showMessageDialog(null, Bundle.ERR_InvalidProject(),
                    Bundle.CTL_OpenRepositoryManager(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        ManageBinaryFilesAction.open(project);
    }
}
