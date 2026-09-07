allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    dependencies {
        add("implementation", "jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
        add("implementation", "jakarta.inject:jakarta.inject-api:2.0.1")
    }
}
