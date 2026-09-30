package app.luoxianlv.buildlogic;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.junit.Test;

/** 真实 Kotlin 消费者必须通过模块元数据发现顶层函数，Java 可见性不足以证明 SDK 完整。 */
public final class KotlinSdkTest {
  @Test public void androidJavaResourcesRestoreTopLevelFunctionDiscoveryAndRemainDeterministic() throws Exception {
    Path work = Files.createTempDirectory("kotlin-sdk-"), producer = work.resolve("producer");
    Files.createDirectories(producer);
    Path source = work.resolve("Shared.kt");
    Files.writeString(source, "package sample\nfun sharedMessage(): String = \"可用\"\n", StandardCharsets.UTF_8);
    assertEquals(ExitCode.OK, compile(source, producer, "native-shared-api", stdlib()));
    Path classes = work.resolve("classes"), metadata = work.resolve("java-resources");
    try (var files = Files.walk(producer)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Path target = (file.toString().endsWith(".class") ? classes : metadata).resolve(producer.relativize(file));
        Files.createDirectories(target.getParent());
        Files.copy(file, target);
      }
    }
    Path without = work.resolve("without.jar"), sdk = work.resolve("sdk.jar"), repeated = work.resolve("repeated.jar");
    CompileSdk.write(List.of(), List.of(classes), without);
    CompileSdk.write(List.of(), List.of(classes), List.of(metadata), sdk);
    CompileSdk.write(List.of(), List.of(classes), List.of(metadata, metadata), repeated);
    assertEquals(NativeApkContents.hash(sdk), NativeApkContents.hash(repeated));
    Path consumer = work.resolve("Consumer.kt");
    Files.writeString(consumer, "package consumer\nimport sample.sharedMessage\nfun result(): String = sharedMessage()\n", StandardCharsets.UTF_8);
    assertEquals(ExitCode.COMPILATION_ERROR, compile(consumer, work.resolve("missing"), "consumer", stdlib() + File.pathSeparator + without));
    assertEquals(ExitCode.OK, compile(consumer, work.resolve("available"), "consumer", stdlib() + File.pathSeparator + sdk));
  }

  @Test public void differentKotlinModulesWithTheSameArchiveNameFailExplicitly() throws Exception {
    Path work = Files.createTempDirectory("kotlin-sdk-conflict-"), first = work.resolve("first"), second = work.resolve("second");
    Path left = work.resolve("Left.kt"), right = work.resolve("Right.kt");
    Files.writeString(left, "package sample\nfun left() = 1\n", StandardCharsets.UTF_8);
    Files.writeString(right, "package sample\nfun right() = 2\n", StandardCharsets.UTF_8);
    assertEquals(ExitCode.OK, compile(left, first, "same-module", stdlib()));
    assertEquals(ExitCode.OK, compile(right, second, "same-module", stdlib()));
    var failure = assertThrows(java.io.IOException.class,
        () -> CompileSdk.write(List.of(), List.of(first), List.of(second), work.resolve("conflict.jar")));
    assertTrue(failure.getMessage().contains("Kotlin 模块元数据冲突"));
  }

  private static ExitCode compile(Path source, Path output, String module, String classpath) throws Exception {
    Files.createDirectories(output);
    var log = new ByteArrayOutputStream();
    try (var print = new PrintStream(log, true, StandardCharsets.UTF_8)) {
      return new K2JVMCompiler().exec(print, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
          "-module-name", module, "-classpath", classpath, "-d", output.toString(), source.toString());
    }
  }
  private static String stdlib() throws Exception {
    return Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  }
}
