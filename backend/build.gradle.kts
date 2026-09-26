plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.gitutility"
version = "1.0.0-SNAPSHOT"
description = "Bidirectional Git Mirroring Utility with Spring Boot, AMQP Queues, and DLQ"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val jgitVersion = "7.8.0.202609011348-r"
val bouncyCastleVersion = "1.85"
val cucumberVersion = "8.0.1"

dependencies {
    // Boot 4 modular starters (web → webmvc)
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    runtimeOnly("com.h2database:h2")

    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:$jgitVersion")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    implementation("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion")
    implementation("org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-amqp-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.cucumber:cucumber-java:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:$cucumberVersion")
}

tasks.withType<Test> {
    description = "Regression: JUnit and Cucumber together. -PtestEngine=cucumber or -PtestEngine=junit-jupiter runs one engine."
    useJUnitPlatform {
        val engine = providers.gradleProperty("testEngine").orNull
        if (!engine.isNullOrBlank()) {
            includeEngines(engine)
        }
    }
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    val filtered = classpath?.filter { !it.name.startsWith("lombok-") }
    if (filtered != null) {
        classpath = filtered
    }
}
