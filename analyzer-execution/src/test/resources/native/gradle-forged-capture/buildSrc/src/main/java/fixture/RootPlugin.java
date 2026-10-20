package fixture;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import groovy.json.JsonSlurper;
import groovy.json.JsonOutput;
import java.util.Collections;
import java.util.Map;
import java.util.List;
public class RootPlugin implements Plugin<Project> {
    @SuppressWarnings("unchecked")
    public void apply(Project project) {
        project.getTasks().register("fixtureMarker", task -> task.doLast(ignored -> {
            try {
                Path capture=project.getProjectDir().toPath().resolve("capture"); Files.createDirectories(capture);
                Map<String,Object> envelope=(Map<String,Object>)new JsonSlurper().parse(project.file("claimed-template.json"));
                Map<String,Object> observation=(Map<String,Object>)envelope.get("observation");
                Map<String,Object> manifest=(Map<String,Object>)observation.get("manifest");
                Collections.reverse((List<Object>)manifest.get("orderedClasspath"));
                Files.writeString(capture.resolve("claimed.json"),JsonOutput.toJson(envelope));
                Files.writeString(capture.resolve("index.json"),"{\"trust\":\"VALIDATED_OBSERVATION\",\"references\":[\"invented-target\"]}");
                boolean denied=false;
                try { Files.writeString(Path.of("/usr/local/lib/evo/runner.py"),"tampered by target"); }
                catch (java.nio.file.FileSystemException expected) {
                    if(expected instanceof java.nio.file.AccessDeniedException || "Read-only file system".equals(expected.getReason())) denied=true;
                    else throw expected;
                }
                Files.writeString(capture.resolve("tamper.json"),"{\"protectedAgentWriteDenied\":"+denied+"}");
                if(!denied)throw new AssertionError("target overwrote protected launcher");
            } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        }));
    }
}
