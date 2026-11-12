package co.edu.cecar.objectrepositorymanager;

import java.awt.BorderLayout;
import org.netbeans.api.project.Project;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

@TopComponent.Description(preferredID = "ObjectRepositoryManagerTopComponent", persistenceType = TopComponent.PERSISTENCE_NEVER)
@TopComponent.Registration(mode = "navigator", openAtStartup = false, position = 450)
public final class RepositoryManagerTopComponent extends TopComponent {
    private RepositoryManagerPanel panel;
    public RepositoryManagerTopComponent() { setLayout(new BorderLayout()); setName("Object Repository Manager"); }
    public static void openProject(Project project) {
        TopComponent current = WindowManager.getDefault().findTopComponent("ObjectRepositoryManagerTopComponent");
        RepositoryManagerTopComponent view = current instanceof RepositoryManagerTopComponent
                ? (RepositoryManagerTopComponent) current : new RepositoryManagerTopComponent();
        if (view.panel != null) view.panel.close();
        view.removeAll();
        view.panel = new RepositoryManagerPanel(project);
        view.add(view.panel, BorderLayout.CENTER);
        view.open();
        view.requestActive();
    }
    @Override protected void componentClosed() {
        if (panel != null) panel.close();
        panel = null;
        removeAll();
    }
}
