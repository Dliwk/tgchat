import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }
    dependencies {
        classpath("org.yaml:snakeyaml:1.26")
        classpath("org.jlleitschuh.gradle:ktlint-gradle:10.1.0")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.6.10"
    id("com.github.johnrengelman.shadow") version "5.2.0"
    id("org.jlleitschuh.gradle.ktlint") version "10.1.0"
}

group = "su.dromanov"

val cfg: Map<String, String> = Yaml()
    .load(FileInputStream("$projectDir/src/main/resources/plugin.yml"))
val pluginVersion = cfg.get("version")
val spigotApiVersion = cfg.get("api-version")
val retrofitVersion = "2.7.1"
version = pluginVersion as Any

repositories {
    mavenCentral()
    maven(
        url = "https://hub.spigotmc.org/nexus/content/repositories/snapshots/"
    )
    maven(url = "https://jitpack.io")
    maven(url = "https://oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion-R0.1-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.8.7")
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-gson:$retrofitVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:4.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
    implementation("com.vdurmont:emoji-java:5.1.1")
}

defaultTasks("shadowJar")

tasks {
    named<ShadowJar>("shadowJar") {
        archiveFileName.set(
            "tgchat-$spigotApiVersion-v$pluginVersion.jar"
        )
    }
    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }
}
