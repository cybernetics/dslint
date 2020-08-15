@file:Suppress("UnstableApiUsage")

package com.ironsource.aura.dslint.detectors

import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PropertyUtilBase
import com.ironsource.aura.dslint.DSLintAnnotation
import com.ironsource.aura.dslint.utils.nullIfEmpty
import com.ironsource.aura.dslint.utils.resolveStringAttributeValue
import org.jetbrains.uast.*
import org.jetbrains.uast.util.isAssignment
import org.jetbrains.uast.util.isMethodCall
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class DslMandatoryDetector : DSLintDetector() {

    companion object {
        val ISSUE = Issue.create(
            "Missing DSL attribute",
            "Mandatory DSL attribute not defined",
            "Mandatory DSL attribute not defined",
            Category.CORRECTNESS, 6,
            Severity.ERROR,
            Implementation(DslMandatoryDetector::class.java, Scope.JAVA_FILE_SCOPE)
        )
    }

    override fun visitDslLintLambda(
        context: JavaContext,
        node: ULambdaExpression,
        dslLintClass: PsiClass
    ) {
        val dslPropertiesDefs = getDslMandatoryProperties(dslLintClass)

        val dslPropertiesCalls = getDslMandatoryCallsCount(
            dslPropertiesDefs,
            node.body as UBlockExpression
        )

        // Report groups with no calls
        dslPropertiesCalls
            .filterValues { it == 0 }
            .forEach {
                reportMissingMandatoryAttribute(dslPropertiesDefs, it.key, context, node)
            }
    }

    private fun reportMissingMandatoryAttribute(
        dslPropertiesDefs: Map<String, List<DSLMandatoryAttribute>>,
        group: String,
        context: JavaContext,
        node: ULambdaExpression
    ) {
        val message = dslPropertiesDefs[group]?.getOrNull(0)?.message
            ?: "\"${group}\" attribute must be defined"
        context.report(
            ISSUE,
            node,
            context.getLocation(node as UElement),
            message,
            getFix(dslPropertiesDefs[group]!!)
        )
    }

    private fun getFix(groupAttributes: List<DSLMandatoryAttribute>): LintFix {
        val group = LintFix.create().group()
        groupAttributes.forEach {
            val setSuffix = if (it.type == Type.PROPERTY) " = " else "{\n}"
            group.add(
                LintFix.create()
                    .name("Define \"${it.name}\" attribute")
                    .replace()
                    .text("{")
                    .with("{\n${it.name}$setSuffix")
                    .reformat(true)
                    .build()
            )
        }

        return group.build()
    }


    private fun getDslMandatoryCallsCount(
        dslProperties: Map<String, List<DSLMandatoryAttribute>>,
        blockBody: UBlockExpression
    ): Map<String, Int> {
        val propertiesCalls = getDslPropertiesCallsCount(dslProperties, blockBody)
        val functionsCalls = getDslMandatoryFunctionsCallsCount(dslProperties, blockBody)

        return (propertiesCalls + functionsCalls).toMutableMap()
            .apply {
                dslProperties.keys.forEach {
                    this@apply.putIfAbsent(it, 0)
                }
            }
    }

    // Returns mapping of group name to calls count
    private fun getDslMandatoryFunctionsCallsCount(
        dslProperties: Map<String, List<DSLMandatoryAttribute>>,
        blockBody: UBlockExpression
    ): Map<String, Int> {
        return blockBody.expressions
            .filterIsInstance<UCallExpression>()
            .filter { it.isMethodCall() }
            .mapNotNull {
                getAttributeGroup(it.methodName!!, dslProperties)
            }
            .groupingBy { it }
            .eachCount()
    }

    // Returns mapping of group name to calls count
    private fun getDslPropertiesCallsCount(
        dslProperties: Map<String, List<DSLMandatoryAttribute>>,
        blockBody: UBlockExpression
    ): Map<String, Int> {
        return blockBody.expressions
            .filterIsInstance<UBinaryExpression>()
            .filter { it.isAssignment() }
            .mapNotNull {
                getAttributeGroup(getAssignedAttributeName(it), dslProperties)
            }
            .groupingBy { it }
            .eachCount()
    }

    private fun getAssignedAttributeName(it: UBinaryExpression) =
        (((it.leftOperand as UReferenceExpression).referenceNameElement)
                as UIdentifier).name

    private fun getAttributeGroup(
        attributeName: String,
        dslProperties: Map<String, List<DSLMandatoryAttribute>>
    ): String? {
        dslProperties.forEach { (group, properties) ->
            val propertiesName = properties.map { it.name }
            if (propertiesName.contains(attributeName)) {
                return group
            }
        }
        return null
    }

    // Return mapping of group name to mandatory properties, properties with no group are grouped by their name
    private fun getDslMandatoryProperties(clazz: PsiClass): Map<String, List<DSLMandatoryAttribute>> {
        return clazz.allMethods
            .filter {
                it.hasAnnotation(DSLintAnnotation.DslMandatory.name)
            }
            .map {
                createDslMandatoryAttribute(it)
            }
            .groupBy {
                if (!it.group.isNullOrEmpty()) it.group else it.name
            }
    }

    private fun createDslMandatoryAttribute(method: PsiMethod): DSLMandatoryAttribute {
        val annotation = method.getAnnotation(DSLintAnnotation.DslMandatory.name)
        val group =
            annotation.resolveStringAttributeValue(DSLintAnnotation.DslMandatory.Attributes.group)
                .nullIfEmpty()
        val message =
            annotation.resolveStringAttributeValue(DSLintAnnotation.DslMandatory.Attributes.message)
                .nullIfEmpty()
        val isPropertySetter = PropertyUtilBase.isSetterName(method.name)
        val type = if (isPropertySetter) Type.PROPERTY else Type.FUNCTION
        val name =
            if (isPropertySetter) PropertyUtilBase.getPropertyName(method)!! else method.name
        return DSLMandatoryAttribute(name, type, group, message)
    }
}

data class DSLMandatoryAttribute(
    val name: String,
    val type: Type,
    val group: String?,
    val message: String?
)

enum class Type {
    FUNCTION, PROPERTY
}