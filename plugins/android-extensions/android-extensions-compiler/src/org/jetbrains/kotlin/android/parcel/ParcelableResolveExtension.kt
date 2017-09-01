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

package org.jetbrains.kotlin.android.parcel

import kotlinx.android.parcel.Parceler
import kotlinx.android.parcel.Parcelize
import org.jetbrains.kotlin.android.parcel.ParcelableSyntheticComponent.*
import org.jetbrains.kotlin.android.parcel.ParcelableSyntheticComponent.ComponentKind.*
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptorImpl
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.annotations.AnnotationsImpl
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.resolve.annotations.JVM_STATIC_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.resolve.source.PsiSourceElement
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType
import java.util.ArrayList

open class ParcelableResolveExtension : SyntheticResolveExtension {
    companion object {
        fun resolveParcelClassType(module: ModuleDescriptor): SimpleType {
            return module.findClassAcrossModuleDependencies(
                    ClassId.topLevel(FqName("android.os.Parcel")))?.defaultType ?: error("Can't resolve 'android.os.Parcel' class")
        }

        fun createCreatorProperty(classDescriptor: ClassDescriptor): PropertyDescriptor {
            val jvmStaticClass = classDescriptor.module
                    .findClassAcrossModuleDependencies(ClassId.topLevel(JVM_STATIC_ANNOTATION_FQ_NAME))
                    ?: error("@JvmStatic was not found")

            val jvmStaticAnnotation = AnnotationDescriptorImpl(jvmStaticClass.defaultType, emptyMap(), classDescriptor.source)
            val annotations = AnnotationsImpl(listOf(jvmStaticAnnotation))

            val propertyDescriptor = PropertyDescriptorImpl.create(
                    classDescriptor, annotations, Modality.FINAL, Visibilities.PUBLIC, false,
                    Name.identifier(ComponentKind.CREATOR.jvmName),
                    Kind.SYNTHESIZED, classDescriptor.source, false, false, false, false, false, false)

            val outType = classDescriptor.module.findClassAcrossModuleDependencies(ClassId.topLevel(ANDROID_PARCELABLE_CLASS_FQNAME))
                    ?.unsubstitutedMemberScope
                    ?.getContributedClassifier(Name.identifier("Creator"), NoLookupLocation.WHEN_GET_DECLARATION_SCOPE)
                    ?.defaultType
                    ?: ErrorUtils.createErrorType("Parcelable Creator class not found")

            propertyDescriptor.setType(outType, emptyList(), classDescriptor.thisAsReceiverParameter, null as KotlinType?)
            return propertyDescriptor
        }

        fun createMethod(
                classDescriptor: ClassDescriptor,
                componentKind: ParcelableSyntheticComponent.ComponentKind,
                returnType: KotlinType,
                vararg parameters: Pair<String, KotlinType>
        ): SimpleFunctionDescriptor {
            val functionDescriptor = object : ParcelableSyntheticComponent, SimpleFunctionDescriptorImpl(
                    classDescriptor,
                    null,
                    Annotations.EMPTY,
                    Name.identifier(componentKind.jvmName),
                    Kind.SYNTHESIZED,
                    classDescriptor.source
            ) {
                override val componentKind = componentKind
            }

            val valueParameters = parameters.mapIndexed { index, (name, type) -> functionDescriptor.makeValueParameter(name, type, index) }

            functionDescriptor.initialize(
                    null, classDescriptor.thisAsReceiverParameter, emptyList(), valueParameters,
                    returnType, Modality.FINAL, Visibilities.PUBLIC)

            return functionDescriptor
        }

        private fun FunctionDescriptor.makeValueParameter(name: String, type: KotlinType, index: Int): ValueParameterDescriptor {
            return ValueParameterDescriptorImpl(
                    this, null, index, Annotations.EMPTY, Name.identifier(name), type, false, false, false, null, this.source)
        }
    }

    protected open fun isExperimental(element: KtElement) = true

    override fun getSyntheticCompanionObjectNameIfNeeded(thisDescriptor: ClassDescriptor): Name? {
        if (thisDescriptor.kind == ClassKind.CLASS && isExperimental(thisDescriptor) && thisDescriptor.isParcelize) {
            return Name.identifier("Companion")
        }

        return null
    }

    override fun generateSyntheticProperties(
            thisDescriptor: ClassDescriptor,
            name: Name,
            fromSupertypes: ArrayList<PropertyDescriptor>,
            result: MutableSet<PropertyDescriptor>
    ) {
        if (name.asString() == ComponentKind.CREATOR.jvmName
            && thisDescriptor.isCompanionObject
            && thisDescriptor.containingDeclaration is ClassDescriptor
            && isExperimental(thisDescriptor)
            && result.none { it.name.asString() == ComponentKind.CREATOR.jvmName }
        ) {
            val containingClass = (thisDescriptor.containingDeclaration as? ClassDescriptor)?.takeIf { it.isParcelize }
            if (containingClass != null) {
                result += createCreatorProperty(thisDescriptor)
            }
        }
    }

    fun isExperimental(thisDescriptor: ClassDescriptor): Boolean {
        val sourceElement = (thisDescriptor.source as? PsiSourceElement)?.psi as? KtElement ?: return false
        return isExperimental(sourceElement)
    }

    override fun generateSyntheticMethods(
            thisDescriptor: ClassDescriptor,
            name: Name,
            fromSupertypes: List<SimpleFunctionDescriptor>,
            result: MutableCollection<SimpleFunctionDescriptor>
    ) {
        if (name.asString() == DESCRIBE_CONTENTS.jvmName
            && thisDescriptor.isParcelize
            && isExperimental(thisDescriptor)
            && result.none { it.isDescribeContents() }
            && fromSupertypes.none { it.isDescribeContents() }
        ) {
            result += createMethod(thisDescriptor, DESCRIBE_CONTENTS, thisDescriptor.builtIns.intType)
        } else if (name.asString() == WRITE_TO_PARCEL.jvmName
                   && thisDescriptor.isParcelize
                   && isExperimental(thisDescriptor)
                   && result.none { it.isWriteToParcel() }
        ) {
            val builtIns = thisDescriptor.builtIns
            val parcelClassType = resolveParcelClassType(thisDescriptor.module)
            result += createMethod(thisDescriptor, WRITE_TO_PARCEL, builtIns.unitType, "parcel" to parcelClassType, "flags" to builtIns.intType)
        }
    }

    private fun SimpleFunctionDescriptor.isDescribeContents(): Boolean {
        return this.kind != Kind.FAKE_OVERRIDE
               && modality != Modality.ABSTRACT
               && typeParameters.isEmpty()
               && valueParameters.isEmpty()
               && returnType?.let { type -> KotlinBuiltIns.isInt(type) } == true
    }
}

internal fun SimpleFunctionDescriptor.isWriteToParcel(): Boolean {
    return typeParameters.isEmpty()
           && valueParameters.size == 2
           && valueParameters[0].type.isParcel()
           && KotlinBuiltIns.isInt(valueParameters[1].type)
}

private fun KotlinType.isParcel() = constructor.declarationDescriptor?.fqNameSafe == ANDROID_PARCEL_CLASS_FQNAME

interface ParcelableSyntheticComponent {
    val componentKind: ComponentKind

    enum class ComponentKind(val jvmName: String) {
        WRITE_TO_PARCEL("writeToParcel"),
        DESCRIBE_CONTENTS("describeContents"),
        NEW_ARRAY("newArray"),
        CREATE_FROM_PARCEL("createFromParcel"),
        CREATOR("CREATOR")
    }
}

val PARCELIZE_CLASS_FQNAME: FqName = FqName(Parcelize::class.java.canonicalName)

internal val PARCELER_FQNAME: FqName = FqName(Parceler::class.java.canonicalName)

val ClassDescriptor.isParcelize: Boolean
    get() = this.annotations.hasAnnotation(PARCELIZE_CLASS_FQNAME)

val KotlinType.isParceler
    get() = constructor.declarationDescriptor?.fqNameSafe == PARCELER_FQNAME