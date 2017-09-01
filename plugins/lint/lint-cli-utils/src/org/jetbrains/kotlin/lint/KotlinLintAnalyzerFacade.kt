/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.lint

import com.intellij.mock.MockProject
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.extensions.ExtensionPoint.*
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoots
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.load.kotlin.MetadataFinderFactory
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver
import org.jetbrains.uast.evaluation.UEvaluatorExtension
import org.jetbrains.uast.kotlin.KotlinUastBindingContextProviderService
import org.jetbrains.uast.kotlin.evaluation.KotlinEvaluatorExtension
import org.jetbrains.uast.kotlin.internal.CliKotlinUastBindingContextProviderService
import java.io.File

@Suppress("unused")
object KotlinLintAnalyzerFacade {
    @JvmStatic
    fun analyze(files: List<File>, javaRoots: List<File>, project: MockProject): BindingTrace {
        val localFs = StandardFileSystems.local()
        val psiManager = PsiManager.getInstance(project)

        val trace = CliLightClassGenerationSupport.NoScopeRecordCliBindingTrace()

        val virtualFiles = files.mapNotNull { localFs.findFileByPath(it.absolutePath) }
        val ktFiles = virtualFiles.mapNotNull { psiManager.findFile(it) }.filterIsInstance<KtFile>()
        if (ktFiles.isEmpty()) return trace

        // We can't figure out if the given directory is a binary or a source root, so we add it to both lists
        val javaBinaryRoots = javaRoots
                .mapNotNull { localFs.findFileByPath(it.absolutePath) }
                .map { JavaRoot(it, JavaRoot.RootType.BINARY) }

        val javaSourceRoots = javaRoots
                .filter { it.isDirectory }
                .mapNotNull { localFs.findFileByPath(it.absolutePath) }
                .map { JavaRoot(it, JavaRoot.RootType.SOURCE) }

        val allJavaRoots = javaBinaryRoots + javaSourceRoots

        val compilerConfiguration = createCompilerConfiguration("lintWithKotlin", allJavaRoots)

        fun createPackagePartProvider(scope: GlobalSearchScope): JvmPackagePartProvider {
            return JvmPackagePartProvider(compilerConfiguration.languageVersionSettings, scope).apply {
                addRoots(allJavaRoots)
            }
        }

        registerAnalyzerComponents(project, allJavaRoots)

        TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                project, ktFiles, trace, compilerConfiguration, ::createPackagePartProvider)

        return trace
    }

    private fun registerAnalyzerComponents(
            project: MockProject,
            javaRoots: List<JavaRoot>
    ) {
//        KotlinCoreEnvironment.registerPluginExtensionPoints(project)
//        KotlinCoreEnvironment.registerProjectServices(project, null)
//        KotlinCoreEnvironment.registerKotlinLightClassSupport(project)

        val rootsIndex = JvmDependenciesIndexImpl(javaRoots)

        val finderFactory = CliVirtualFileFinderFactory(rootsIndex)
        project.registerServiceIfNeeded(MetadataFinderFactory::class.java, finderFactory)
        project.registerServiceIfNeeded(VirtualFileFinderFactory::class.java, finderFactory)

        project.registerServiceIfNeeded(JavaModuleResolver::class.java, object : JavaModuleResolver {
            override fun checkAccessibility(fileFromOurModule: VirtualFile?, referencedFile: VirtualFile, referencedPackage: FqName?) = null
        })

//        val rootArea = Extensions.getRootArea()
//        rootArea.registerExtensionPoint(UEvaluatorExtension.EXTENSION_POINT_NAME.name, UEvaluatorExtension::class.java.name, Kind.INTERFACE)
//        rootArea.getExtensionPoint(UEvaluatorExtension.EXTENSION_POINT_NAME).registerExtension(KotlinEvaluatorExtension())

        project.registerServiceIfNeeded(
                KotlinUastBindingContextProviderService::class.java,
                CliKotlinUastBindingContextProviderService::class.java)
    }

    private fun <T> MockProject.registerServiceIfNeeded(intf: Class<T>, impl: T) {
        if (ServiceManager.getService(this, intf) == null) {
            registerService(intf, impl)
        }
    }

    private fun <T> MockProject.registerServiceIfNeeded(intf: Class<T>, impl: Class<out T>) {
        if (ServiceManager.getService(this, intf) == null) {
            registerService(intf, impl)
        }
    }

    private fun createCompilerConfiguration(moduleName: String, javaRoots: List<JavaRoot>): CompilerConfiguration {
        val configuration = CompilerConfiguration()
        configuration.put(CommonConfigurationKeys.MODULE_NAME, moduleName)
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

        val javaSources = javaRoots
                .filter { it.type == JavaRoot.RootType.SOURCE }
                .mapNotNull { it.file.canonicalPath }
                .map(::File)

        val classpath = javaRoots
                .filter { it.type == JavaRoot.RootType.BINARY }
                .mapNotNull { it.file.canonicalPath }
                .map(::File)

        configuration.addJavaSourceRoots(javaSources)
        configuration.addJvmClasspathRoots(classpath)

        return configuration
    }

}