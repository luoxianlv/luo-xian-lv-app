plugins { java }

repositories { mavenCentral() }

dependencies {
    implementation(gradleApi())
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
