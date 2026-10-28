plugins {
    java
}

group = "fr.tpblock"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 26.x coordinate: no more "-R0.1-SNAPSHOT", the format is <version>.build.<number>-<stage>.
    compileOnly("io.papermc.paper:paper-api:26.3.build.227-stable")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // "No compiler warning" (CONTRIBUTING.md) is enforced, not just asked.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    // Read at configuration time and declared as an input: a version bump reruns the task.
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}
