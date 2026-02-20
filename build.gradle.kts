plugins {
    kotlin("jvm") version "2.3.0"
    id("java")
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
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

group = "org.lewapnoob.LewapEngine"
val MainClass = "org.lewapnoob.LewapEngine.MainKt"

tasks {
    shadowJar {
        archiveFileName.set("LewapEngine.jar")
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
    archiveBaseName.set("LewapEngine_RAW")
    archiveVersion.set("")
    archiveClassifier.set("")
}