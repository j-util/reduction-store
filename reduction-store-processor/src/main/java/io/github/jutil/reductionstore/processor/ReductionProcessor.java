package io.github.jutil.reductionstore.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Generates one strongly typed reduction store for each supported input type
 * found in the compiler's root source elements.
 *
 * <p>This is a universal processor: it supports {@code "*"}, so javac invokes
 * it even when client sources have no annotations. Applications should
 * configure the processor artifact on the annotation-processor path rather
 * than instantiate this class.
 */
@SupportedAnnotationTypes("*")
public final class ReductionProcessor extends AbstractProcessor {

    private static final String STORE_SUFFIX = "ReductionStore";

    private Elements elements;
    private Types types;
    private Filer filer;
    private Messager messager;
    private TypeMirror runtimeExceptionType;
    private TypeMirror errorType;
    private final Set<String> objectNoArgMethodNames =
            new HashSet<String>();
    private boolean processedInitialSources;

    /**
     * Creates a processor for compiler service discovery.
     */
    public ReductionProcessor() {
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        elements = processingEnvironment.getElementUtils();
        types = processingEnvironment.getTypeUtils();
        filer = processingEnvironment.getFiler();
        messager = processingEnvironment.getMessager();
        runtimeExceptionType = elements
                .getTypeElement(RuntimeException.class.getCanonicalName())
                .asType();
        errorType = elements.getTypeElement(Error.class.getCanonicalName())
                .asType();

        TypeElement objectType = elements.getTypeElement(
                Object.class.getCanonicalName());
        for (ExecutableElement method
                : ElementFilter.methodsIn(objectType.getEnclosedElements())) {
            if (!method.getModifiers().contains(Modifier.STATIC)
                    && method.getParameters().isEmpty()) {
                objectNoArgMethodNames.add(method.getSimpleName().toString());
            }
        }
    }

    /**
     * Supports the latest source level understood by the compiler executing
     * this Java-8-compatible processor.
     *
     * @return the latest source version supported by the running compiler
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /** {@inheritDoc} */
    @Override
    public boolean process(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnvironment) {
        if (processedInitialSources || roundEnvironment.processingOver()) {
            return false;
        }
        processedInitialSources = true;

        TypeElement reductionElement = elements.getTypeElement(
                StateKind.OBJECT.contractType);
        if (reductionElement == null) {
            return false;
        }

        Set<String> currentTopLevelTypes = new HashSet<String>();
        List<TypeElement> compilationTypes = new ArrayList<TypeElement>();
        for (Element root : roundEnvironment.getRootElements()) {
            if (root instanceof TypeElement) {
                TypeElement rootType = (TypeElement) root;
                currentTopLevelTypes.add(
                        rootType.getQualifiedName().toString());
                collectTypes(rootType, compilationTypes);
            }
        }

        Map<String, ReductionGroup> groups =
                new LinkedHashMap<String, ReductionGroup>();
        for (TypeElement type : compilationTypes) {
            ResolvedReduction reduction = findReductionType(
                    type.asType(), new HashSet<String>());
            if (reduction == null) {
                continue;
            }
            if (type.getKind() != ElementKind.CLASS
                    || type.getModifiers().contains(Modifier.ABSTRACT)) {
                if (type.getKind() != ElementKind.INTERFACE
                        && !type.getModifiers().contains(Modifier.ABSTRACT)) {
                    error(type,
                            "Reduction implementations must be concrete "
                                    + "classes");
                }
                continue;
            }
            collectReduction(
                    type, reduction, currentTopLevelTypes, groups);
        }

        List<ReductionGroup> orderedGroups =
                new ArrayList<ReductionGroup>(groups.values());
        Collections.sort(orderedGroups, new Comparator<ReductionGroup>() {
            @Override
            public int compare(ReductionGroup left, ReductionGroup right) {
                return left.sourceName.compareTo(right.sourceName);
            }
        });
        for (ReductionGroup group : orderedGroups) {
            validateNames(group);
            if (group.valid) {
                generateStore(group);
            }
        }
        return false;
    }

    private void collectTypes(
            TypeElement type, List<TypeElement> compilationTypes) {
        compilationTypes.add(type);
        for (TypeElement nested
                : ElementFilter.typesIn(type.getEnclosedElements())) {
            collectTypes(nested, compilationTypes);
        }
    }

    private ResolvedReduction findReductionType(
            TypeMirror type, Set<String> visited) {
        if (type.getKind() != TypeKind.DECLARED
                && type.getKind() != TypeKind.ERROR) {
            return null;
        }
        DeclaredType declaredType = (DeclaredType) type;
        TypeElement typeElement = (TypeElement) declaredType.asElement();
        StateKind stateKind = StateKind.forContract(
                typeElement.getQualifiedName());
        if (stateKind != null) {
            return new ResolvedReduction(stateKind, declaredType);
        }
        if (!visited.add(type.toString())) {
            return null;
        }
        for (TypeMirror supertype : types.directSupertypes(declaredType)) {
            ResolvedReduction found = findReductionType(supertype, visited);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void collectReduction(
            TypeElement implementation,
            ResolvedReduction reduction,
            Set<String> currentTopLevelTypes,
            Map<String, ReductionGroup> groups) {
        List<? extends TypeMirror> arguments =
                reduction.type.getTypeArguments();
        if (arguments.size() != reduction.stateKind.typeParameterCount) {
            error(implementation,
                    reduction.stateKind.simpleName
                            + " must not be implemented as a raw type");
            return;
        }

        TypeMirror sourceType = arguments.get(0);
        TypeMirror stateType = reduction.stateKind == StateKind.OBJECT
                ? arguments.get(1) : null;
        if (sourceType.getKind() != TypeKind.DECLARED) {
            error(implementation,
                    "Reduction input type must be a declared top-level type");
            return;
        }
        TypeElement sourceElement =
                (TypeElement) ((DeclaredType) sourceType).asElement();
        String sourceName = sourceElement.getQualifiedName().toString();
        ReductionGroup group = groups.get(sourceName);
        if (group == null) {
            group = new ReductionGroup(sourceElement, sourceName);
            groups.put(sourceName, group);
        }

        boolean valid = true;
        if (sourceElement.getNestingKind() != NestingKind.TOP_LEVEL) {
            error(implementation,
                    "Reduction input type must be top-level: " + sourceType);
            valid = false;
        }
        if (!sourceElement.getTypeParameters().isEmpty()
                || !((DeclaredType) sourceType).getTypeArguments().isEmpty()) {
            error(implementation,
                    "Reduction input type must be non-generic: " + sourceType);
            valid = false;
        }
        if (!currentTopLevelTypes.contains(sourceName)) {
            error(implementation,
                    "Reduction input type must be compiled in the same full "
                            + "javac invocation: " + sourceName);
            valid = false;
        }

        PackageElement generatedPackage = elements.getPackageOf(sourceElement);
        if (!implementation.getTypeParameters().isEmpty()) {
            error(implementation,
                    "Reduction implementation classes must be non-generic");
            valid = false;
        }
        if (implementation.getNestingKind() == NestingKind.MEMBER
                && !implementation.getModifiers().contains(Modifier.STATIC)) {
            error(implementation,
                    "Member reduction implementation classes must be static");
            valid = false;
        }
        if (!isTypeElementAccessible(implementation, generatedPackage)) {
            error(implementation,
                    "Reduction implementation is not accessible from generated "
                            + "package " + packageName(generatedPackage));
            valid = false;
        }
        if (!hasUsableNoArgConstructor(implementation, generatedPackage)) {
            valid = false;
        }
        if (reduction.stateKind == StateKind.OBJECT) {
            if (!isRepresentableStateType(stateType)) {
                error(implementation,
                        "Reduction state type must be a concrete source-level "
                                + "reference type: " + stateType);
                valid = false;
            } else if (!isAccessible(stateType, generatedPackage)) {
                error(implementation,
                        "Reduction state type is not accessible from generated "
                                + "package " + packageName(generatedPackage)
                                + ": " + stateType);
                valid = false;
            }
        }

        if (!valid) {
            group.valid = false;
            return;
        }

        String accessorName = decapitalize(
                implementation.getSimpleName().toString());
        group.reductions.add(new ReductionDescriptor(
                implementation,
                implementation.getQualifiedName().toString(),
                reduction.stateKind,
                reduction.stateKind == StateKind.OBJECT
                        ? stateType.toString()
                        : reduction.stateKind.primitiveType,
                accessorName));
    }

    private boolean hasUsableNoArgConstructor(
            TypeElement implementation, PackageElement generatedPackage) {
        List<ExecutableElement> constructors = ElementFilter.constructorsIn(
                implementation.getEnclosedElements());
        if (constructors.isEmpty()) {
            return true;
        }
        for (ExecutableElement constructor : constructors) {
            if (!constructor.getParameters().isEmpty()
                    || !isConstructorAccessible(
                            constructor, implementation, generatedPackage)) {
                continue;
            }
            boolean checkedException = false;
            for (TypeMirror thrownType : constructor.getThrownTypes()) {
                if (!types.isSubtype(thrownType, runtimeExceptionType)
                        && !types.isSubtype(thrownType, errorType)) {
                    checkedException = true;
                    break;
                }
            }
            if (checkedException) {
                error(implementation,
                        "Reduction no-argument constructor must not declare "
                                + "checked exceptions");
                return false;
            }
            return true;
        }
        error(implementation,
                "Reduction implementation must have a no-argument constructor "
                        + "accessible from generated package "
                        + packageName(generatedPackage));
        return false;
    }

    private boolean isConstructorAccessible(
            ExecutableElement constructor,
            TypeElement implementation,
            PackageElement generatedPackage) {
        Set<Modifier> modifiers = constructor.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE)) {
            return false;
        }
        if (samePackage(
                elements.getPackageOf(implementation), generatedPackage)) {
            return true;
        }
        return modifiers.contains(Modifier.PUBLIC);
    }

    private boolean isRepresentableStateType(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind == TypeKind.ARRAY) {
            return isRepresentableStateType(
                    ((ArrayType) type).getComponentType());
        }
        if (kind == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType) type;
            TypeMirror enclosingType = declaredType.getEnclosingType();
            if (enclosingType.getKind() != TypeKind.NONE
                    && !isRepresentableStateType(enclosingType)) {
                return false;
            }
            for (TypeMirror argument : declaredType.getTypeArguments()) {
                if (!isRepresentableStateType(argument)) {
                    return false;
                }
            }
            return true;
        }
        if (kind == TypeKind.WILDCARD) {
            WildcardType wildcard = (WildcardType) type;
            return (wildcard.getExtendsBound() == null
                    || isRepresentableStateType(wildcard.getExtendsBound()))
                    && (wildcard.getSuperBound() == null
                    || isRepresentableStateType(wildcard.getSuperBound()));
        }
        return kind.isPrimitive();
    }

    private boolean isAccessible(
            TypeMirror type, PackageElement generatedPackage) {
        TypeKind kind = type.getKind();
        if (kind.isPrimitive()) {
            return true;
        }
        if (kind == TypeKind.ARRAY) {
            return isAccessible(
                    ((ArrayType) type).getComponentType(), generatedPackage);
        }
        if (kind == TypeKind.WILDCARD) {
            WildcardType wildcard = (WildcardType) type;
            return (wildcard.getExtendsBound() == null
                    || isAccessible(wildcard.getExtendsBound(), generatedPackage))
                    && (wildcard.getSuperBound() == null
                    || isAccessible(wildcard.getSuperBound(), generatedPackage));
        }
        if (kind != TypeKind.DECLARED) {
            return false;
        }

        DeclaredType declaredType = (DeclaredType) type;
        TypeMirror enclosingType = declaredType.getEnclosingType();
        if (enclosingType.getKind() != TypeKind.NONE
                && !isAccessible(enclosingType, generatedPackage)) {
            return false;
        }
        if (!isTypeElementAccessible(
                (TypeElement) declaredType.asElement(), generatedPackage)) {
            return false;
        }
        for (TypeMirror argument : declaredType.getTypeArguments()) {
            if (!isAccessible(argument, generatedPackage)) {
                return false;
            }
        }
        return true;
    }

    private boolean isTypeElementAccessible(
            TypeElement type, PackageElement generatedPackage) {
        boolean samePackage = samePackage(
                elements.getPackageOf(type), generatedPackage);
        Element current = type;
        while (current instanceof TypeElement) {
            Set<Modifier> modifiers = current.getModifiers();
            if (modifiers.contains(Modifier.PRIVATE)) {
                return false;
            }
            if (!samePackage && !modifiers.contains(Modifier.PUBLIC)) {
                return false;
            }
            current = current.getEnclosingElement();
        }
        return true;
    }

    private boolean samePackage(
            PackageElement left, PackageElement right) {
        return left.getQualifiedName().contentEquals(right.getQualifiedName());
    }

    private String packageName(PackageElement packageElement) {
        String name = packageElement.getQualifiedName().toString();
        return name.length() == 0 ? "<unnamed package>" : name;
    }

    private void validateNames(ReductionGroup group) {
        Collections.sort(
                group.reductions,
                new Comparator<ReductionDescriptor>() {
                    @Override
                    public int compare(
                            ReductionDescriptor left,
                            ReductionDescriptor right) {
                        return left.implementationName.compareTo(
                                right.implementationName);
                    }
                });

        Map<String, ReductionDescriptor> accessors =
                new HashMap<String, ReductionDescriptor>();
        for (ReductionDescriptor reduction : group.reductions) {
            String accessor = reduction.accessorName;
            if (!SourceVersion.isIdentifier(accessor)
                    || SourceVersion.isKeyword(accessor)) {
                error(reduction.implementation,
                        "Reduction class name produces an invalid accessor: "
                                + accessor + "()");
                group.valid = false;
            }
            if (objectNoArgMethodNames.contains(accessor)) {
                error(reduction.implementation,
                        "Reduction accessor " + accessor
                                + "() conflicts with java.lang.Object");
                group.valid = false;
            }
            ReductionDescriptor previous = accessors.put(accessor, reduction);
            if (previous != null) {
                String message = "Reduction accessor collision for "
                        + group.sourceName + ": " + accessor + "() is derived "
                        + "from both " + previous.implementationName + " and "
                        + reduction.implementationName;
                error(previous.implementation, message);
                error(reduction.implementation, message);
                group.valid = false;
            }
        }
    }

    private void generateStore(ReductionGroup group) {
        String packageName = elements.getPackageOf(group.sourceElement)
                .getQualifiedName().toString();
        String simpleName = group.sourceElement.getSimpleName().toString()
                + STORE_SUFFIX;
        String qualifiedName = packageName.length() == 0
                ? simpleName : packageName + "." + simpleName;

        List<Element> originatingElements = new ArrayList<Element>();
        originatingElements.add(group.sourceElement);
        for (ReductionDescriptor reduction : group.reductions) {
            originatingElements.add(reduction.implementation);
        }

        try {
            JavaFileObject sourceFile = filer.createSourceFile(
                    qualifiedName,
                    originatingElements.toArray(
                            new Element[originatingElements.size()]));
            Writer writer = sourceFile.openWriter();
            try {
                writer.write(generateSource(group, packageName, simpleName));
            } finally {
                writer.close();
            }
        } catch (IOException exception) {
            error(group.sourceElement,
                    "Could not generate reduction store " + qualifiedName
                            + ": " + exception.getMessage());
        }
    }

    private String generateSource(
            ReductionGroup group,
            String packageName,
            String simpleName) {
        StringBuilder source = new StringBuilder(4096);
        if (packageName.length() != 0) {
            line(source, "package " + packageName + ";");
            line(source, "");
        }
        line(source, "/**");
        line(source, " * Reduction store generated for {@link "
                + group.sourceName + "}.");
        line(source, " *");
        line(source, " * <p>This type is not thread-safe. Reducers run in "
                + "reduction implementation name order.");
        line(source, " */");
        line(source, "public final class " + simpleName + " {");
        line(source, "");
        for (int index = 0; index < group.reductions.size(); index++) {
            ReductionDescriptor reduction = group.reductions.get(index);
            line(source, "    private final "
                    + reduction.stateKind.reducerType(
                            reduction.stateType, group.sourceName)
                    + " reducer" + index + ";");
            line(source, "    private " + reduction.stateType + " state"
                    + index + ";");
        }
        line(source, "");
        line(source, "    /**");
        line(source, "     * Creates a store, initializes each state once, "
                + "and resolves each reducer once.");
        line(source, "     */");
        line(source, "    public " + simpleName + "() {");
        for (int index = 0; index < group.reductions.size(); index++) {
            ReductionDescriptor reduction = group.reductions.get(index);
            line(source, "        " + reduction.implementationName
                    + " reduction" + index + " = new "
                    + reduction.implementationName + "();");
            line(source, "        state" + index + " = reduction" + index
                    + ".supplier()."
                    + reduction.stateKind.supplierGetter + "();");
            line(source, "        reducer" + index + " = reduction" + index
                    + ".reducer();");
        }
        line(source, "    }");
        line(source, "");
        line(source, "    /**");
        line(source, "     * Applies every reduction to {@code value}.");
        line(source, "     *");
        line(source, "     * <p>If a reduction fails, earlier state changes "
                + "from this call are retained");
        line(source, "     * and later reductions are not invoked.");
        line(source, "     *");
        line(source, "     * @param value input passed unchanged to each "
                + "reducer, including {@code null}");
        line(source, "     */");
        line(source, "    public void add(" + group.sourceName + " value) {");
        for (int index = 0; index < group.reductions.size(); index++) {
            line(source, "        state" + index + " = reducer" + index
                    + ".apply(state" + index + ", value);");
        }
        line(source, "    }");
        for (int index = 0; index < group.reductions.size(); index++) {
            ReductionDescriptor reduction = group.reductions.get(index);
            line(source, "");
            line(source, "    /**");
            line(source, "     * Returns the current state of {@link "
                    + reduction.implementationName + "}.");
            line(source, "     *");
            if (reduction.stateKind == StateKind.OBJECT) {
                line(source, "     * @return the current state, possibly "
                        + "{@code null}");
            } else {
                line(source, "     * @return the current primitive state");
            }
            line(source, "     */");
            line(source, "    public " + reduction.stateType + " "
                    + reduction.accessorName + "() {");
            line(source, "        return state" + index + ";");
            line(source, "    }");
        }
        line(source, "}");
        return source.toString();
    }

    private static String decapitalize(String value) {
        int first = value.codePointAt(0);
        int lower = Character.toLowerCase(first);
        StringBuilder result = new StringBuilder(value.length());
        result.appendCodePoint(lower);
        result.append(value.substring(Character.charCount(first)));
        return result.toString();
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private static void line(StringBuilder source, String value) {
        source.append(value).append('\n');
    }

    private static final class ReductionGroup {
        private final TypeElement sourceElement;
        private final String sourceName;
        private final List<ReductionDescriptor> reductions =
                new ArrayList<ReductionDescriptor>();
        private boolean valid = true;

        private ReductionGroup(
                TypeElement sourceElement, String sourceName) {
            this.sourceElement = sourceElement;
            this.sourceName = sourceName;
        }
    }

    private static final class ReductionDescriptor {
        private final TypeElement implementation;
        private final String implementationName;
        private final StateKind stateKind;
        private final String stateType;
        private final String accessorName;

        private ReductionDescriptor(
                TypeElement implementation,
                String implementationName,
                StateKind stateKind,
                String stateType,
                String accessorName) {
            this.implementation = implementation;
            this.implementationName = implementationName;
            this.stateKind = stateKind;
            this.stateType = stateType;
            this.accessorName = accessorName;
        }
    }

    private static final class ResolvedReduction {
        private final StateKind stateKind;
        private final DeclaredType type;

        private ResolvedReduction(
                StateKind stateKind, DeclaredType type) {
            this.stateKind = stateKind;
            this.type = type;
        }
    }

    private enum StateKind {
        OBJECT(
                "io.github.jutil.reductionstore.Reduction",
                "Reduction",
                2,
                null,
                null,
                "get"),
        INT(
                "io.github.jutil.reductionstore.IntReduction",
                "IntReduction",
                1,
                "int",
                "io.github.jutil.reductionstore.IntReducer",
                "getAsInt"),
        LONG(
                "io.github.jutil.reductionstore.LongReduction",
                "LongReduction",
                1,
                "long",
                "io.github.jutil.reductionstore.LongReducer",
                "getAsLong"),
        DOUBLE(
                "io.github.jutil.reductionstore.DoubleReduction",
                "DoubleReduction",
                1,
                "double",
                "io.github.jutil.reductionstore.DoubleReducer",
                "getAsDouble");

        private final String contractType;
        private final String simpleName;
        private final int typeParameterCount;
        private final String primitiveType;
        private final String reducerType;
        private final String supplierGetter;

        StateKind(
                String contractType,
                String simpleName,
                int typeParameterCount,
                String primitiveType,
                String reducerType,
                String supplierGetter) {
            this.contractType = contractType;
            this.simpleName = simpleName;
            this.typeParameterCount = typeParameterCount;
            this.primitiveType = primitiveType;
            this.reducerType = reducerType;
            this.supplierGetter = supplierGetter;
        }

        private static StateKind forContract(CharSequence name) {
            for (StateKind stateKind : values()) {
                if (stateKind.contractType.contentEquals(name)) {
                    return stateKind;
                }
            }
            return null;
        }

        private String reducerType(String stateType, String sourceType) {
            if (this == OBJECT) {
                return "java.util.function.BiFunction<" + stateType + ", "
                        + sourceType + ", " + stateType + ">";
            }
            return reducerType + "<" + sourceType + ">";
        }
    }
}
