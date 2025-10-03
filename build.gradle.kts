plugins {
    kotlin("jvm") version "2.0.20"
    `java-library`
    `maven-publish`
    signing
}

group = "io.github.moregrayner.flowx"
version = "1.0"



java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Ref")
                description.set("Reference-Protected variable")
                url.set("https://github.com/moregrayner/Ref")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("moregrayner")
                        name.set("Your Name")  // 실제 이름으로 변경
                        email.set("your.email@example.com")  // 실제 이메일로 변경
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/moregrayner/Ref.git")
                    developerConnection.set("scm:git:ssh://github.com/moregrayner/Ref.git")
                    url.set("https://github.com/moregrayner/Ref")
                }
            }
        }
    }

    repositories {
        maven {
            name = "OSSRH"
            url = uri("https://central.sonatype.com/api/v1/publisher/upload")
            credentials {
                username = project.findProperty("108K9Q") as String? ?: ""
                password = project.findProperty("4RsXldpf5SzMzgmpcFjuhXCi0NeEXsbfc") as String? ?: ""
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}
repositories {
    mavenCentral()  // 이 부분이 누락되어 있었을 수 있습니다
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}