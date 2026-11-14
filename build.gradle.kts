plugins {
    java
    application
}

group = "greenhouse"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.h2database:h2:2.5.252")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.google.code.gson:gson:2.15.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // 目标字节码保持 21：云端 JVM 25 可运行，同时给将来换 JRE 留余地
    options.release = 21
    options.encoding = "UTF-8"
}

application {
    mainClass = "greenhouse.db.App"
    applicationDefaultJvmArgs = listOf("-Xmx256m", "-Dfile.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
