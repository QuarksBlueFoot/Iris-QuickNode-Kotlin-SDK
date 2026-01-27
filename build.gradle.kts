plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
}

allprojects {
    group = "xyz.selenus"
    version = "1.1.0"
    
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    
    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }
    
    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation(kotlin("stdlib"))
        testImplementation(kotlin("test"))
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    }
    
    tasks.withType<Test> {
        useJUnitPlatform()
    }
    
    val sourcesJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        from(project.the<SourceSetContainer>()["main"].allSource)
    }
    
    val javadocJar by tasks.registering(Jar::class) {
        archiveClassifier.set("javadoc")
        from(rootProject.file("README.md"))
    }
    
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifact(sourcesJar)
                artifact(javadocJar)
                
                pom {
                    name.set(project.name)
                    description.set("Iris SDK - QuickNode Solana SDK for Kotlin")
                    url.set("https://github.com/QuarksBlueFoot/Iris-QuickNode-Kotlin-SDK")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("BoobiesInteractive")
                            name.set("Bluefoot Labs")
                            email.set("quark@bluefoot.tech")
                        }
                    }
                    scm {
                        url.set("https://github.com/QuarksBlueFoot/Iris-QuickNode-Kotlin-SDK")
                        connection.set("scm:git:git://github.com/QuarksBlueFoot/Iris-QuickNode-Kotlin-SDK.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "Staging"
                url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }
}

