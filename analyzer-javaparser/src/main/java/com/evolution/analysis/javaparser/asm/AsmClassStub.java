package com.evolution.analysis.javaparser.asm;

import java.util.*;
import org.objectweb.asm.*;

/**
 * Immutable, thread-safe structural metadata extracted from a JVM {@code .class} file
 * using ASM {@code ClassReader.SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES}.
 * Contains zero references to any {@code TypeSolver} and is safely shared across modules and threads.
 */
public final class AsmClassStub {
    public enum Kind {
        ANNOTATION, INTERFACE, ENUM, RECORD, CLASS
    }

    public record AnnotationStub(String descriptor, String qualifiedName) {}

    public record FieldStub(
            int access,
            String name,
            String descriptor,
            String signature,
            List<AnnotationStub> annotations) {}

    public record MethodStub(
            int access,
            String name,
            String descriptor,
            String signature,
            List<String> exceptions,
            List<AnnotationStub> annotations,
            List<String> parameterNames,
            Object annotationDefault) {
        public boolean isConstructor() {
            return "<init>".equals(name);
        }

        public boolean isStaticInitializer() {
            return "<clinit>".equals(name);
        }

        public boolean isSynthetic() {
            return (access & Opcodes.ACC_SYNTHETIC) != 0;
        }

        public boolean isBridge() {
            return (access & Opcodes.ACC_BRIDGE) != 0;
        }

        public boolean isVarArgs() {
            return (access & Opcodes.ACC_VARARGS) != 0;
        }

        public boolean isStatic() {
            return (access & Opcodes.ACC_STATIC) != 0;
        }

        public boolean isAbstract() {
            return (access & Opcodes.ACC_ABSTRACT) != 0;
        }
    }

    public record RecordComponentStub(String name, String descriptor, String signature) {}

    public record EnumConstantDefault(String enumDescriptor, String constantName) {}

    public record ClassDefault(String descriptor) {}

    public record AnnotationDefault(String descriptor, Map<String, Object> values) {
        public AnnotationDefault {
            var ordered = new TreeMap<String, Object>(values);
            values = Collections.unmodifiableMap(ordered);
        }
    }

    public record ModuleExportStub(String moduleName, List<String> exportedPackages) {}

    private final Kind kind;
    private final int access;
    private final String internalName;
    private final String binaryName;
    private final String qualifiedName;
    private final String packageName;
    private final String className;
    private final String simpleName;
    private final String superInternalName;
    private final List<String> interfaceInternalNames;
    private final String signature;
    private final String outerInternalName;
    private final List<String> nestedInternalNames;
    private final List<AnnotationStub> annotations;
    private final Set<String> directAnnotationNames;
    private final List<FieldStub> fields;
    private final List<MethodStub> methods;
    private final List<RecordComponentStub> recordComponents;
    private final ModuleExportStub moduleExport;

    private AsmClassStub(
            Kind kind,
            int access,
            String internalName,
            String superInternalName,
            List<String> interfaceInternalNames,
            String signature,
            String outerInternalName,
            List<String> nestedInternalNames,
            List<AnnotationStub> annotations,
            List<FieldStub> fields,
            List<MethodStub> methods,
            List<RecordComponentStub> recordComponents,
            ModuleExportStub moduleExport) {
        this.kind = kind;
        this.access = access;
        this.internalName = internalName;
        this.binaryName = internalName.replace('/', '.');
        this.qualifiedName = this.binaryName.replace('$', '.');
        int lastSlash = internalName.lastIndexOf('/');
        this.packageName = lastSlash >= 0 ? internalName.substring(0, lastSlash).replace('/', '.') : "";
        this.className = this.packageName.isEmpty()
                ? this.qualifiedName
                : this.qualifiedName.substring(this.packageName.length() + 1);
        int lastDot = this.qualifiedName.lastIndexOf('.');
        this.simpleName = lastDot >= 0 ? this.qualifiedName.substring(lastDot + 1) : this.qualifiedName;
        this.superInternalName = superInternalName;
        this.interfaceInternalNames = List.copyOf(interfaceInternalNames);
        this.signature = signature;
        this.outerInternalName = outerInternalName;
        this.nestedInternalNames = List.copyOf(nestedInternalNames);
        this.annotations = List.copyOf(annotations);
        var annNames = new HashSet<String>(Math.max(4, annotations.size() * 2));
        for (var a : annotations) annNames.add(a.qualifiedName());
        this.directAnnotationNames = Collections.unmodifiableSet(annNames);
        this.fields = List.copyOf(fields);
        this.methods = List.copyOf(methods);
        this.recordComponents = List.copyOf(recordComponents);
        this.moduleExport = moduleExport;
    }

    public Kind kind() { return kind; }
    public int access() { return access; }
    public String internalName() { return internalName; }
    public String binaryName() { return binaryName; }
    public String qualifiedName() { return qualifiedName; }
    public String packageName() { return packageName; }
    public String className() { return className; }
    public String simpleName() { return simpleName; }
    public String superInternalName() { return superInternalName; }
    public List<String> interfaceInternalNames() { return interfaceInternalNames; }
    public String signature() { return signature; }
    public String outerInternalName() { return outerInternalName; }
    public List<String> nestedInternalNames() { return nestedInternalNames; }
    public List<AnnotationStub> annotations() { return annotations; }
    public boolean hasDirectAnnotation(String qualifiedName) { return directAnnotationNames.contains(qualifiedName); }
    public List<FieldStub> fields() { return fields; }
    public List<MethodStub> methods() { return methods; }
    public List<RecordComponentStub> recordComponents() { return recordComponents; }
    public ModuleExportStub moduleExport() { return moduleExport; }

    public static AsmClassStub parse(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        var collector = new StubClassVisitor();
        reader.accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return collector.build();
    }

    static String descriptorToQualifiedName(String descriptor) {
        if (descriptor == null || descriptor.length() < 3
                || descriptor.charAt(0) != 'L'
                || descriptor.charAt(descriptor.length() - 1) != ';') {
            return descriptor == null ? "" : descriptor;
        }
        return internalToQualifiedName(descriptor.substring(1, descriptor.length() - 1));
    }

    public static String internalToQualifiedName(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    private static final class StubClassVisitor extends ClassVisitor {
        private int access;
        private int innerAccess = -1;
        private String internalName;
        private String superInternalName;
        private List<String> interfaces = List.of();
        private String signature;
        private String outerInternalName;
        private final List<String> nestedInternalNames = new ArrayList<>();
        private final List<AnnotationStub> annotations = new ArrayList<>();
        private final List<FieldStub> fields = new ArrayList<>();
        private final List<MethodStub> methods = new ArrayList<>();
        private final List<RecordComponentStub> recordComponents = new ArrayList<>();
        private boolean hasRecordAttribute;
        private String moduleName;
        private final List<String> moduleExports = new ArrayList<>();

        StubClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.access = access;
            this.internalName = name;
            this.signature = signature;
            this.superInternalName = superName;
            this.interfaces = interfaces == null || interfaces.length == 0 ? List.of() : List.of(interfaces);
            if ((access & Opcodes.ACC_RECORD) != 0) {
                this.hasRecordAttribute = true;
            }
        }

        @Override
        public ModuleVisitor visitModule(String name, int access, String version) {
            this.moduleName = name;
            return new ModuleVisitor(Opcodes.ASM9) {
                @Override
                public void visitExport(String packaze, int access, String... modules) {
                    if (modules == null || modules.length == 0) {
                        moduleExports.add(packaze.replace('/', '.'));
                    }
                }
            };
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            if (name == null) return;
            if (name.equals(this.internalName)) {
                if (outerName != null && innerName != null) {
                    this.outerInternalName = outerName;
                }
                this.innerAccess = access;
            } else if (outerName != null && outerName.equals(this.internalName) && innerName != null) {
                if (!nestedInternalNames.contains(name)) {
                    nestedInternalNames.add(name);
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations.add(new AnnotationStub(descriptor, descriptorToQualifiedName(descriptor)));
            return null;
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            this.hasRecordAttribute = true;
            recordComponents.add(new RecordComponentStub(name, descriptor, signature));
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            var fieldAnns = new ArrayList<AnnotationStub>(2);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    fieldAnns.add(new AnnotationStub(desc, descriptorToQualifiedName(desc)));
                    return null;
                }

                @Override
                public void visitEnd() {
                    fields.add(new FieldStub(access, name, descriptor, signature, List.copyOf(fieldAnns)));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ("<clinit>".equals(name)) {
                return null;
            }
            var methodAnns = new ArrayList<AnnotationStub>(2);
            var paramNames = new ArrayList<String>(4);
            var excList = exceptions == null || exceptions.length == 0 ? List.<String>of() : List.of(exceptions);
            final Object[] defaultVal = new Object[1];
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitParameter(String paramName, int paramAccess) {
                    paramNames.add(paramName);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    methodAnns.add(new AnnotationStub(desc, descriptorToQualifiedName(desc)));
                    return null;
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return new DefaultValueCollector(v -> defaultVal[0] = v);
                }

                @Override
                public void visitEnd() {
                    methods.add(new MethodStub(
                            access,
                            name,
                            descriptor,
                            signature,
                            excList,
                            List.copyOf(methodAnns),
                            List.copyOf(paramNames),
                            defaultVal[0]));
                }
            };
        }

        AsmClassStub build() {
            int visMask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
            int effectiveAccess = innerAccess >= 0 ? ((access & ~visMask) | (innerAccess & visMask)) : access;
            Kind kind;
            if ((effectiveAccess & Opcodes.ACC_ANNOTATION) != 0) {
                kind = Kind.ANNOTATION;
            } else if ((effectiveAccess & Opcodes.ACC_INTERFACE) != 0) {
                kind = Kind.INTERFACE;
            } else if ((effectiveAccess & Opcodes.ACC_ENUM) != 0) {
                kind = Kind.ENUM;
            } else if (hasRecordAttribute || "java/lang/Record".equals(superInternalName)) {
                kind = Kind.RECORD;
            } else {
                kind = Kind.CLASS;
            }
            ModuleExportStub mod = moduleName != null ? new ModuleExportStub(moduleName, List.copyOf(moduleExports)) : null;
            return new AsmClassStub(
                    kind,
                    effectiveAccess,
                    internalName,
                    superInternalName,
                    interfaces,
                    signature,
                    outerInternalName,
                    nestedInternalNames,
                    annotations,
                    fields,
                    methods,
                    recordComponents,
                    mod);
        }
    }

    private static final class DefaultValueCollector extends AnnotationVisitor {
        private final java.util.function.Consumer<Object> sink;

        DefaultValueCollector(java.util.function.Consumer<Object> sink) {
            super(Opcodes.ASM9);
            this.sink = sink;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof Type asmType) {
                sink.accept(new ClassDefault(asmType.getDescriptor()));
            } else {
                sink.accept(value);
            }
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            sink.accept(new EnumConstantDefault(descriptor, value));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            return new NestedAnnotationCollector(descriptor, sink);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return new ArrayValueCollector(sink);
        }
    }

    private static final class ArrayValueCollector extends AnnotationVisitor {
        private final java.util.function.Consumer<Object> sink;
        private final List<Object> items = new ArrayList<>();

        ArrayValueCollector(java.util.function.Consumer<Object> sink) {
            super(Opcodes.ASM9);
            this.sink = sink;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof Type asmType) {
                items.add(new ClassDefault(asmType.getDescriptor()));
            } else {
                items.add(value);
            }
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            items.add(new EnumConstantDefault(descriptor, value));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            return new NestedAnnotationCollector(descriptor, items::add);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return new ArrayValueCollector(items::add);
        }

        @Override
        public void visitEnd() {
            sink.accept(List.copyOf(items));
        }
    }

    private static final class NestedAnnotationCollector extends AnnotationVisitor {
        private final String descriptor;
        private final java.util.function.Consumer<Object> sink;
        private final Map<String, Object> values = new TreeMap<>();

        NestedAnnotationCollector(String descriptor, java.util.function.Consumer<Object> sink) {
            super(Opcodes.ASM9);
            this.descriptor = descriptor;
            this.sink = sink;
        }

        @Override
        public void visit(String name, Object value) {
            if (name == null) return;
            if (value instanceof Type asmType) {
                values.put(name, new ClassDefault(asmType.getDescriptor()));
            } else {
                values.put(name, value);
            }
        }

        @Override
        public void visitEnum(String name, String enumDescriptor, String value) {
            if (name != null) {
                values.put(name, new EnumConstantDefault(enumDescriptor, value));
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String nestedDescriptor) {
            if (name == null) return null;
            return new NestedAnnotationCollector(nestedDescriptor, v -> values.put(name, v));
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if (name == null) return null;
            return new ArrayValueCollector(v -> values.put(name, v));
        }

        @Override
        public void visitEnd() {
            sink.accept(new AnnotationDefault(descriptor, values));
        }
    }
}
