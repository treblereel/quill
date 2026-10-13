package org.treblereel.mcp.model;

import java.util.List;

/** A statically declared HTTP endpoint and its retained route metadata. */
public record FrameworkEndpointRecord(
        int classId,
        String className,
        String methodName,
        String signature,
        String descriptor,
        String framework,
        List<String> httpMethods,
        List<String> classPaths,
        List<String> methodPaths,
        List<String> annotations,
        Integer declaringClassId,
        String declaringClassName,
        List<String> mappingSources) {

    public FrameworkEndpointRecord(int classId, String className, String methodName,
            String signature, String descriptor, String framework, List<String> httpMethods,
            List<String> classPaths, List<String> methodPaths, List<String> annotations) {
        this(classId, className, methodName, signature, descriptor, framework, httpMethods,
                classPaths, methodPaths, annotations, classId, className, List.of());
    }

    public FrameworkEndpointRecord {
        httpMethods = List.copyOf(httpMethods);
        classPaths = List.copyOf(classPaths);
        methodPaths = List.copyOf(methodPaths);
        annotations = List.copyOf(annotations);
        mappingSources = List.copyOf(mappingSources);
    }
}
