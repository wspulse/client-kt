plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    jacoco
    `maven-publish`
}

group = "com.wspulse"
version = "0.2.1"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.slf4j.api)

    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.org.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Run integration tests against a live Go testserver."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    classpath = sourceSets["test"].runtimeClasspath
    testClassesDirs = sourceSets["test"].output.classesDirs
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            artifactId = "client-kt"

            pom {
                name.set("wspulse client-kt")
                description.set("WebSocket client library for Kotlin (JVM + Android) with auto-reconnect and exponential backoff")
                url.set("https://github.com/wspulse/client-kt")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("wspulse")
                        name.set("wspulse contributors")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/wspulse/client-kt.git")
                    developerConnection.set("scm:git:ssh://github.com/wspulse/client-kt.git")
                    url.set("https://github.com/wspulse/client-kt")
                }
            }
        }
    }
}
