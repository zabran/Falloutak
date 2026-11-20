plugins {
    java
    jacoco   // coverage: every rule tested outside the game (Marek 11-18: "I would like to test only UX and UI in game")
}

group = "cz.lollywood"
version = "0.40.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("io.github.toxicity188:bettermodel-api:2.2.0")          // the creature models (Creatures, BmModels), optional; 3.x needs Java 25
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:2.2.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.11.0")   // Paper ships Gson at run time
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")   // the text layout tests build Adventure components
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3")   // a mock server: Battle, Items, Menus tested without a client
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports { csv.required.set(true); html.required.set(true); xml.required.set(true) }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
