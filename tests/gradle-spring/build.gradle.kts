plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework:spring-context:6.2.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
