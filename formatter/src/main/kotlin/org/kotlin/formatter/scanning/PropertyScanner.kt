package org.kotlin.formatter.scanning

import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.psiUtil.children
import org.kotlin.formatter.BlockFromLastForcedBreakToken
import org.kotlin.formatter.ClosingForcedBreakToken
import org.kotlin.formatter.ForcedBreakToken
import org.kotlin.formatter.LeafNodeToken
import org.kotlin.formatter.State
import org.kotlin.formatter.Token
import org.kotlin.formatter.WhitespaceToken
import org.kotlin.formatter.nonBreakingSpaceToken
import org.kotlin.formatter.scanning.nodepattern.NodePatternBuilder
import org.kotlin.formatter.scanning.nodepattern.nodePattern

/** A [NodeScanner] for property declarations. */
internal class PropertyScanner(private val kotlinScanner: KotlinScanner): NodeScanner {
    private val nodePattern = nodePattern {
        exactlyOne {
            declarationWithOptionalModifierList(kotlinScanner)
            zeroOrOne { propertyInitializer(kotlinScanner) }
            zeroOrMore {
                nodeOfType(KtNodeTypes.PROPERTY_ACCESSOR) andThen { nodes ->
                    listOf(
                        ForcedBreakToken(count = 1),
                        *kotlinScanner.scanNodes(nodes, ScannerState.STATEMENT).toTypedArray()
                    )
                }
            }
        } thenMapTokens { inBeginEndBlock(it, State.CODE) }
        end()
    }

    override fun scan(node: ASTNode, scannerState: ScannerState): List<Token> =
        nodePattern.matchSequence(node.children().asIterable())
}

/**
 * Adds to the receiver [NodePatternBuilder] a sequence matching a class, function, or property
 * declaration which be preceded by a modifier list.
 */
internal fun NodePatternBuilder.declarationWithOptionalModifierList(kotlinScanner: KotlinScanner) {
    optionalKDoc(kotlinScanner)
    either {
        exactlyOne {
            nodeOfType(KtNodeTypes.MODIFIER_LIST) andThen { nodes ->
                kotlinScanner.scanNodes(nodes, ScannerState.STATEMENT)
            }
            possibleWhitespace()
            anyNode() andThen { nodes ->
                kotlinScanner.scanNodes(nodes, ScannerState.STATEMENT)
            }
        } thenMapTokens { tokens ->
            insertWhitespaceIfNoForcedBreakIsPresent(tokens)
        }
        zeroOrMoreFrugal { anyNode() } andThen { nodes ->
            kotlinScanner.scanNodes(nodes, ScannerState.STATEMENT)
        }
    } or {
        oneOrMoreFrugal { anyNode() } andThen { nodes ->
            kotlinScanner.scanNodes(nodes, ScannerState.STATEMENT)
        }
    }
}

/**
 * Adds to the receiver [NodePatternBuilder] a sequence matching optionally present KDoc.
 */
internal fun NodePatternBuilder.optionalKDoc(kotlinScanner: KotlinScanner) {
    zeroOrOne {
        nodeOfType(KDocTokens.KDOC)
        possibleWhitespace()
    } andThen { nodes ->
        if (nodes.isNotEmpty()) {
            listOf(
                *kotlinScanner.scanNodes(nodes, ScannerState.KDOC).toTypedArray(),

                // We use a ClosingForcedBreakToken rather than a ForcedBreakToken here
                // because the modifier list is inside a block representing the full
                // property, class, or function declaration, and a ForcedBreakToken would
                // then cause the next lines to indent with the next standard indent.
                ClosingForcedBreakToken
            )
        } else {
            listOf()
        }
    }
}

private fun insertWhitespaceIfNoForcedBreakIsPresent(tokens: List<Token>): List<Token> {
    return if (tokens[tokens.size - 2] is ClosingForcedBreakToken) {
        tokens
    } else {
        listOf(
            *tokens.subList(0, tokens.size - 1).toTypedArray(),
            WhitespaceToken(" "),
            tokens.last()
        )
    }
}

/**
 * Adds to the receiver [NodePatternBuilder] a sequence matching the initializer for a property or
 * function.
 */
internal fun NodePatternBuilder.propertyInitializer(kotlinScanner: KotlinScanner) {
    possibleWhitespace()
    nodeOfType(KtTokens.EQ)
    possibleWhitespace()
    zeroOrMore { anyNode() } andThen { nodes ->
        val tokens = kotlinScanner.scanNodes(nodes, ScannerState.STATEMENT)
        listOf(
            nonBreakingSpaceToken(),
            LeafNodeToken("="),
            BlockFromLastForcedBreakToken,
            WhitespaceToken(" "),
            *tokens.toTypedArray()
        )
    }
}
