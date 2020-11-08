package com.palaska.memo.annotation.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;

import com.google.auto.service.AutoService;
import com.google.common.base.Preconditions;
import com.google.common.cache.LoadingCache;
import com.squareup.javapoet.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("com.palaska.memo.annotation.processor.Memoize")
@SupportedSourceVersion(SourceVersion.RELEASE_15)
@AutoService(Processor.class)
public class MemoizeProcessor extends AbstractProcessor {
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

    private void generateMemoizedClass(TypeElement cls, ExecutableElement constructor, Set<? extends Element> methods) throws IOException {
        Set<MethodSpec> methodSpecs = methods.stream()
                .map(MemoizeProcessor::getMethodSpec)
                .collect(Collectors.toSet());

        MethodSpec constructorSpec = getConstructorSpec(cls, constructor, methodSpecs);


        TypeSpec generatedClass = TypeSpec.classBuilder(cls.getSimpleName() + "Memoized")
                .addModifiers(MemoizeProcessor.getModifiers(cls.getModifiers()))
                .addMethod(constructorSpec)
                .addMethods(methodSpecs)
                .addField(TypeName.get(cls.asType()), "_original", Modifier.PRIVATE, Modifier.FINAL)
                .addFields(caches(methodSpecs))
                .build();

        JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(cls.getSimpleName() + "Memoized");
        JavaFile javaFile = JavaFile.builder(ProcessorUtils.packageName(cls), generatedClass)
                .build();

        try (PrintWriter out = new PrintWriter(builderFile.openWriter())) {
            FileWriter w = new FileWriter(out);
            out.println(javaFile);

//            w.printPackageName(className);
        }
    }

    private static Set<FieldSpec> caches(Set<MethodSpec> methodSpecs) {
        return methodSpecs
                .stream()
                .map(s -> FieldSpec.builder(
                        LoadingCache.class,
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
                .addStatement("this._original = new $N(" + String.join(",", parameterSpecs.stream().map(spec -> spec.name).collect(Collectors.toSet())) + ")", cls.getSimpleName())
                .addCode(cacheInitializations(methods))
                .build();
    }

    private static String cacheInitializations(Set<MethodSpec> methods) {
        return methods
                .stream()
                .map(s -> "this." + methodCacheId(s.name, s.returnType, s.modifiers, s.parameters) + " = new LoadingCache<int, " + s.returnType + ">()")
                .collect(Collectors.joining(";\n")) + ";\n";
    }

    private static MethodSpec getMethodSpec(Element element) {
        Preconditions.checkArgument(element.getKind().equals(ElementKind.METHOD), "@Memoize annotation can only be used with methods.");

        ExecutableElement el = (ExecutableElement) element;
        List<ParameterSpec> parameterSpecs = el.getParameters()
                .stream()
                .map(MemoizeProcessor::getParameterSpec)
                .collect(Collectors.toList());

        String name = el.getSimpleName().toString();
        Set<Modifier> modifiers = el.getModifiers();
        TypeName returns = TypeName.get(el.getReturnType());

        return MethodSpec.methodBuilder(name)
                .addModifiers(modifiers)
                .addParameters(parameterSpecs)
                .returns(returns)
                .addExceptions(getExceptionTypes(el))
                .addCode("this." + methodCacheId(name, returns, modifiers, parameterSpecs) + ".get();")
                .addCode("\n")
                .build();
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
        return "_cache_" + Integer.toUnsignedLong(Objects.hash(name, returnType, modifiers, parameterSpecs));
    }

    class FileWriter {
        private final PrintWriter writer;

        FileWriter(PrintWriter writer) {
            this.writer = writer;
        }

        void printPackageName(String className) {
            String packageName = null;
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                packageName = className.substring(0, lastDot);
            }

            if (packageName != null) {
                writer.print("package ");
                writer.print(packageName);
                writer.println(";");
                writer.println();
            }
        }
    }
}

