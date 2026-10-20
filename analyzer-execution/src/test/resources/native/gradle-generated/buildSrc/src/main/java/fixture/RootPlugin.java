package fixture;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
public class RootPlugin implements Plugin<Project> {
    public void apply(Project project) {
        project.getTasks().register("fixtureMarker", task -> task.doLast(ignored -> System.out.println("AUTHORED_BUILD_SRC_EXECUTED")));
    }
}
