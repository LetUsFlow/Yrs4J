package at.yrs4j.gradle

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

import javax.inject.Inject

@CompileStatic
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
        def version = yrsVersion.get()
        def sourceDir = sourceDirectory.get().asFile
        def gitDir = new File(sourceDir, '.git')

        if (!gitDir.exists()) {
            sourceDir.parentFile.mkdirs()
            execOperations.exec {
                it.commandLine 'git', 'clone', '--depth', '1', '--branch', "v${version}",
                        'https://github.com/y-crdt/y-crdt.git', sourceDir.absolutePath
            }
            return
        }

        execOperations.exec {
            it.commandLine 'git', '-C', sourceDir.absolutePath, 'fetch', '--tags',
                    '--depth', '1', 'origin', "v${version}"
        }
        execOperations.exec {
            it.commandLine 'git', '-C', sourceDir.absolutePath, 'checkout', '--force',
                    "v${version}"
        }
    }
}

