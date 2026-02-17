plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("java")
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("io.ktor.plugin") version "2.3.8"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:windows-x86_64")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.ktor:ktor-server-core:2.3.8")
    implementation("io.ktor:ktor-server-netty:2.3.8")
    implementation("io.ktor:ktor-server-websockets:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("io.ktor:ktor-client-core:2.3.8")
    implementation("io.ktor:ktor-client-cio:2.3.8")       // Silnik klienta
    implementation("io.ktor:ktor-client-websockets:2.3.8") // Obsługa WebSockets
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

javafx {
    version = "21.0.3"
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.swing")
}

group = "org.lewapnoob.FileZero"
val MainClass = "org.lewapnoob.gridMap.GridMapKt"

application {
    mainClass.set(MainClass)
    applicationName = "FileZero"
}

tasks {
    shadowJar {
        archiveFileName.set("FileZero.jar")
        mergeServiceFiles()
        manifest {
            attributes["Main-Class"] = MainClass
        }
    }
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = MainClass
    }
}

tasks.jar {
    archiveBaseName.set("FileZero_RAW")
    archiveVersion.set("")
    archiveClassifier.set("")
}