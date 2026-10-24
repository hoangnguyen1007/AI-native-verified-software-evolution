package fixture;

import java.io.IOException;
import java.util.Set;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class PartialProcessor extends AbstractProcessor {
    private boolean emitted;
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (!emitted && !round.processingOver()) {
            emitted = true;
            try (var writer = processingEnv.getFiler().createSourceFile("fixture.GeneratedBeforeFailure").openWriter()) {
                writer.write("package fixture; public final class GeneratedBeforeFailure {}\n");
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "EVO_EXPECTED_PARTIAL_PROCESSOR_FAILURE");
        }
        return false;
    }
}
