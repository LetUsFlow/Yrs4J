package at.yrs4j.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

class Yrs4jBuildPlugin implements Plugin<Project> {
    private static final JavaLanguageVersion JAVA_VERSION = JavaLanguageVersion.of(25)
    private static final String JUNIT_LAUNCHER = 'org.junit.platform:junit-platform-launcher:1.8.1'

    @Override
    void apply(Project root) {
        requireRootProject(root)
        configureRootProject(root)
        configureSubprojects(root)
        configurePublishedLibraries(root)
        configureNativeBuild(root)
    }

    private static void requireRootProject(Project project) {
        if (project != project.rootProject) {
            throw new GradleException('The at.yrs4j.build plugin must be applied to the root project')
        }
    }

    private static void configureRootProject(Project root) {
        root.pluginManager.apply(JavaPlugin)
        root.pluginManager.apply(MavenPublishPlugin)
        root.group = 'at.yrs4j'
        root.version = '0.1.0'

        configureJavaProject(root)
        root.repositories.mavenCentral()
        root.dependencies.add('testImplementation', 'org.junit.jupiter:junit-jupiter-api:5.7.0')
        root.dependencies.add('testRuntimeOnly', 'org.junit.jupiter:junit-jupiter-engine:5.7.0')
    }

    private static void configureSubprojects(Project root) {
        root.subprojects { subproject ->
            subproject.repositories.mavenCentral()
            subproject.pluginManager.withPlugin('java') {
                configureJavaProject(subproject)
            }
        }
    }

    private static void configureJavaProject(Project project) {
        JavaPluginExtension javaExtension = project.extensions.getByType(JavaPluginExtension)
        javaExtension.toolchain.languageVersion.set(JAVA_VERSION)
        javaExtension.modularity.inferModulePath.set(true)
        project.dependencies.add('testRuntimeOnly', JUNIT_LAUNCHER)
        project.tasks.withType(Test).configureEach {
            useJUnitPlatform()
            jvmArgs '--enable-native-access=ALL-UNNAMED',
                    '--illegal-native-access=deny'
        }
    }

    private static void configurePublishedLibraries(Project root) {
        configurePublishedLibrary(root, ':yrs4j-bindings', 'bindingsVersion', 'bindings')
        configurePublishedLibrary(root, ':yrs4j-native-linux', 'nativeLinuxVersion', 'libnative-linux')
        configurePublishedLibrary(root, ':yrs4j-native-windows', 'nativeWindowsVersion', 'libnative-windows')
    }

    private static void configurePublishedLibrary(
            Project root,
            String projectPath,
            String versionProperty,
            String artifactId
    ) {
        Project library = root.project(projectPath)
        library.pluginManager.apply(JavaLibraryPlugin)
        library.pluginManager.apply(MavenPublishPlugin)
        library.version = library.providers.gradleProperty(versionProperty).get()

        PublishingExtension publishing = library.extensions.getByType(PublishingExtension)
        publishing.publications.create('mavenJava', MavenPublication) {
            from library.components.getByName('java')
            it.artifactId = artifactId
        }

        String repositoryName = root.providers.environmentVariable('GITHUB_REPOSITORY')
                .getOrElse('letusflow/yrs4j')
        publishing.repositories.maven {
            name = 'GitHubPackages'
            url = library.uri("https://maven.pkg.github.com/${repositoryName}")
            credentials(PasswordCredentials) {
                username = library.providers.gradleProperty('gpr.user')
                        .orElse(library.providers.environmentVariable('GITHUB_ACTOR'))
                        .orNull
                password = library.providers.gradleProperty('gpr.key')
                        .orElse(library.providers.environmentVariable('GITHUB_TOKEN'))
                        .orNull
            }
        }
    }

    private static void configureNativeBuild(Project root) {
        String linuxVersion = root.providers.gradleProperty('nativeLinuxVersion').get()
        String windowsVersion = root.providers.gradleProperty('nativeWindowsVersion').get()
        String nativeVersion = root.providers.gradleProperty('nativeYrsVersion').orNull
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

        TaskProvider<PrepareNativeSourceTask> prepareSource = root.tasks.register(
                'prepareNativeSource',
                PrepareNativeSourceTask
        ) {
            group = 'build'
            description = 'Fetch the pinned y-crdt source tree used for bundled yffi native libraries.'
            yrsVersion.set(nativeVersion)
            sourceDirectory.set(sourceDir)
        }

        List<Map<String, String>> targets = [
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

        Map<String, TaskProvider<BuildNativeLibraryTask>> nativeTasks = [:]
        targets.each { target ->
            nativeTasks[target.name] = root.tasks.register(
                    "buildNative${target.name}",
                    BuildNativeLibraryTask
            ) {
                group = 'build'
                description = "Build yffi for ${target.rustTarget} and copy it into ${target.resourcePath}."
                dependsOn prepareSource
                yrsVersion.set(nativeVersion)
                rustTarget.set(target.rustTarget)
                buildTool.set(target.buildTool)
                artifactName.set(target.artifactName)
                cargoManifest.set(sourceDir.map { it.file('yffi/Cargo.toml') })
                cargoLock.set(sourceDir.map { it.file('Cargo.lock') })
                cargoTargetDirectory.set(targetBaseDir.map { it.dir(target.rustTarget) })
                destination.set(root.layout.projectDirectory.file(target.resourcePath))
            }
        }

        root.tasks.register('buildNativeLinux') {
            group = 'build'
            description = 'Build bundled yffi native libraries for Linux x86_64 and aarch64.'
            dependsOn nativeTasks.LinuxX64, nativeTasks.LinuxAarch64
        }
        root.tasks.register('buildNativeWindows') {
            group = 'build'
            description = 'Build bundled yffi native libraries for Windows x86_64 and aarch64 from Linux using cargo-xwin.'
            dependsOn nativeTasks.WindowsX64, nativeTasks.WindowsAarch64
        }
        root.tasks.register('buildNative') {
            group = 'build'
            description = 'Build all bundled yffi native libraries for Linux and Windows from Linux.'
            dependsOn nativeTasks.values()
        }
    }
}
