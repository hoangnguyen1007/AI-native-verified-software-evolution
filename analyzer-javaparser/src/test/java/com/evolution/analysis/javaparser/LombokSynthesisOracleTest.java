package com.evolution.analysis.javaparser;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class LombokSynthesisOracleTest {
    @TempDir Path temporary;
    @Test void generatedConstructorSignaturesAgreeWithPinnedLombokCompilerForAuthoredControls()throws Exception {
        Path jar=Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var digest=ContentDigest.sha256(Files.readAllBytes(jar));
        assertEquals("sha256:01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126",digest.value());
        String source="""
                @lombok.RequiredArgsConstructor class Required { final String name; @lombok.NonNull Integer count; static int ignored; final int initialized=1; }
                @lombok.AllArgsConstructor class All { String name; int count; final int initialized=1; }
                @lombok.NoArgsConstructor(force=true) class Empty { final String name; }
                @lombok.Data class Data { final String name; int count; }
                @lombok.Value class Value { String name; int count; }
                @lombok.Builder class Built { String name; int count; }
                """;
        Path file=temporary.resolve("Controls.java");Files.writeString(file,source);
        // Only these fixed authored controls execute a processor; repository inputs never do.
        var output=new java.io.ByteArrayOutputStream();
        int exit=ToolProvider.getSystemJavaCompiler().run(null,output,output,"--release","21","-encoding","UTF-8",
                "-classpath",jar.toString(),"-processorpath",jar.toString(),"-processor","lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
                "-d",temporary.toString(),file.toString());
        assertEquals(0,exit,"Trusted Lombok oracle compilation failed");
        var binary=new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"org.projectlombok:lombok:1.18.46@jar",digest),jar);
        var frontend=new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/Controls.java",source),List.of(binary)));
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            for(String name:List.of("Required","All","Empty","Data","Value","Built","Built$BuiltBuilder")) {
                var compiled=Class.forName(name,false,loader);var actual=compiled.getDeclaredConstructors();assertEquals(1,actual.length);
                var parameters=Arrays.stream(actual[0].getParameterTypes()).map(c->c.isPrimitive()?ErasedType.primitive(c.getName()):ErasedType.declared(JavaSymbolName.topLevelType(c.getPackageName(),c.getSimpleName()))).toList();
                var owner=symbol(compiled);String expected=JavaSymbolName.constructor(owner,parameters).canonicalName();
                assertTrue(frontend.declarations().stream().anyMatch(d->d.entity().canonicalName().equals(expected)),name);
                var expectedMethods=Arrays.stream(compiled.getDeclaredMethods()).filter(m->!m.isSynthetic()).map(m->JavaSymbolName.method(owner,m.getName(),Arrays.stream(m.getParameterTypes()).map(c->c.isPrimitive()?ErasedType.primitive(c.getName()):ErasedType.declared(symbol(c))).toList()).canonicalName()).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                var methods=frontend.declarations().stream().filter(d->d.entity().kind()==EntityKind.METHOD&&d.entity().canonicalName().startsWith("java:v1:[\"method\","+owner.canonicalName().substring(8)+","))
                        .map(d->d.entity().canonicalName()).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                assertEquals(expectedMethods,methods,name);
            }
        }
    }
    private static JavaSymbolName symbol(Class<?> type) {
        return type.getEnclosingClass()==null?JavaSymbolName.topLevelType(type.getPackageName(),type.getSimpleName()):JavaSymbolName.memberType(symbol(type.getEnclosingClass()),type.getSimpleName());
    }
}
