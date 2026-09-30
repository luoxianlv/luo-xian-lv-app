import app.luoxianlv.buildlogic.ExportCompileSdk
import app.luoxianlv.buildlogic.ExportNativeBuildReport
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

buildscript {
    // 外部脚本不继承 plugins DSL 的编译 classpath，复用根项目已解析的同一组插件。
    dependencies {
        classpath(files(rootProject.buildscript.configurations.getByName("classpath")))
    }
}

// 所有信息来自当前变体的 AGP 产物；指纹和 mapping 不接受人工配置。
val nativeRole = when (project.name) {
    "app-host" -> "host"
    "app-runtime" -> "runtime"
    "app-business" -> "business"
    else -> error("原生报告只适用于三层应用模块")
}
val sourceCommit = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }
val nativeSourceDirty = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "status", "--porcelain")
}.standardOutput.asText.map { it.isNotBlank() }
val nativeAndroid = extensions.getByType<ApplicationExtension>()

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar(Char::uppercaseChar)
        val report = tasks.register<ExportNativeBuildReport>("export${name}NativeBuildReport") {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            role.set(nativeRole)
            variantName.set(variant.name)
            applicationId.set(variant.applicationId)
            packageId.set(when (nativeRole) { "host" -> 0x80; "runtime" -> 0x7f; else -> 0x81 })
            r8.set(variant.isMinifyEnabled)
            resourceShrink.set(variant.shrinkResources)
            commit.set(sourceCommit)
            sourceDirty.set(nativeSourceDirty)
            toolchain.set("AGP ${com.android.builder.model.Version.ANDROID_GRADLE_PLUGIN_VERSION}; Gradle ${gradle.gradleVersion}; JDK ${System.getProperty("java.version")}; compileSdk ${nativeAndroid.compileSdk}; buildTools ${nativeAndroid.buildToolsVersion}")
            output.set(layout.buildDirectory.dir("native-report/${variant.name}"))
            if (variant.isMinifyEnabled) mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            val classViews = listOf("compile" to variant.compileConfiguration, "runtime" to variant.runtimeConfiguration)
                .map { (scope, configuration) ->
                    scope to configuration.incoming.artifactView {
                        attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
                    }.artifacts
                }
            val resolved = provider {
                classViews.flatMap { (scope, artifacts) ->
                        artifacts.artifacts.map { artifact ->
                            val component = artifact.id.componentIdentifier
                            val identity = when (component) {
                                is org.gradle.api.artifacts.component.ModuleComponentIdentifier -> "${component.group}:${component.module}:${component.version}"
                                is org.gradle.api.artifacts.component.ProjectComponentIdentifier -> "project:${component.projectPath}"
                                else -> "file:${artifact.file.name}"
                            }
                            "$scope|$identity|${artifact.file.name}" to artifact.file.absolutePath
                        }
                    }.toMap()
            }
            dependencyPaths.set(resolved)
            dependencyIdentities.set(resolved.map { it.keys.sorted() })
            dependencyFiles.from(classViews.map { it.second.artifactFiles })
        }
        val reportFiles = configurations.create("${variant.name}NativeReport") {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
        artifacts.add(reportFiles.name, report.flatMap { it.output.file("report.json") })
        if (nativeRole == "runtime") {
            val sdk = tasks.register<ExportCompileSdk>("export${name}RuntimeSdk") {
                this.sdk.set(layout.buildDirectory.file("native-sdk/${variant.name}/runtime-sdk.jar"))
                metadataSources.from(variant.compileConfiguration.incoming.artifactView {
                    attributes.attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-java-res")
                }.files)
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(sdk)
                .toGet(ScopedArtifact.CLASSES, { it.jars }, { it.directories })
            val sdkFiles = configurations.create("${variant.name}RuntimeSdk") {
                isCanBeConsumed = true
                isCanBeResolved = false
            }
            artifacts.add(sdkFiles.name, sdk.flatMap { it.sdk })
            report.configure { this.sdk.set(sdk.flatMap { it.sdk }) }
        } else {
            fun consume(suffix: String, path: String, exported: String) = configurations.create("${variant.name}Native$suffix") {
                isCanBeConsumed = false
                isCanBeResolved = true
                dependencies.add(project.dependencies.project(mapOf("path" to path, "configuration" to "${variant.name}$exported")))
            }
            val runtime = consume("RuntimeApk", ":app-runtime", "RuntimeApk")
            val runtimeReport = consume("RuntimeReport", ":app-runtime", "NativeReport")
            report.configure {
                runtimeApk.set(layout.file(provider { runtime.singleFile }))
                this.runtimeReport.set(layout.file(provider { runtimeReport.singleFile }))
                dependsOn(runtime, runtimeReport)
            }
            val contractSdk = consume("ContractSdk", ":hot-contract", "ContractSdk")
            if (nativeRole == "host") {
                val business = consume("BusinessApk", ":app-business", "BusinessApk")
                val businessReport = consume("BusinessReport", ":app-business", "NativeReport")
                report.configure {
                    sdk.set(layout.file(provider { contractSdk.singleFile }))
                    businessApk.set(layout.file(provider { business.singleFile }))
                    this.businessReport.set(layout.file(provider { businessReport.singleFile }))
                    dependsOn(contractSdk, business, businessReport)
                }
            } else {
                val runtimeSdk = consume("RuntimeSdk", ":app-runtime", "RuntimeSdk")
                dependencies.add("${variant.name}CompileOnly", files(contractSdk, runtimeSdk))
                report.configure { entryClass.set("app.luoxianlv.business.AppBusinessFactory") }
            }
        }
    }
}
