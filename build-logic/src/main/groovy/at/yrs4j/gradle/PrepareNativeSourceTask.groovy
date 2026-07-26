package at.yrs4j.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

import javax.inject.Inject

@DisableCachingByDefault(because = 'Fetches source from an external Git repository')
abstract class PrepareNativeSourceTask extends DefaultTask {
    @Input
    abstract Property<String> getYrsVersion()

    @OutputDirectory
    abstract DirectoryProperty getSourceDirectory()

    @Inject
    abstract ExecOperations getExecOperations()

    @TaskAction
    void prepareSource() {
        String version = yrsVersion.get()
        File sourceDir = sourceDirectory.get().asFile
        File gitDir = new File(sourceDir, '.git')

        if (!gitDir.exists()) {
            sourceDir.parentFile.mkdirs()
            execOperations.exec {
                commandLine 'git', 'clone', '--depth', '1', '--branch', "v${version}",
                        'https://github.com/y-crdt/y-crdt.git', sourceDir.absolutePath
            }
            return
        }

        execOperations.exec {
            commandLine 'git', '-C', sourceDir.absolutePath, 'fetch', '--tags',
                    '--depth', '1', 'origin', "v${version}"
        }
        execOperations.exec {
            commandLine 'git', '-C', sourceDir.absolutePath, 'checkout', '--force',
                    "v${version}"
        }
    }
}

