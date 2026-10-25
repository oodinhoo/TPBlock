plugins {
    java
}

group = "fr.tpblock"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 26.x coordinate: no more "-R0.1-SNAPSHOT", the format is <version>.build.<number>-<stage>.
    compileOnly("io.papermc.paper:paper-api:26.2.build.119-stable")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}
