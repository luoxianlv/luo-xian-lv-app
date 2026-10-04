import app.luoxianlv.buildlogic.ExportCompileSdk
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts

plugins { id("com.android.library") }
android {
    namespace = "app.luoxianlv.hot.contract"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents.onVariants { variant ->
    val sdk = tasks.register<ExportCompileSdk>("export${variant.name.replaceFirstChar(Char::uppercaseChar)}ContractSdk") {
        this.sdk.set(layout.buildDirectory.file("native-sdk/${variant.name}/host-contract-sdk.jar"))
    }
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(sdk)
        .toGet(ScopedArtifact.CLASSES, { it.jars }, { it.directories })
    val exported = configurations.create("${variant.name}ContractSdk") {
        isCanBeConsumed = true
        isCanBeResolved = false
    }
    artifacts.add(exported.name, sdk.flatMap { it.sdk })
}
