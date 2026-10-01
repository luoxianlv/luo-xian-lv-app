import app.luoxianlv.buildlogic.BindNativeContract
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

buildscript {
    dependencies {
        classpath(files(rootProject.buildscript.configurations.getByName("classpath")))
    }
}

require(project.name in listOf("app-host", "app-business"))

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val contractConfiguration = configurations.create("${variant.name}BoundContractSdk") {
            isCanBeConsumed = false
            isCanBeResolved = true
            dependencies.add(project.dependencies.project(mapOf(
                "path" to ":hot-contract", "configuration" to "${variant.name}ContractSdk"
            )))
        }
        val binding = tasks.register<BindNativeContract>(
            "bind${variant.name.replaceFirstChar(Char::uppercaseChar)}NativeContract"
        ) {
            this.sdk.set(layout.file(provider { contractConfiguration.singleFile }))
            output.set(layout.buildDirectory.dir("generated/native-contract/${variant.name}"))
            dependsOn(contractConfiguration)
        }
        variant.sources.assets!!.addGeneratedSourceDirectory(binding) { it.output }
    }
}
