plugins {
    kotlin("jvm") version "2.0.21"
    // kotlinx-serialization-json đã là dependency từ trước nhưng thiếu compiler plugin nên
    // @Serializable không dùng được. Cần cho OTA manifest (JSON lồng nhiều tầng, parse tay
    // là chỗ dễ sinh lỗi nhất). Cùng version với backend/build.gradle.kts.
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "vn.aiview"
version = "1.0.0"

application {
    mainClass.set("vn.aiview.watchdog.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Coroutines - vòng lặp song song
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // HTTP Client - gọi Docker Socket API, Telegram, health endpoints
    implementation("io.ktor:ktor-client-core-jvm:3.0.1")
    implementation("io.ktor:ktor-client-cio-jvm:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.0.1")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // YAML config parsing
    implementation("org.yaml:snakeyaml:2.3")

    // SQLite + Exposed ORM - EventLogger DB
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.56.0")
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Test — bộ test đầu tiên của repo. Nhắm vào OTA verifier + gates: đó là chỗ một lỗi
    // không gây crash mà gây nhận sai gói cập nhật, nên không test thì không có cách nào biết.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "vn.aiview.watchdog.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

kotlin {
    jvmToolchain(21)
}
