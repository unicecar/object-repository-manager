package co.edu.cecar.objectrepositorymanager;

import java.awt.event.ActionEvent;
import java.util.Collection;
import javax.swing.AbstractAction;
import javax.swing.Action;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.awt.DynamicMenuContent;
import org.openide.util.ContextAwareAction;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;

/** Captures the project from the popup's lookup, not the global selection. */
@ActionID(category = "Project", id = "co.edu.cecar.objectrepositorymanager.ManageBinaryFilesAction")
@ActionRegistration(displayName = "#CTL_ManageBinaryFiles", lazy = false)
@ActionReferences({
    @ActionReference(path = "Projects/Actions", position = 1850),
    @ActionReference(path = "Projects/org-netbeans-modules-maven/Actions", position = 1850)
})
@Messages({
    "CTL_ManageBinaryFiles=Object Repository Manager",
    "# {0} - nombre del proyecto",
    "TTL_RepositoryManager=Object Repository Manager - {0}",
    "CTL_Close=Cerrar"
})
public final class ManageBinaryFilesAction extends AbstractAction implements ContextAwareAction {

    private final Project project;

    public ManageBinaryFilesAction() {
        this(null);
    }

    private ManageBinaryFilesAction(Project project) {
        super(Bundle.CTL_ManageBinaryFiles());
        this.project = project;
        putValue(DynamicMenuContent.HIDE_WHEN_DISABLED, false);
        setEnabled(true);
    }

    static SourceGroup[] javaSources(Project project) {
        return ProjectUtils.getSources(project)
                .getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA);
    }

    @Override
    public Action createContextAwareInstance(Lookup context) {
        Collection<? extends Project> projects = context.lookupAll(Project.class);
        return new ManageBinaryFilesAction(projects.size() == 1 ? projects.iterator().next() : null);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (project == null) {
            new OpenRepositoryManagerAction().actionPerformed(event);
            return;
        }
        open(project);
    }

    static void open(Project project) {
        RepositoryManagerTopComponent.openProject(project);
    }
}
