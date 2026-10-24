plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.tobiasguta"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Provided by Burp itself; must never be bundled in our extension JAR.
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.7")
    implementation("com.graphql-java:graphql-java:26.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.4")

    testImplementation(kotlin("test-junit5"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest.attributes["Implementation-Title"] = "GQLens"
    manifest.attributes["Implementation-Version"] = project.version
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
