package com.mikeescom;

public enum IntentBuilderOption {
    FINAL_SETTERS("finalSetters"),
    COPY_CONSTRUCTOR("copyConstructor"),
    WITH_JAVADOC("withJavadoc"),
    PUBLIC_INTERFACES("publicInterface");

    private final String property;

    private IntentBuilderOption(final String property) {
        this.property = String.format("GenerateStepBuilder.%s", property);
    }

    public String getProperty() {
        return property;
    }
}
