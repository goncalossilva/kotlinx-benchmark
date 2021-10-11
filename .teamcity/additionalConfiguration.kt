/*
 * Copyright 2016-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */

import jetbrains.buildServer.configs.kotlin.v2019_2.BuildType
import jetbrains.buildServer.configs.kotlin.v2019_2.BuildTypeSettings
import jetbrains.buildServer.configs.kotlin.v2019_2.FailureAction
import jetbrains.buildServer.configs.kotlin.v2019_2.ParameterDisplay
import jetbrains.buildServer.configs.kotlin.v2019_2.Project
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.GradleBuildStep
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.gradle

fun Project.additionalConfiguration() {
    platforms.forEach { platform ->
        val gradleBuild = knownBuilds.buildOn(platform).steps.items.single() as GradleBuildStep
        gradleBuild.tasks += " " + fastBenchmarkTasks(platform)
    }

    deployPlugin()

    configureSpaceDeployments()
}

fun fastBenchmarkTasks(platform: Platform): String {
    return listOf(
        "js", "jvm", platform.nativeTaskPrefix()
    ).joinToString(separator = " ", transform = { "${it}FastBenchmark" })
}


// deploy plugin to Gradle Plugin Portal

const val gradlePublishKey = "gradle.publish.key"
const val gradlePublishSecret = "gradle.publish.secret"

const val DEPLOY_PUBLISH_PLUGIN_ID = "Deploy_Publish_Plugin"

fun Project.deployPlugin() = BuildType {
    id(DEPLOY_PUBLISH_PLUGIN_ID)
    this.name = "Deploy (Publish Plugin)"
    commonConfigure()

    requirements {
        // Require Linux for publishPlugins
        contains("teamcity.agent.jvm.os.name", "Linux")
    }

    dependsOnSnapshot(this@deployPlugin.knownBuilds.buildAll)
    buildNumberPattern = this@deployPlugin.knownBuilds.buildVersion.depParamRefs.buildNumber.ref

    type = BuildTypeSettings.Type.DEPLOYMENT
    enablePersonalBuilds = false
    maxRunningBuilds = 1

    steps {
        gradle {
            name = "Publish Plugin"
            jdkHome = "%env.$jdk%"
            jvmArgs = "-Xmx1g"
            gradleParams = "--info --stacktrace -P$gradlePublishKey=%$gradlePublishKey% -P$gradlePublishSecret=%$gradlePublishSecret%"
            tasks = "clean :plugin:publishPlugins"
            buildFile = ""
            gradleWrapperPath = ""
        }
    }
}.also { buildType(it) }


// deploy plugin and runtime to Space maven repository

fun Project.configureSpaceDeployments() {
    val buildAll = knownBuilds.buildAll
    val deployVersion = knownBuilds.deployVersion
    val deploys = platforms.map { deployToSpace(it, deployVersion) }
    val deployPublish = deployPublishToSpace(deployVersion).apply {
        dependsOnSnapshot(buildAll, onFailure = FailureAction.IGNORE)
        deploys.forEach {
            dependsOnSnapshot(it)
        }
    }
    deployPluginToSpace()
}

const val DEPLOY_PUBLISH_TO_SPACE_ID = "Deploy_Publish_To_Space"

fun Project.deployPublishToSpace(configureBuild: BuildType) = BuildType {
    id(DEPLOY_PUBLISH_TO_SPACE_ID)
    this.name = "Deploy (Publish to Space)"
    type = BuildTypeSettings.Type.COMPOSITE
    dependsOnSnapshot(configureBuild)
    buildNumberPattern = configureBuild.depParamRefs.buildNumber.ref
    params {
        // Tell configuration build how to get release version parameter from this build
        // "dev" is the default and means publishing is not releasing to public
        text(configureBuild.reverseDepParamRefs[releaseVersionParameter].name, "dev", display = ParameterDisplay.PROMPT, label = "Release Version")
    }
    commonConfigure()
}.also { buildType(it) }

const val DEPLOY_PUBLISH_PLUGIN_TO_SPACE_ID = "Deploy_Publish_Plugin_To_Space"

fun Project.deployPluginToSpace() = BuildType {
    id(DEPLOY_PUBLISH_PLUGIN_TO_SPACE_ID)
    this.name = "Deploy (Publish Plugin To Space)"
    commonConfigure()

    requirements {
        // Require Linux for publishing the plugin
        contains("teamcity.agent.jvm.os.name", "Linux")
    }

    dependsOnSnapshot(this@deployPluginToSpace.knownBuilds.buildAll)
    buildNumberPattern = this@deployPluginToSpace.knownBuilds.buildVersion.depParamRefs.buildNumber.ref

    type = BuildTypeSettings.Type.DEPLOYMENT
    enablePersonalBuilds = false
    maxRunningBuilds = 1

    params {
        param("system.space.user", "abduqodiri.qurbonzoda")
        password("system.space.token", "credentialsJSON:7aa03210-1f86-452e-b786-920f8a321b7d")
    }

    steps {
        gradle {
            name = "Publish Plugin"
            jdkHome = "%env.$jdk%"
            jvmArgs = "-Xmx1g"
            gradleParams = "--info --stacktrace"
            tasks = "clean :plugin:publishToSpaceRepository"
            buildFile = ""
            gradleWrapperPath = ""
        }
    }
}.also { buildType(it) }

fun Project.deployToSpace(platform: Platform, configureBuild: BuildType) = buildType("Deploy_To_Space", platform) {
    type = BuildTypeSettings.Type.DEPLOYMENT
    enablePersonalBuilds = false
    maxRunningBuilds = 1
    params {
        param(versionSuffixParameter, "${configureBuild.depParamRefs[versionSuffixParameter]}")
        param(releaseVersionParameter, "${configureBuild.depParamRefs[releaseVersionParameter]}")
        param("system.space.user", "abduqodiri.qurbonzoda")
        password("system.space.token", "credentialsJSON:7aa03210-1f86-452e-b786-920f8a321b7d")
    }

    vcs {
        cleanCheckout = true
    }

    steps {
        gradle {
            name = "Deploy ${platform.buildTypeName()} Binaries to Space"
            jdkHome = "%env.$jdk%"
            jvmArgs = "-Xmx1g"
            gradleParams = "--info --stacktrace -P$versionSuffixParameter=%$versionSuffixParameter% -P$releaseVersionParameter=%$releaseVersionParameter%"
            tasks = "clean publishToSpaceRepository"
            buildFile = ""
            gradleWrapperPath = ""
        }
    }
}.dependsOnSnapshot(configureBuild)