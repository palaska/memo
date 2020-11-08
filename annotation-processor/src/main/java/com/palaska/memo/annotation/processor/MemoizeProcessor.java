package com.palaska.memo.annotation.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import com.google.auto.service.AutoService;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.squareup.javapoet.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("com.palaska.memo.annotation.processor.Memoize")
@SupportedSourceVersion(SourceVersion.RELEASE_15)
@AutoService(Processor.class)
public class MemoizeProcessor extends AbstractProcessor {

    private static final String ORIGINAL_INSTANCE = "__original";
    private static final String GENERATED_CLASS_SUFFIX = "Memoized";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation: annotations) {
            Set<? extends Element> annotatedElements = roundEnv.getElementsAnnotatedWith(annotation);

            if (annotatedElements.isEmpty()) {
                continue;
            }

            TypeElement enclosingClass = enclosingElement(annotatedElements);
            ExecutableElement constructor = getConstructor(enclosingClass);

            try {
                generateMemoizedClass(enclosingClass, constructor, annotatedElements);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return true;
    }

    private void generateMemoizedClass(TypeElement cls, ExecutableElement constructor, Set<? extends Element> annotatedMethods) throws IOException {
        Preconditions.checkArgument(!cls.getModifiers().contains(Modifier.ABSTRACT), "@Memoize annotation cannot be used in abstract classes.");
        Preconditions.checkArgument(!cls.getModifiers().contains(Modifier.STATIC), "@Memoize annotation cannot be used in static classes.");
        Preconditions.checkArgument(!cls.getModifiers().contains(Modifier.PRIVATE), "@Memoize annotation cannot be used in private classes.");

        Set<MethodSpec> methodSpecs = annotatedMethods.stream()
                .map(s -> MemoizeProcessor.getMethodSpec(s, cls))
                .collect(Collectors.toSet());

        MethodSpec constructorSpec = getConstructorSpec(cls, constructor, methodSpecs);

        TypeSpec generatedClass = TypeSpec.classBuilder(cls.getSimpleName() + GENERATED_CLASS_SUFFIX)
                .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", CodeBlock.builder().add("\"Memoize.generator\"").build()).build())
                .addModifiers(MemoizeProcessor.getModifiers(cls.getModifiers()))
                .addMethod(constructorSpec)
                .addMethods(methodSpecs)
                .addMethods(nonAnnotatedMethods(cls))
//                .addStaticBlock(CodeBlock.builder().add(nonAnnotatedMethods(cls)).build())
                .addMethods(helperMethods())
                .addField(TypeName.get(cls.asType()), ORIGINAL_INSTANCE, Modifier.PRIVATE, Modifier.FINAL)
                .addFields(caches(methodSpecs))
                .build();

        JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(generatedClassName(cls));
        JavaFile javaFile = JavaFile.builder(ProcessorUtils.packageName(cls), generatedClass)
                .build();

        try (PrintWriter out = new PrintWriter(builderFile.openWriter())) {
            out.println(javaFile);
        }
    }

    private static Set<MethodSpec> nonAnnotatedMethods(TypeElement cls) {
        return cls.getEnclosedElements()
                .stream()
                .filter(element -> element.getKind().equals(ElementKind.METHOD))
                .filter(element -> !element.getModifiers().contains(Modifier.PRIVATE))
                .filter(element -> element.getAnnotation(Memoize.class) == null)
                .map(element -> (ExecutableElement) element)
                .map(MemoizeProcessor::forwardMethod)
                .collect(Collectors.toSet());
    }

    private static MethodSpec forwardMethod(ExecutableElement m) {
        List<ParameterSpec> parameterSpecs = m.getParameters()
                .stream()
                .map(MemoizeProcessor::getParameterSpec)
                .collect(Collectors.toList());

        return MethodSpec.methodBuilder(m.getSimpleName().toString())
                .addModifiers(m.getModifiers())
                .returns(TypeName.get(m.getReturnType()))
                .addParameters(parameterSpecs)
                .addExceptions(getExceptionTypes(m))
                .addCode(forwardMethodCode(m, parameterSpecs))
                .build();
    }

    private static CodeBlock forwardMethodCode(ExecutableElement m, List<ParameterSpec> parameterSpecs) {
        boolean isStatic = m.getModifiers().contains(Modifier.STATIC);
        boolean isVoid = TypeName.get(m.getReturnType()).equals(TypeName.VOID);

        String className = m.getEnclosingElement().getSimpleName().toString();
        String paramNames = parameterSpecs.stream().map(s -> s.name).collect(Collectors.joining(", "));

        return CodeBlock.builder()
                .add(isVoid ? "" : "return ")
                .add(isStatic ? className : ORIGINAL_INSTANCE)
                .add("." + m.getSimpleName() + "(" + paramNames + ");\n")
                .build();
    }

    private static Set<FieldSpec> caches(Set<MethodSpec> methodSpecs) {
        return methodSpecs
                .stream()
                .map(s -> FieldSpec.builder(
                        ParameterizedTypeName.get(ClassName.get(Cache.class), TypeName.get(String.class), s.returnType.box()),
                        methodCacheId(s.name, s.returnType, s.modifiers, s.parameters),
                        Modifier.PRIVATE, Modifier.FINAL).build())
                .collect(Collectors.toSet());
    }

    private static Modifier[] getModifiers(Set<Modifier> modifiers) {
        Modifier[] result = new Modifier[modifiers.size()];

        int i = 0;
        for (Modifier modifier : modifiers) {
            result[i] = modifier;
            i += 1;
        }

        return result;
    }

    private static MethodSpec getConstructorSpec(TypeElement cls, ExecutableElement el, Set<MethodSpec> methods) {
        Set<ParameterSpec> parameterSpecs = el.getParameters()
                .stream()
                .map(MemoizeProcessor::getParameterSpec)
                .collect(Collectors.toSet());

        return MethodSpec.constructorBuilder()
                .addModifiers(el.getModifiers())
                .addParameters(parameterSpecs)
                .addExceptions(getExceptionTypes(el))
                .addStatement("this.$L = new $N(" + String.join(",", parameterSpecs.stream().map(spec -> spec.name).collect(Collectors.toSet())) + ")", ORIGINAL_INSTANCE, cls.getSimpleName())
                .addCode(cacheInitializations(methods))
                .build();
    }

    private static CodeBlock cacheInitializations(Set<MethodSpec> methods) {
        return methods
                .stream()
                .map(s -> CodeBlock.builder()
                        .add("this." + methodCacheId(s.name, s.returnType, s.modifiers, s.parameters) + " = $T.newBuilder()", CacheBuilder.class)
                        .add("\n  .build();\n")
                        .build())
                .reduce((a, b) -> a.toBuilder().add(b).build())
                .orElseGet(() -> CodeBlock.builder().build());
    }

    private static MethodSpec getMethodSpec(Element element, TypeElement cls) {
        Preconditions.checkArgument(element.getKind().equals(ElementKind.METHOD), "@Memoize annotation can only be used with methods.");
        Preconditions.checkArgument(!element.getModifiers().contains(Modifier.PRIVATE), "@Memoize annotation cannot be used with private methods.");
        Preconditions.checkArgument(!element.getModifiers().contains(Modifier.STATIC), "@Memoize annotation cannot be used with static methods.");

        ExecutableElement el = (ExecutableElement) element;
        List<ParameterSpec> parameterSpecs = el.getParameters()
                .stream()
                .map(MemoizeProcessor::getParameterSpec)
                .collect(Collectors.toList());

        String name = el.getSimpleName().toString();
        String paramNames = parameterSpecs.stream().map(s -> s.name).collect(Collectors.joining(", "));
        Set<Modifier> modifiers = el.getModifiers();
        TypeName returns = TypeName.get(el.getReturnType());
        String cacheId = methodCacheId(name, returns, modifiers, parameterSpecs);

        return MethodSpec.methodBuilder(name)
                .addModifiers(modifiers)
                .addParameters(parameterSpecs)
                .returns(returns)
                .addExceptions(getExceptionTypes(el))
                .addCode("String cacheKey = " + generatedClassName(cls) + ".cacheKey(" + paramNames + ");\n")
                .addCode("$T result = this." + cacheId + ".getIfPresent(cacheKey);\n", returns.box())
                .addCode(CodeBlock.builder()
                        .add("\n")
                        .beginControlFlow("if (result != null)")
                        .addStatement("return result")
                        .endControlFlow()
                        .add("\n")
                        .build())
                .addCode("$T computed = " + ORIGINAL_INSTANCE + "." + name + "(" + paramNames + ");\n", returns)
                .addCode("this." + cacheId + ".put(cacheKey, computed);\n")
                .addCode("return computed;\n")
                .build();
    }

    private static String generatedClassName(TypeElement cls) {
        return cls.getSimpleName() + GENERATED_CLASS_SUFFIX;
    }

    private static Set<MethodSpec> helperMethods() {
        MethodSpec hashAll = MethodSpec.methodBuilder("hashAll")
                .varargs()
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(Object[].class, "args")
                .addCode("return \"_\" + $T.toUnsignedLong($T.hash(args));\n", Integer.class, Objects.class)
                .build();

        MethodSpec cacheKey = MethodSpec.methodBuilder("cacheKey")
                .varargs()
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(Object[].class, "args")
                .addCode("return \"_key\" + hashAll(args);\n")
                .build();

        return Set.of(hashAll, cacheKey);
    }

    private static List<TypeName> getExceptionTypes(ExecutableElement el) {
        return el.getThrownTypes().stream().map(TypeName::get).collect(Collectors.toList());
    }

    private static ParameterSpec getParameterSpec(VariableElement param) {
        return ParameterSpec.builder(TypeName.get(param.asType()), param.getSimpleName().toString()).build();
    }

    private static ExecutableElement getConstructor(TypeElement cls) {
        return (ExecutableElement) cls.getEnclosedElements()
                .stream()
                .filter(element -> element.getKind().equals(ElementKind.CONSTRUCTOR))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Could not find constructor."));
    }

    private static TypeElement enclosingElement(Set<? extends Element> elements) {
        Preconditions.checkArgument(
                elements.size() > 0,
                "Need at least one element to find the enclosing element.");

        return (TypeElement) elements.iterator().next().getEnclosingElement();
    }

    private static String methodCacheId(String name, TypeName returnType, Set<Modifier> modifiers, List<ParameterSpec> parameterSpecs) {
        return "_cache" + hashAll(name, returnType, modifiers, parameterSpecs);
    }

    private static String hashAll(Object... args) {
        return "_" + Integer.toUnsignedLong(Objects.hash(args));
    }
}

