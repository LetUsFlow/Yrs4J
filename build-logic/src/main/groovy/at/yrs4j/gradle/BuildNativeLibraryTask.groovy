package at.yrs4j.gradle

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

import javax.inject.Inject

@CompileStatic
@DisableCachingByDefault(because = 'Depends on the locally installed Rust and cross-compilation toolchains')
abstract class BuildNativeLibraryTask extends DefaultTask {
    @Input
    abstract Property<String> getYrsVersion()

    @Input
    abstract Property<String> getRustTarget()

    @Input
    abstract Property<String> getBuildTool()

    @Input
    abstract Property<String> getArtifactName()

    @InputFile
    abstract RegularFileProperty getCargoManifest()

    @InputFile
    abstract RegularFileProperty getCargoLock()

    @LocalState
    abstract DirectoryProperty getCargoTargetDirectory()

    @OutputFile
    abstract RegularFileProperty getDestination()

    @Inject
    abstract ExecOperations getExecOperations()

    @Inject
    abstract FileSystemOperations getFileSystemOperations()

    @TaskAction
    void buildNativeLibrary() {
        def target = rustTarget.get()
        def tool = buildTool.get()
        def manifest = cargoManifest.get().asFile
        def targetDir = cargoTargetDirectory.get().asFile

        execOperations.exec {
            it.commandLine 'rustup', 'target', 'add', target
        }

        def command = ['cargo']
        if (tool == 'cargo-xwin') {
            command.add('xwin')
        } else if (tool != 'cargo') {
            throw new GradleException("Unsupported native build tool: ${tool}")
        }
        command.addAll([
                'build',
                '--manifest-path', manifest.absolutePath,
                '--release',
                '--target', target,
                '--target-dir', targetDir.absolutePath
        ])

        execOperations.exec {
            it.commandLine command
            if (target == 'aarch64-unknown-linux-gnu') {
                it.environment 'CC_aarch64_unknown_linux_gnu', 'aarch64-linux-gnu-gcc'
                it.environment 'CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER', 'aarch64-linux-gnu-gcc'
            }
        }

        def builtArtifact = new File(targetDir, "${target}/release/${artifactName.get()}")
        if (!builtArtifact.isFile()) {
            throw new GradleException("Expected native library was not produced: ${builtArtifact}")
        }

        def destinationFile = destination.get().asFile
        fileSystemOperations.copy {
            it.from builtArtifact
            it.into destinationFile.parentFile
            it.rename { destinationFile.name }
        }
    }
}
