package org.treblereel.mcp.model;

public enum BuiltinQualifier {
    DEFAULT("@Default"), ANY("@Any"), NAMED("@Named");

    private final String annotation;

    BuiltinQualifier(String annotation) {
        this.annotation = annotation;
    }

    public String annotation() {
        return annotation;
    }
}
