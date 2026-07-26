package at.yrs4j.gradle

import groovy.transform.CompileStatic
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

@CompileStatic
class Yrs4jBuildPlugin implements Plugin<Project> {
    @Override
    void apply(Project root) {
        def linuxVersion = root.providers.gradleProperty('nativeLinuxVersion').get()
        def windowsVersion = root.providers.gradleProperty('nativeWindowsVersion').get()
        def nativeVersion = root.providers.gradleProperty('nativeYrsVersion').orNull
        if (nativeVersion == null) {
            if (linuxVersion != windowsVersion) {
                throw new GradleException(
                        "nativeLinuxVersion (${linuxVersion}) and nativeWindowsVersion " +
                                "(${windowsVersion}) must match, or pass -PnativeYrsVersion explicitly."
                )
            }
            nativeVersion = linuxVersion
        }

        def sourceDir = root.layout.buildDirectory.dir('native-src/y-crdt')
        def targetBaseDir = root.layout.buildDirectory.dir('native-target')

        def prepareSource = root.tasks.register(
                'prepareNativeSource',
                PrepareNativeSourceTask
        ) {
            it.group = 'build'
            it.description = 'Fetch the pinned y-crdt source tree used for bundled yffi native libraries.'
            it.yrsVersion.set(nativeVersion)
            it.sourceDirectory.set(sourceDir)
        }

        def targets = [
                [
                        name: 'LinuxX64',
                        rustTarget: 'x86_64-unknown-linux-gnu',
                        buildTool: 'cargo',
                        artifactName: 'libyrs.so',
                        resourcePath: 'yrs4j-native-linux/src/main/resources/linux-x86-64/libyrs.so'
                ],
                [
                        name: 'LinuxAarch64',
                        rustTarget: 'aarch64-unknown-linux-gnu',
                        buildTool: 'cargo',
                        artifactName: 'libyrs.so',
                        resourcePath: 'yrs4j-native-linux/src/main/resources/linux-aarch64/libyrs.so'
                ],
                [
                        name: 'WindowsX64',
                        rustTarget: 'x86_64-pc-windows-msvc',
                        buildTool: 'cargo-xwin',
                        artifactName: 'yrs.dll',
                        resourcePath: 'yrs4j-native-windows/src/main/resources/win32-x86-64/libyrs.dll'
                ],
                [
                        name: 'WindowsAarch64',
                        rustTarget: 'aarch64-pc-windows-msvc',
                        buildTool: 'cargo-xwin',
                        artifactName: 'yrs.dll',
                        resourcePath: 'yrs4j-native-windows/src/main/resources/win32-aarch64/libyrs.dll'
                ]
        ]

        def nativeTasks = [:]
        targets.each { target ->
            nativeTasks[target.name] = root.tasks.register(
                    "buildNative${target.name}",
                    BuildNativeLibraryTask
            ) {
                it.group = 'build'
                it.description = "Build yffi for ${target.rustTarget} and copy it into ${target.resourcePath}."
                it.dependsOn prepareSource
                it.yrsVersion.set(nativeVersion)
                it.rustTarget.set(target.rustTarget)
                it.buildTool.set(target.buildTool)
                it.artifactName.set(target.artifactName)
                it.cargoManifest.set(sourceDir.map { it.file('yffi/Cargo.toml') })
                it.cargoLock.set(sourceDir.map { it.file('Cargo.lock') })
                it.cargoTargetDirectory.set(targetBaseDir.map { it.dir(target.rustTarget) })
                it.destination.set(root.layout.projectDirectory.file(target.resourcePath))
            }
        }

        root.tasks.register('buildNativeLinux') {
            it.group = 'build'
            it.description = 'Build bundled yffi native libraries for Linux x86_64 and aarch64.'
            it.dependsOn nativeTasks.LinuxX64, nativeTasks.LinuxAarch64
        }
        root.tasks.register('buildNativeWindows') {
            it.group = 'build'
            it.description = 'Build bundled yffi native libraries for Windows x86_64 and aarch64 from Linux using cargo-xwin.'
            it.dependsOn nativeTasks.WindowsX64, nativeTasks.WindowsAarch64
        }
        root.tasks.register('buildNative') {
            it.group = 'build'
            it.description = 'Build all bundled yffi native libraries for Linux and Windows from Linux.'
            it.dependsOn nativeTasks.values()
        }

    }
}
