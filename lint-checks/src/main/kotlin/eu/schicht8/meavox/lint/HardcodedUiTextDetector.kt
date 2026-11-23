// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.skipParenthesizedExprDown

/**
 * Text the UI shows must come from string resources, so it can be translated (#43). Android's own
 * HardcodedText check only looks at XML layouts; this one finds string literals with letters in them
 * passed as a String to a `@Composable` function, to a Toast, a snackbar, a notification (or its
 * channel's name) or a share chooser, also as a branch of an `if`.
 * Parameters that aren't shown (routes, keys, tags, URLs) are left alone.
 */
class HardcodedUiTextDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve() ?: return
            val shown = shownArguments(context, node, method)
            for (argument in shown) {
                if (argument.hasLetters()) {
                    context.report(
                        ISSUE,
                        argument,
                        context.getLocation(argument),
                        "Text shown in the UI must come from a string resource, so it can be translated",
                    )
                }
            }
        }
    }

    /** The arguments of [node] that end up on screen. */
    private fun shownArguments(context: JavaContext, node: UCallExpression, method: PsiMethod): List<UExpression> {
        val evaluator = context.evaluator
        val owner = method.containingClass?.qualifiedName
        return when {
            owner == "android.widget.Toast" && method.name == "makeText" -> listOfNotNull(node.getArgumentForParameter(1))
            owner == "android.content.Intent" && method.name == "createChooser" -> listOfNotNull(node.getArgumentForParameter(1))
            owner == "androidx.compose.material3.SnackbarHostState" && method.name == "showSnackbar" -> node.valueArguments
            owner == "android.app.NotificationChannel" && method.isConstructor -> listOfNotNull(node.getArgumentForParameter(1))
            owner in NOTIFICATION_BUILDERS && method.name in NOTIFICATION_TEXT -> node.valueArguments
            owner in NOTIFICATION_BUILDERS && method.name == "addAction" -> listOfNotNull(node.getArgumentForParameter(1))
            // Their String parameters are debug labels for the inspector, not shown.
            owner.orEmpty().startsWith(ANIMATION_PACKAGE) || evaluator.getPackage(method)?.qualifiedName?.startsWith(ANIMATION_PACKAGE) == true ->
                emptyList()
            isComposable(method) -> {
                val mapping = evaluator.computeArgumentMapping(node, method)
                mapping.filter { (_, parameter) ->
                    parameter.type.canonicalText in TEXT_TYPES && parameter.name !in NOT_SHOWN
                }.keys.toList()
            }
            else -> emptyList()
        }
    }

    private fun isComposable(method: PsiMethod): Boolean = method.annotations.any { it.qualifiedName == COMPOSABLE }

    /** Whether the expression is a string literal or template with a letter in its literal text, or an `if` with one. */
    private fun UExpression.hasLetters(): Boolean = when (val e = skipParenthesizedExprDown()) {
        is ULiteralExpression -> (e.value as? String)?.any(Char::isLetter) == true
        is UPolyadicExpression -> e.operands.any { it is ULiteralExpression && (it.value as? String)?.any(Char::isLetter) == true }
        is UIfExpression -> e.thenExpression?.hasLetters() == true || e.elseExpression?.hasLetters() == true
        else -> false
    }

    companion object {
        private const val COMPOSABLE = "androidx.compose.runtime.Composable"
        private const val ANIMATION_PACKAGE = "androidx.compose.animation"
        private val TEXT_TYPES = setOf("java.lang.String", "java.lang.CharSequence")
        private val NOTIFICATION_BUILDERS = setOf("androidx.core.app.NotificationCompat.Builder", "android.app.Notification.Builder")
        private val NOTIFICATION_TEXT = setOf("setContentTitle", "setContentText", "setSubText", "setTicker")

        /** String parameters of composables that are never shown. */
        private val NOT_SHOWN = setOf(
            "route", "startDestination", "key", "tag", "testTag", "url", "uri", "contentType", "fontFeatureSettings", "mimeType",
        )

        val ISSUE: Issue = Issue.create(
            id = "HardcodedUiText",
            briefDescription = "Hardcoded UI text",
            explanation = """
                Text the UI shows must come from `strings.xml` (`stringResource`, `getString`), so that \
                it is translated (#43). Symbols without letters (`−`, `·`) are fine. A text that must not \
                be translated, such as a format name, goes in `strings.xml` with `translatable="false"`.
            """,
            category = Category.I18N,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(HardcodedUiTextDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
