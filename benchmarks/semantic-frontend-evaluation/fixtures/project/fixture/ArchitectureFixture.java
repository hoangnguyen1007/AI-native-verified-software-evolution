package fixture;

import external.api.ExternalService;
import optional.api.OptionalService;
import java.util.List;

@ExternalService.Marker
public sealed interface ArchitectureFixture permits ArchitectureFixture.RecordCase, ArchitectureFixture.Impl {
    record RecordCase(String value) implements ArchitectureFixture { }
    final class Impl implements ArchitectureFixture {
        private int counter;
        public String run(List<String> values, ExternalService dependency, OptionalService optional) throws IllegalStateException {
            counter++;
            String inferred = values.get(0).trim();
            dependency.process(inferred);
            optional.optional();
            Runnable methodReference = dependency::ping;
            Runnable lambda = () -> dependency.ping();
            methodReference.run();
            lambda.run();
            return new String(inferred);
        }
    }
}
