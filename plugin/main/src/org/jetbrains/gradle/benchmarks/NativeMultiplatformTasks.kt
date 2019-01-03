package org.jetbrains.gradle.benchmarks

import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.tasks.*
import org.jetbrains.kotlin.konan.target.*
import java.io.*

fun Project.processNativeCompilation(
    extension: BenchmarksExtension,
    config: BenchmarkConfiguration,
    compilation: KotlinNativeCompilation
) {
    configureMultiplatformNativeCompilation(this, config, compilation)
    createNativeBenchmarkGenerateSourceTask(
        extension,
        config,
        compilation.target,
        compilation.compileAllTaskName,
        compilation.output.allOutputs
    )

    val benchmarkCompilation = createNativeBenchmarkCompileTask(extension, config, compilation)
    createNativeBenchmarkExecTask(extension, config, benchmarkCompilation)
}

private fun configureMultiplatformNativeCompilation(
    project: Project,
    config: BenchmarkConfiguration,
    compilation: KotlinNativeCompilation
) {
    // TODO: add dependency to multiplatform benchmark runtime lib
}

private fun Project.createNativeBenchmarkGenerateSourceTask(
    extension: BenchmarksExtension,
    config: BenchmarkConfiguration,
    target: KotlinNativeTarget,
    compilationTask: String,
    compilationOutput: FileCollection
) {
    val benchmarkBuildDir = benchmarkBuildDir(extension, config)
    task<NativeSourceGeneratorTask>("${config.name}${BenchmarksPlugin.BENCHMARK_GENERATE_SUFFIX}") {
        group = BenchmarksPlugin.BENCHMARKS_TASK_GROUP
        description = "Generate Native source files for '${config.name}'"
        // dependsOn(compilationTask) // next line should do it implicitly
        this.target = target.konanTarget.name
        inputClassesDirs = compilationOutput
        outputResourcesDir = file("$benchmarkBuildDir/resources")
        outputSourcesDir = file("$benchmarkBuildDir/sources")
    }
}

private fun Project.createNativeBenchmarkCompileTask(
    extension: BenchmarksExtension,
    config: BenchmarkConfiguration,
    compilation: KotlinNativeCompilation
): KotlinNativeCompilation {

    val benchmarkBuildDir = benchmarkBuildDir(extension, config)
    val benchmarkCompilation = compilation.target.compilations.create("benchmark") as KotlinNativeCompilation
    val compileTask = tasks.getByName(benchmarkCompilation.compileKotlinTaskName) as KotlinNativeCompile

    benchmarkCompilation.apply {
        val sourceSet = kotlinSourceSets.single()
        sourceSet.kotlin.srcDir(file("$benchmarkBuildDir/sources"))
        sourceSet.dependencies {
            implementation(compilation.compileDependencyFiles)
            implementation(compilation.output.allOutputs)
        }
        compileTask.apply {
            group = BenchmarksPlugin.BENCHMARKS_TASK_GROUP
            description = "Compile Native benchmark source files for '${config.name}'"
            destinationDir = file("$benchmarkBuildDir/classes")
            outputKind = CompilerOutputKind.PROGRAM
            optimized = true
            debuggable = false
            entryPoint("org.jetbrains.gradle.benchmarks.generated.main") 
            dependsOn("${config.name}${BenchmarksPlugin.BENCHMARK_GENERATE_SUFFIX}")
        }
    }
    return benchmarkCompilation
}

fun Project.createNativeBenchmarkExecTask(
    extension: BenchmarksExtension,
    config: BenchmarkConfiguration,
    compilation: KotlinNativeCompilation
) {
    val reportsDir = buildDir.resolve(extension.buildDir).resolve("reports")
    val reportFile = reportsDir.resolve("${config.name}.json")
    task<Exec>("${config.name}${BenchmarksPlugin.BENCHMARK_EXEC_SUFFIX}", depends = "benchmark") {
        group = BenchmarksPlugin.BENCHMARKS_TASK_GROUP
        description = "Executes benchmark for '${config.name}'"
        //setScript(file("$nodeModulesDir/${compilation.output}"))
        val nativeTask = tasks.getByName(compilation.compileKotlinTaskName) as KotlinNativeCompile
        executable  = nativeTask.outputFile.get().absolutePath
        // TODO: add line-protocol for saving report. 
        // Create a filtering output stream, that would pass lines to output, unless special line ####BEGIN_REPORT#### comes
        // then stop piping and start saving to file, then switch back on ####END_REPORT####
        standardOutput = FileOutputStream(reportFile)
        dependsOn(nativeTask)
        doFirst {
            reportsDir.mkdirs()
            println("Running benchmarks for ${config.name}")
        }
    }
}