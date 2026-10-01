package app.luoxianlv.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class ExportCompileSdk extends DefaultTask {
  @InputFiles @PathSensitive(PathSensitivity.NONE)
  public abstract ListProperty<RegularFile> getJars();
  @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ListProperty<Directory> getDirectories();
  @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getMetadataSources();
  @OutputFile public abstract RegularFileProperty getSdk();

  @TaskAction public void export() throws Exception {
    CompileSdk.write(
        getJars().get().stream().map(file -> file.getAsFile().toPath()).toList(),
        getDirectories().get().stream().map(file -> file.getAsFile().toPath()).toList(),
        getMetadataSources().getFiles().stream().map(file -> file.toPath()).toList(),
        getSdk().get().getAsFile().toPath());
  }
}
