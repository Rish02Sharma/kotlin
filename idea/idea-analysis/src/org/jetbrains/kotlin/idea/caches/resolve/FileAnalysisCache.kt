/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.project.Project
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.SLRUCache
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.CompositeBindingContext

class FileAnalysisCache(project: Project, private val moduleResolverProvider: () -> ModuleResolverProvider) {
    private val analysisResults = CachedValuesManager.getManager(project).createCachedValue(
        {
            val resolverProvider = moduleResolverProvider()
            val results = object : SLRUCache<KtFile, PerFileAnalysisCache>(2, 3) {
                override fun createValue(file: KtFile): PerFileAnalysisCache {
                    return PerFileAnalysisCache(
                        file,
                        resolverProvider.resolverForProject.resolverForModule(file.getModuleInfo()).componentProvider
                    )
                }
            }

            val allDependencies = resolverProvider.cacheDependencies + listOf(PsiModificationTracker.MODIFICATION_COUNT)
            CachedValueProvider.Result.create(results, allDependencies)
        }, false
    )

    fun getAnalysisResultsForElements(elements: Collection<KtElement>): AnalysisResult {
        assert(elements.isNotEmpty()) { "elements collection should not be empty" }
        val slruCache = synchronized(analysisResults) {
            analysisResults.value!!
        }
        val results = elements.map {
            val perFileCache = synchronized(slruCache) {
                slruCache[it.containingKtFile]
            }
            perFileCache.getAnalysisResults(it)
        }
        val withError = results.firstOrNull { it.isError() }
        val bindingContext = CompositeBindingContext.create(results.map { it.bindingContext })
        return if (withError != null) {
            AnalysisResult.internalError(bindingContext, withError.error)
        } else {
            //TODO: (module refactoring) several elements are passed here in debugger
            AnalysisResult.success(bindingContext, findModuleDescriptor(elements.first().getModuleInfo()))
        }
    }

    fun findModuleDescriptor(ideaModuleInfo: IdeaModuleInfo): ModuleDescriptor {
        return moduleResolverProvider().resolverForProject.descriptorForModule(ideaModuleInfo)
    }
}
