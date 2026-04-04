plugins {
    kotlin("jvm") version "2.3.20"
}

group = "com.farcsal"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    testImplementation(kotlin("test"))
    testImplementation("org.wiremock:wiremock:3.13.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "26" // Required for HTTP/3
    targetCompatibility = "25" // Kotlin uses this version by default
}
