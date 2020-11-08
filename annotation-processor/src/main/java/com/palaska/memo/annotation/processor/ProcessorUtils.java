package com.palaska.memo.annotation.processor;

import javax.lang.model.element.TypeElement;

class ProcessorUtils {
    static String className(TypeElement element) {
        return element.getQualifiedName().toString();
    }

    static String simpleClassName(TypeElement element) {
        String className = className(element);
        int lastDot = className.lastIndexOf('.');
        return className.substring(lastDot + 1);
    }

    static String packageName(TypeElement cls) {
        String className = className(cls);
        String packageName = null;
        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) {
            packageName = className.substring(0, lastDot);
        }

        return packageName;
    }
}
