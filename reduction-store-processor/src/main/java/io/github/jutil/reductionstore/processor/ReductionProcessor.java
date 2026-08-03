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
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
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
    private static final String DEFINITION_ANNOTATION =
            "io.github.jutil.reductionstore.ReductionStoreDefinition";

    private Elements elements;
    private Types types;
    private Filer filer;
    private Messager messager;
    private TypeMirror runtimeExceptionType;
    private TypeMirror errorType;
    private final Set<String> objectNoArgMethodNames =
            new HashSet<String>();
    private final Map<String, ExplicitDefinition> explicitDefinitions =
            new LinkedHashMap<String, ExplicitDefinition>();
    private final Map<String, List<ExplicitDefinition>> definitionsByTarget =
            new LinkedHashMap<String, List<ExplicitDefinition>>();
    private final Set<String> generatedStoreNames = new HashSet<String>();
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
        if (roundEnvironment.processingOver()) {
            reportUnresolvedDefinitions();
            return false;
        }

        TypeElement reductionElement = elements.getTypeElement(
                StateKind.OBJECT.contractType);
        if (reductionElement == null) {
            return false;
        }

        TypeElement definitionAnnotation = elements.getTypeElement(
                DEFINITION_ANNOTATION);
        if (definitionAnnotation != null) {
            collectExplicitDefinitions(
                    roundEnvironment.getElementsAnnotatedWith(
                            definitionAnnotation));
        }
        processExplicitDefinitions();

        if (processedInitialSources) {
            return false;
        }
        processedInitialSources = true;
        processAutomaticDefinitions(roundEnvironment);
        return false;
    }

    private void processAutomaticDefinitions(
            RoundEnvironment roundEnvironment) {

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
            String automaticTarget = automaticTarget(reduction);
            if (automaticTarget != null
                    && definitionsByTarget.containsKey(automaticTarget)) {
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
    }

    private void collectExplicitDefinitions(
            Set<? extends Element> annotatedElements) {
        List<TypeElement> ordered = new ArrayList<TypeElement>();
        for (Element element : annotatedElements) {
            if (element instanceof TypeElement) {
                ordered.add((TypeElement) element);
            }
        }
        Collections.sort(ordered, new Comparator<TypeElement>() {
            @Override
            public int compare(TypeElement left, TypeElement right) {
                return left.getQualifiedName().toString().compareTo(
                        right.getQualifiedName().toString());
            }
        });
        for (TypeElement definitionElement : ordered) {
            String name = definitionElement.getQualifiedName().toString();
            if (!explicitDefinitions.containsKey(name)) {
                explicitDefinitions.put(
                        name, readExplicitDefinition(definitionElement));
            }
        }
    }

    private ExplicitDefinition readExplicitDefinition(
            TypeElement definitionElement) {
        ExplicitDefinition definition = new ExplicitDefinition(
                definitionElement);
        if (definitionElement.getKind() != ElementKind.INTERFACE
                || definitionElement.getNestingKind()
                        != NestingKind.TOP_LEVEL
                || !definitionElement.getTypeParameters().isEmpty()) {
            definitionError(
                    definition,
                    "ReductionStoreDefinition must annotate a top-level, "
                            + "non-generic interface");
        }

        readExplicitDefinitionValues(definition);
        return definition;
    }

    private void readExplicitDefinitionValues(
            ExplicitDefinition definition) {
        definition.input = null;
        definition.reductions.clear();
        definition.valuesUnresolved = false;

        AnnotationMirror annotation = definitionAnnotation(
                definition.element);
        if (annotation == null) {
            definitionError(
                    definition,
                    "Could not read ReductionStoreDefinition values");
            return;
        }

        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                elements.getElementValuesWithDefaults(annotation);
        AnnotationValue inputValue = null;
        AnnotationValue reductionsValue = null;
        for (Map.Entry<? extends ExecutableElement,
                ? extends AnnotationValue> entry : values.entrySet()) {
            String memberName = entry.getKey().getSimpleName().toString();
            if ("input".equals(memberName)) {
                inputValue = entry.getValue();
            } else if ("reductions".equals(memberName)) {
                reductionsValue = entry.getValue();
            }
        }

        definition.input = classReference(
                definition, inputValue, "input()");
        if (reductionsValue != null
                && reductionsValue.getValue() instanceof List<?>) {
            for (Object item : (List<?>) reductionsValue.getValue()) {
                if (item instanceof AnnotationValue) {
                    TypeReference reference = classReference(
                            definition,
                            (AnnotationValue) item,
                            "reductions()");
                    if (reference != null) {
                        definition.reductions.add(reference);
                    }
                }
            }
        } else {
            definitionError(
                    definition,
                    "ReductionStoreDefinition reductions() must be a class "
                            + "array");
        }

        if (definition.valuesUnresolved) {
            return;
        }
        if (definition.reductions.isEmpty()) {
            definitionError(
                    definition,
                    "ReductionStoreDefinition reductions() must not be empty");
        }
        Set<String> reductionNames = new HashSet<String>();
        for (TypeReference reduction : definition.reductions) {
            if (!reductionNames.add(reduction.name)) {
                definitionError(
                        definition,
                        "ReductionStoreDefinition contains duplicate reduction "
                                + reduction.displayName);
            }
        }
    }

    private AnnotationMirror definitionAnnotation(
            TypeElement definitionElement) {
        for (AnnotationMirror annotation
                : definitionElement.getAnnotationMirrors()) {
            Element annotationElement = annotation.getAnnotationType()
                    .asElement();
            if (annotationElement instanceof TypeElement
                    && ((TypeElement) annotationElement).getQualifiedName()
                            .contentEquals(DEFINITION_ANNOTATION)) {
                return annotation;
            }
        }
        return null;
    }

    private TypeReference classReference(
            ExplicitDefinition definition,
            AnnotationValue value,
            String memberName) {
        if (value == null || !(value.getValue() instanceof TypeMirror)) {
            if (value != null && "<error>".equals(value.getValue())) {
                definition.valuesUnresolved = true;
                return null;
            }
            definitionError(
                    definition,
                    "ReductionStoreDefinition " + memberName
                            + " must name a class");
            return null;
        }
        TypeMirror type = (TypeMirror) value.getValue();
        if (type.getKind() != TypeKind.DECLARED
                && type.getKind() != TypeKind.ERROR) {
            definitionError(
                    definition,
                    "ReductionStoreDefinition " + memberName
                            + " must name a declared type: " + type);
            return null;
        }
        Element typeElement = ((DeclaredType) type).asElement();
        if (!(typeElement instanceof TypeElement)) {
            definitionError(
                    definition,
                    "ReductionStoreDefinition " + memberName
                            + " could not be resolved: " + type);
            return null;
        }
        String name = ((TypeElement) typeElement).getQualifiedName()
                .toString();
        if (name.length() == 0) {
            name = type.toString();
        }
        return new TypeReference(name, type.toString());
    }

    private void processExplicitDefinitions() {
        for (ExplicitDefinition definition : explicitDefinitions.values()) {
            if (definition.completed) {
                continue;
            }
            if (definition.valuesUnresolved) {
                readExplicitDefinitionValues(definition);
            }
            if (definition.valuesUnresolved || definition.input == null) {
                continue;
            }
            TypeElement inputElement = resolve(definition.input);
            if (inputElement == null) {
                continue;
            }
            definition.inputElement = inputElement;
            if (definition.targetName == null) {
                PackageElement generatedPackage = elements.getPackageOf(
                        definition.element);
                definition.targetName = qualifiedStoreName(
                        generatedPackage, inputElement);
                registerDefinitionTarget(definition);
            }
        }

        for (ExplicitDefinition definition : explicitDefinitions.values()) {
            if (definition.completed) {
                continue;
            }
            if (definition.valuesUnresolved) {
                continue;
            }
            if (definition.failed) {
                definition.completed = true;
                continue;
            }
            if (definition.inputElement == null) {
                continue;
            }

            List<TypeElement> implementations = new ArrayList<TypeElement>();
            boolean unresolved = false;
            for (TypeReference reduction : definition.reductions) {
                TypeElement implementation = resolve(reduction);
                if (implementation == null
                        || containsErrorInHierarchy(
                                implementation.asType(),
                                new HashSet<String>())) {
                    unresolved = true;
                    definition.unresolvedReduction = reduction.displayName;
                    break;
                }
                implementations.add(implementation);
            }
            if (unresolved) {
                continue;
            }
            definition.unresolvedReduction = null;
            validateAndGenerateDefinition(definition, implementations);
            definition.completed = true;
        }
    }

    private void registerDefinitionTarget(ExplicitDefinition definition) {
        List<ExplicitDefinition> definitions = definitionsByTarget.get(
                definition.targetName);
        if (definitions == null) {
            definitions = new ArrayList<ExplicitDefinition>();
            definitionsByTarget.put(definition.targetName, definitions);
        }
        definitions.add(definition);
        if (definitions.size() > 1) {
            for (ExplicitDefinition conflicting : definitions) {
                if (!conflicting.collisionReported) {
                    error(conflicting.element,
                            "Multiple ReductionStoreDefinition declarations "
                                    + "target generated class "
                                    + definition.targetName);
                    conflicting.collisionReported = true;
                    conflicting.failed = true;
                }
            }
        }
    }

    private void validateAndGenerateDefinition(
            ExplicitDefinition definition,
            List<TypeElement> implementations) {
        TypeElement inputElement = definition.inputElement;
        PackageElement generatedPackage = elements.getPackageOf(
                definition.element);
        if (inputElement.getNestingKind() != NestingKind.TOP_LEVEL) {
            definitionError(
                    definition,
                    "ReductionStoreDefinition input type must be top-level: "
                            + inputElement.getQualifiedName());
        }
        if (!inputElement.getTypeParameters().isEmpty()) {
            definitionError(
                    definition,
                    "ReductionStoreDefinition input type must be non-generic: "
                            + inputElement.getQualifiedName());
        }
        if (!isTypeElementAccessible(inputElement, generatedPackage)) {
            definitionError(
                    definition,
                    "ReductionStoreDefinition input type is not accessible "
                            + "from generated package "
                            + packageName(generatedPackage) + ": "
                            + inputElement.getQualifiedName());
        }

        ReductionGroup group = new ReductionGroup(
                inputElement,
                inputElement.getQualifiedName().toString(),
                generatedPackage,
                definition.element,
                definition.element);
        for (TypeElement implementation : implementations) {
            List<ResolvedReduction> contracts = findReductionTypes(
                    implementation.asType());
            if (contracts.size() != 1) {
                definitionError(
                        definition,
                        "Explicit reduction class must implement exactly one "
                                + "supported reduction contract: "
                                + implementation.getQualifiedName());
                group.valid = false;
                continue;
            }
            if (implementation.getKind() != ElementKind.CLASS
                    || implementation.getModifiers().contains(
                            Modifier.ABSTRACT)) {
                definitionError(
                        definition,
                        "Explicit reduction must be a concrete class: "
                                + implementation.getQualifiedName());
                group.valid = false;
                continue;
            }
            addReduction(
                    group,
                    implementation,
                    contracts.get(0),
                    Collections.<String>emptySet(),
                    false,
                    definition.element);
        }

        validateNames(group);
        TypeElement existingType = elements.getTypeElement(
                definition.targetName);
        if (existingType != null
                && !generatedStoreNames.contains(definition.targetName)) {
            definitionError(
                    definition,
                    "Generated reduction store name conflicts with existing "
                            + "type " + definition.targetName);
            group.valid = false;
        }
        if (!definition.failed && group.valid) {
            generateStore(group);
        }
    }

    private TypeElement resolve(TypeReference reference) {
        TypeElement element = elements.getTypeElement(reference.name);
        if (element == null || element.asType().getKind() == TypeKind.ERROR) {
            return null;
        }
        return element;
    }

    private void reportUnresolvedDefinitions() {
        for (ExplicitDefinition definition : explicitDefinitions.values()) {
            if (definition.completed) {
                continue;
            }
            if (definition.valuesUnresolved) {
                error(definition.element,
                        "ReductionStoreDefinition class values remain "
                                + "unresolved after all processing rounds");
                definition.completed = true;
                continue;
            }
            if (definition.input != null
                    && resolve(definition.input) == null) {
                error(definition.element,
                        "ReductionStoreDefinition input type remains "
                                + "unresolved after all processing rounds: "
                                + definition.input.displayName);
            }
            for (TypeReference reduction : definition.reductions) {
                if (resolve(reduction) == null) {
                    error(definition.element,
                            "ReductionStoreDefinition reduction type remains "
                                    + "unresolved after all processing rounds: "
                                    + reduction.displayName);
                }
            }
            if (definition.unresolvedReduction != null) {
                error(definition.element,
                        "ReductionStoreDefinition reduction hierarchy remains "
                                + "unresolved after all processing rounds: "
                                + definition.unresolvedReduction);
            }
            definition.completed = true;
        }
    }

    private void definitionError(
            ExplicitDefinition definition, String message) {
        error(definition.element, message);
        definition.failed = true;
    }

    private String automaticTarget(ResolvedReduction reduction) {
        List<? extends TypeMirror> arguments =
                reduction.type.getTypeArguments();
        if (arguments.size() != reduction.stateKind.typeParameterCount
                || arguments.get(0).getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement input = (TypeElement) ((DeclaredType) arguments.get(0))
                .asElement();
        return qualifiedStoreName(elements.getPackageOf(input), input);
    }

    private String qualifiedStoreName(
            PackageElement generatedPackage, TypeElement inputElement) {
        String packageName = generatedPackage.getQualifiedName().toString();
        String simpleName = inputElement.getSimpleName().toString()
                + STORE_SUFFIX;
        return packageName.length() == 0
                ? simpleName : packageName + "." + simpleName;
    }

    private List<ResolvedReduction> findReductionTypes(TypeMirror type) {
        Map<String, ResolvedReduction> found =
                new LinkedHashMap<String, ResolvedReduction>();
        findReductionTypes(type, new HashSet<String>(), found);
        return new ArrayList<ResolvedReduction>(found.values());
    }

    private void findReductionTypes(
            TypeMirror type,
            Set<String> visited,
            Map<String, ResolvedReduction> found) {
        if (type.getKind() != TypeKind.DECLARED
                && type.getKind() != TypeKind.ERROR) {
            return;
        }
        DeclaredType declaredType = (DeclaredType) type;
        TypeElement typeElement = (TypeElement) declaredType.asElement();
        StateKind stateKind = StateKind.forContract(
                typeElement.getQualifiedName());
        if (stateKind != null) {
            String key = stateKind.contractType + "<" + declaredType + ">";
            found.put(key, new ResolvedReduction(stateKind, declaredType));
            return;
        }
        if (!visited.add(type.toString())) {
            return;
        }
        for (TypeMirror supertype : types.directSupertypes(declaredType)) {
            findReductionTypes(supertype, visited, found);
        }
    }

    private boolean containsErrorInHierarchy(
            TypeMirror type, Set<String> visited) {
        if (type.getKind() == TypeKind.ERROR) {
            return true;
        }
        if (type.getKind() != TypeKind.DECLARED
                || !visited.add(type.toString())) {
            return false;
        }
        DeclaredType declaredType = (DeclaredType) type;
        for (TypeMirror argument : declaredType.getTypeArguments()) {
            if (containsErrorType(argument)) {
                return true;
            }
        }
        for (TypeMirror supertype : types.directSupertypes(declaredType)) {
            if (containsErrorInHierarchy(supertype, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsErrorType(TypeMirror type) {
        if (type.getKind() == TypeKind.ERROR) {
            return true;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return containsErrorType(((ArrayType) type).getComponentType());
        }
        if (type.getKind() == TypeKind.DECLARED) {
            for (TypeMirror argument
                    : ((DeclaredType) type).getTypeArguments()) {
                if (containsErrorType(argument)) {
                    return true;
                }
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
            PackageElement generatedPackage = elements.getPackageOf(
                    sourceElement);
            group = new ReductionGroup(
                    sourceElement,
                    sourceName,
                    generatedPackage,
                    sourceElement,
                    sourceElement);
            groups.put(sourceName, group);
        }

        addReduction(
                group,
                implementation,
                reduction,
                currentTopLevelTypes,
                true,
                implementation);
    }

    private void addReduction(
            ReductionGroup group,
            TypeElement implementation,
            ResolvedReduction reduction,
            Set<String> currentTopLevelTypes,
            boolean requireCurrentInput,
            Element diagnosticElement) {
        List<? extends TypeMirror> arguments =
                reduction.type.getTypeArguments();
        if (arguments.size() != reduction.stateKind.typeParameterCount) {
            error(diagnosticElement,
                    reduction.stateKind.simpleName
                            + " must not be implemented as a raw type: "
                            + implementation.getQualifiedName());
            group.valid = false;
            return;
        }

        TypeMirror sourceType = arguments.get(0);
        TypeMirror stateType = reduction.stateKind == StateKind.OBJECT
                ? arguments.get(1) : null;
        if (sourceType.getKind() != TypeKind.DECLARED) {
            error(diagnosticElement,
                    "Reduction input type must be a declared top-level type: "
                            + implementation.getQualifiedName());
            group.valid = false;
            return;
        }
        if (!requireCurrentInput
                && !types.isSameType(
                        sourceType, group.sourceElement.asType())) {
            error(diagnosticElement,
                    "Reduction input type " + sourceType
                            + " does not exactly match definition input "
                            + group.sourceName + ": "
                            + implementation.getQualifiedName());
            group.valid = false;
            return;
        }

        boolean valid = true;
        TypeElement sourceElement = group.sourceElement;
        String sourceName = group.sourceName;
        if (sourceElement.getNestingKind() != NestingKind.TOP_LEVEL) {
            error(diagnosticElement,
                    "Reduction input type must be top-level: " + sourceType);
            valid = false;
        }
        if (!sourceElement.getTypeParameters().isEmpty()
                || !((DeclaredType) sourceType).getTypeArguments().isEmpty()) {
            error(diagnosticElement,
                    "Reduction input type must be non-generic: " + sourceType);
            valid = false;
        }
        if (requireCurrentInput
                && !currentTopLevelTypes.contains(sourceName)) {
            error(diagnosticElement,
                    "Reduction input type must be compiled in the same full "
                            + "javac invocation: " + sourceName);
            valid = false;
        }

        PackageElement generatedPackage = group.generatedPackage;
        if (!implementation.getTypeParameters().isEmpty()) {
            error(diagnosticElement,
                    "Reduction implementation classes must be non-generic: "
                            + implementation.getQualifiedName());
            valid = false;
        }
        if (implementation.getNestingKind() == NestingKind.MEMBER
                && !implementation.getModifiers().contains(Modifier.STATIC)) {
            error(diagnosticElement,
                    "Member reduction implementation classes must be static: "
                            + implementation.getQualifiedName());
            valid = false;
        }
        if (!isTypeElementAccessible(implementation, generatedPackage)) {
            error(diagnosticElement,
                    "Reduction implementation is not accessible from generated "
                            + "package " + packageName(generatedPackage)
                            + ": " + implementation.getQualifiedName());
            valid = false;
        }
        if (!hasUsableNoArgConstructor(
                implementation, generatedPackage, diagnosticElement)) {
            valid = false;
        }
        if (reduction.stateKind == StateKind.OBJECT) {
            if (!isRepresentableStateType(stateType)) {
                error(diagnosticElement,
                        "Reduction state type must be a concrete source-level "
                                + "reference type: " + stateType);
                valid = false;
            } else if (!isAccessible(stateType, generatedPackage)) {
                error(diagnosticElement,
                        "Reduction state type is not accessible from generated "
                                + "package " + packageName(generatedPackage)
                                + ": " + stateType + " in "
                                + implementation.getQualifiedName());
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
                diagnosticElement,
                implementation.getQualifiedName().toString(),
                reduction.stateKind,
                reduction.stateKind == StateKind.OBJECT
                        ? stateType.toString()
                        : reduction.stateKind.primitiveType,
                accessorName));
    }

    private boolean hasUsableNoArgConstructor(
            TypeElement implementation,
            PackageElement generatedPackage,
            Element diagnosticElement) {
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
                error(diagnosticElement,
                        "Reduction no-argument constructor must not declare "
                                + "checked exceptions: "
                                + implementation.getQualifiedName());
                return false;
            }
            return true;
        }
        error(diagnosticElement,
                "Reduction implementation must have a no-argument constructor "
                        + "accessible from generated package "
                        + packageName(generatedPackage) + ": "
                        + implementation.getQualifiedName());
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
                error(reduction.diagnosticElement,
                        "Reduction class name produces an invalid accessor: "
                                + accessor + "()");
                group.valid = false;
            }
            if (objectNoArgMethodNames.contains(accessor)) {
                error(reduction.diagnosticElement,
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
                error(previous.diagnosticElement, message);
                error(reduction.diagnosticElement, message);
                group.valid = false;
            }
        }
    }

    private void generateStore(ReductionGroup group) {
        String packageName = group.generatedPackage.getQualifiedName()
                .toString();
        String simpleName = group.sourceElement.getSimpleName().toString()
                + STORE_SUFFIX;
        String qualifiedName = packageName.length() == 0
                ? simpleName : packageName + "." + simpleName;

        List<Element> originatingElements = new ArrayList<Element>();
        originatingElements.add(group.originatingElement);
        if (!group.originatingElement.equals(group.sourceElement)) {
            originatingElements.add(group.sourceElement);
        }
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
            generatedStoreNames.add(qualifiedName);
        } catch (IOException exception) {
            error(group.diagnosticElement,
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
        line(source, "     *");
        line(source, "     * @throws NullPointerException if a reduction's "
                + "supplier or reducer method returns {@code null}");
        line(source, "     */");
        line(source, "    public " + simpleName + "() {");
        for (int index = 0; index < group.reductions.size(); index++) {
            ReductionDescriptor reduction = group.reductions.get(index);
            line(source, "        " + reduction.implementationName
                    + " reduction" + index + " = new "
                    + reduction.implementationName + "();");
            line(source, "        state" + index
                    + " = java.util.Objects.requireNonNull(");
            line(source, "                reduction" + index + ".supplier(),");
            line(source, "                \"" + reduction.implementationName
                    + ".supplier() returned null\")."
                    + reduction.stateKind.supplierGetter + "();");
            line(source, "        reducer" + index
                    + " = java.util.Objects.requireNonNull(");
            line(source, "                reduction" + index + ".reducer(),");
            line(source, "                \"" + reduction.implementationName
                    + ".reducer() returned null\");");
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
        private final PackageElement generatedPackage;
        private final Element originatingElement;
        private final Element diagnosticElement;
        private final List<ReductionDescriptor> reductions =
                new ArrayList<ReductionDescriptor>();
        private boolean valid = true;

        private ReductionGroup(
                TypeElement sourceElement,
                String sourceName,
                PackageElement generatedPackage,
                Element originatingElement,
                Element diagnosticElement) {
            this.sourceElement = sourceElement;
            this.sourceName = sourceName;
            this.generatedPackage = generatedPackage;
            this.originatingElement = originatingElement;
            this.diagnosticElement = diagnosticElement;
        }
    }

    private static final class ReductionDescriptor {
        private final TypeElement implementation;
        private final Element diagnosticElement;
        private final String implementationName;
        private final StateKind stateKind;
        private final String stateType;
        private final String accessorName;

        private ReductionDescriptor(
                TypeElement implementation,
                Element diagnosticElement,
                String implementationName,
                StateKind stateKind,
                String stateType,
                String accessorName) {
            this.implementation = implementation;
            this.diagnosticElement = diagnosticElement;
            this.implementationName = implementationName;
            this.stateKind = stateKind;
            this.stateType = stateType;
            this.accessorName = accessorName;
        }
    }

    private static final class ExplicitDefinition {
        private final TypeElement element;
        private final List<TypeReference> reductions =
                new ArrayList<TypeReference>();
        private TypeReference input;
        private TypeElement inputElement;
        private String targetName;
        private String unresolvedReduction;
        private boolean failed;
        private boolean completed;
        private boolean collisionReported;
        private boolean valuesUnresolved;

        private ExplicitDefinition(TypeElement element) {
            this.element = element;
        }
    }

    private static final class TypeReference {
        private final String name;
        private final String displayName;

        private TypeReference(String name, String displayName) {
            this.name = name;
            this.displayName = displayName;
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
