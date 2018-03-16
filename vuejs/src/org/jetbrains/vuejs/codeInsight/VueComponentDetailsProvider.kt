// Copyright 2000-2018 JetBrains s.r.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package org.jetbrains.vuejs.codeInsight

import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import java.util.*

/**
 * @author Irina.Chernushina on 10/13/2017.
 */
class VueComponentDetailsProvider {
  companion object {
    val INSTANCE = VueComponentDetailsProvider()
    private val ADVANCED_PROVIDERS = listOf(VueMixinLocalComponentDetailsProvider(), VueGlobalMixinComponentDetailsProvider(),
                                            VueExtendsLocalComponentDetailsProvider())
    private val BIND_VARIANTS = setOf(".prop", ".camel", ".sync")
    private val ON_VARIANTS = setOf("*")
    private val PREFIX_VARIANTS = mapOf(Pair(":", BIND_VARIANTS),
                                        Pair("v-bind:", BIND_VARIANTS),
                                        Pair("@", ON_VARIANTS), Pair("v-on:", ON_VARIANTS))
    private val EVENT_MODIFIERS = setOf(".stop", ".prevent", ".capture", ".self", ".once")
    private val NO_VALUE = mapOf(Pair("@", EVENT_MODIFIERS), Pair("v-on:", EVENT_MODIFIERS))

    fun attributeAllowsNoValue(attributeName : String) : Boolean {
      return NO_VALUE.any {
        val cutPrefix = attributeName.substringAfter(it.key, "")
        !cutPrefix.isEmpty() && it.value.any { cutPrefix.endsWith(it) }
      }
    }

    fun getBoundName(attributeName : String): String? {
      return PREFIX_VARIANTS.map {
        val after = attributeName.substringAfter(it.key, "")
        if (!after.isEmpty()) {
          if (it.value.contains("*")) {
            return after.substringBefore(".", after)
          }
          return@map it.value.map { after.substringBefore(it, "") }.firstOrNull { !it.isEmpty() } ?: after
        }
        return@map ""
      }.firstOrNull { !it.isEmpty() }
    }

    fun nameVariantsFilter(attributeName : String) : (String, PsiElement) -> Boolean {
      val prefix = PREFIX_VARIANTS.keys.find { attributeName.startsWith(it) }
      val normalizedName = if (prefix != null) attributeName.substring(prefix.length) else attributeName
      val nameVariants = getNameVariants(normalizedName, true)
      return { name, _ -> name in nameVariants }
    }
  }

  fun getAttributes(descriptor: JSObjectLiteralExpression?,
                    project: Project,
                    onlyPublic: Boolean,
                    xmlContext: Boolean): List<VueAttributeDescriptor> {
    val result: MutableList<VueAttributeDescriptor> = mutableListOf()
    if (descriptor != null) {
      result.addAll(VueComponentOwnDetailsProvider.getDetails(descriptor, EMPTY_FILTER, onlyPublic, false))
      result.addAll(VueDirectivesProvider.getAttributes(descriptor, descriptor.project))
    }
    iterateProviders(descriptor, project, {
      result.addAll(VueComponentOwnDetailsProvider.getDetails(it, EMPTY_FILTER, onlyPublic, false))
      true
    })

    return result.map {
      @Suppress("UnnecessaryVariable")
      val attrDescriptor = it
      if (xmlContext) {
        val fromAsset = fromAsset(it.name)
        return@map listOf(attrDescriptor.createNameVariant(fromAsset),
                          attrDescriptor.createNameVariant(":$fromAsset"),
                          attrDescriptor.createNameVariant("v-bind:$fromAsset"))
      } else {
        if (it.name.contains('-')) {
          listOf(attrDescriptor.createNameVariant(toAsset(it.name)))
        }
        else listOf(it)
      }
    }.flatten()
  }

  fun resolveAttribute(descriptor: JSObjectLiteralExpression,
                       attrName: String,
                       onlyPublic: Boolean): VueAttributeDescriptor? {
    val filter = nameVariantsFilter(attrName)
    val direct = VueComponentOwnDetailsProvider.getDetails(descriptor, filter, onlyPublic, true).firstOrNull()
    if (direct != null) return direct
    val holder : Ref<VueAttributeDescriptor> = Ref()
    iterateProviders(descriptor, descriptor.project, {
      holder.set(VueComponentOwnDetailsProvider.getDetails(it, filter, onlyPublic, true).firstOrNull())
      holder.isNull
    })
    if (holder.isNull) {
      holder.set(VueDirectivesProvider.resolveAttribute(descriptor, attrName, descriptor.project))
    }
    return holder.get()
  }

  fun processLocalComponents(component: JSObjectLiteralExpression?,
                             project: Project,
                             processor: (String?, PsiElement) -> Boolean) {
    val filter: (String, PsiElement) -> Boolean = { name, element -> !processor(name, element) }
    if (component != null) {
      // no need to accumulate the components, it is done in the processor
      // but we should check if processor already commanded to stop
      val direct = VueComponentOwnDetailsProvider.getLocalComponents(component, filter, true)
      if (direct.isNotEmpty()) return
    }
    iterateProviders(component, project, { mixedInDescriptor ->
      VueComponentOwnDetailsProvider.getLocalComponents(mixedInDescriptor, filter, true).isEmpty()
    })
  }

  private fun iterateProviders(descriptor: JSObjectLiteralExpression?, project: Project, processor : (JSObjectLiteralExpression) -> Boolean) {
    val visited = mutableSetOf<JSObjectLiteralExpression>()
    val queue = ArrayDeque<Ref<JSObjectLiteralExpression>>()
    queue.add(Ref(descriptor))
    if (descriptor != null) visited.add(descriptor)

    while (queue.isNotEmpty()) {
      val currentDescriptor = queue.removeFirst()?.get()
      for (provider in ADVANCED_PROVIDERS) {
        val finder = provider.getDescriptorFinder()
        val shouldStop = provider.getIndexedData(currentDescriptor, project).any { implicitElement ->
          val obj = finder(implicitElement) ?: return@any false
          if (visited.add(obj)) queue.add(Ref(obj))

          !processor(obj)
        }
        if (shouldStop) return
      }
    }
  }
}