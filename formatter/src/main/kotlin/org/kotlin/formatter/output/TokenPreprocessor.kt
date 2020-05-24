package org.kotlin.formatter.output

import java.lang.Integer.min
import java.util.Stack
import org.kotlin.formatter.BeginToken
import org.kotlin.formatter.BlockFromMarkerToken
import org.kotlin.formatter.ClosingForcedBreakToken
import org.kotlin.formatter.ClosingSynchronizedBreakToken
import org.kotlin.formatter.EndToken
import org.kotlin.formatter.ForcedBreakToken
import org.kotlin.formatter.KDocContentToken
import org.kotlin.formatter.LeafNodeToken
import org.kotlin.formatter.MarkerToken
import org.kotlin.formatter.State
import org.kotlin.formatter.SynchronizedBreakToken
import org.kotlin.formatter.Token
import org.kotlin.formatter.WhitespaceToken

/**
 * Prepares a list of [Token] for output.
 *
 * This assigns lengths to the [WhitespaceToken] and [BeginToken] in the list based on the lengths
 * of the following tokens. With these data, a [Printer] can decide whether to introduce a line
 * break at a given [WhitespaceToken].
 *
 * This implementation is based loosely on the "scan" operation of the "Prettyprinting" algorithm of
 * Derek Oppen [1] with some inspiration from the
 * [Google Java formatter](https://github.com/google/google-java-format).
 *
 * [1] Oppen, Derek C. "Prettyprinting". ACM Transactions on Programming Languages and Systems,
 * Volume 2 Issue 4, Oct. 1980, pp. 465-483.
 */
class TokenPreprocessor {
    private val resultStack = Stack<StackElement>()

    /**
     * Returns a list of [Token] with the lengths of all [WhitespaceToken] and [BeginToken]
     * assigned.
     *
     * Moves [EndToken] instances to appear after any non-breaking tokens which follow them,
     * adjusting the block lengths accordingly.
     *
     * Replaces all instances of [BlockFromMarkerToken] with a [BeginToken], [EndToken] pair
     * stretching from the previous [MarkerToken] in the current block, or from the previous
     * [BeginToken] if there is no forced break in the current block. The output contains no
     * instances of [MarkerToken] or [BlockFromMarkerToken].
     *
     * Replaces [SynchronizedBreakToken] and [ClosingSynchronizedBreakToken] instances by equivalent
     * [ForcedBreakToken] respectively [ClosingForcedBreakToken] whenever either of the following
     * holds:
     *
     *  * There is a [ForcedBreakToken], [ClosingForcedBreakToken] in the same block.
     *  * There is already a [KDocContentToken] with a newline character in the same block.
     *
     * Any [SynchronizedBreakToken] or [ClosingSynchronizedBreakToken] which immediately follows
     * a [ForcedBreakToken] or [ClosingForcedBreakToken] is dropped.
     *
     * Any [WhitespaceToken] containing newlines and immediately preceding a block of
     * [comment type][State.isComment] is converted into a [ForcedBreakToken] with the same number
     * of up to two newlines.
     *
     * Any existing values of [WhitespaceToken.length] and [BeginToken.length] in [input] are
     * ignored by this process.
     */
    fun preprocess(input: List<Token>): List<Token> {
        var foundEndTokens = 0
        resultStack.push(BlockStackElement(State.CODE))
        for (token in input) {
            if (token !is LeafNodeToken && token !is EndToken) {
                while (foundEndTokens > 0) {
                    handleEndToken()
                    foundEndTokens--
                }
            }

            when (token) {
                is WhitespaceToken -> {
                    if (token.content.isNotEmpty() || !lastTokenWasWhitespace()) {
                        resultStack.push(WhitespaceStackElement(token.content))
                    }
                }
                is BeginToken -> {
                    resultStack.push(BlockStackElement(token.state))
                }
                is EndToken -> {
                    ++foundEndTokens
                }
                is MarkerToken -> {
                    resultStack.push(MarkerElement())
                }
                is BlockFromMarkerToken -> {
                    val poppedElement = popBlockToMarker()
                    if (poppedElement is BlockStackElement) {
                        resultStack.push(BlockStackElement(State.CODE))
                    }
                    appendTokensInElement(poppedElement, State.CODE)
                }
                is SynchronizedBreakToken, is ClosingSynchronizedBreakToken -> {
                    val lastToken = resultStack.peek().tokens.lastOrNull()
                    if (!(lastToken is ForcedBreakToken || lastToken is ClosingForcedBreakToken)) {
                        resultStack.peek().tokens.add(token)
                    }
                }
                else -> resultStack.peek().tokens.add(token)
            }
        }
        while (foundEndTokens-- > 0) {
            handleEndToken()
        }
        return popBlock().tokens
    }

    private fun lastTokenWasWhitespace() =
        resultStack.peek() is WhitespaceStackElement && resultStack.peek().tokens.isEmpty()

    private fun handleEndToken() {
        val blockElement = popBlock().apply { replaceSynchronizedBreaks() }
        appendTokensInElement(blockElement, blockElement.state)
    }

    private fun popBlock(): BlockStackElement =
        when (val topElement = resultStack.pop()) {
            is BlockStackElement -> topElement
            is WhitespaceStackElement -> {
                appendTokensInWhitespaceElement(topElement)
                popBlock()
            }
            is MarkerElement -> {
                resultStack.peek().tokens.addAll(topElement.tokens)
                popBlock()
            }
        }

    private fun popBlockToMarker(): StackElement =
        when (val topElement = resultStack.pop()) {
            is BlockStackElement, is MarkerElement -> topElement
            is WhitespaceStackElement -> {
                appendTokensInWhitespaceElement(topElement)
                popBlockToMarker()
            }
        }

    private fun appendTokensInElement(stackElement: StackElement, state: State) {
        val topElement = resultStack.peek()
        topElement.tokens.add(BeginToken(length = stackElement.textLength, state = state))
        topElement.tokens.addAll(stackElement.tokens)
        topElement.tokens.add(EndToken)
    }

    private fun appendTokensInWhitespaceElement(element: WhitespaceStackElement) {
        val firstToken = element.tokens.firstOrNull()
        if (firstToken is BeginToken && firstToken.state.isComment && element.content.contains('\n')
        ) {
            resultStack.peek()
                .tokens
                .add(ForcedBreakToken(count = min(element.content.countNewlines(), 2)))
        } else {
            resultStack.peek()
                .tokens
                .add(WhitespaceToken(length = element.totalLength, content = element.content))
        }
        resultStack.peek().tokens.addAll(element.tokens)
    }

    private fun String.countNewlines(): Int = count { it == '\n' }
}

private sealed class StackElement(internal val tokens: MutableList<Token> = mutableListOf()) {
    internal val textLength: Int
        get() =
            tokens
                .map {
                    when (it) {
                        is WhitespaceToken -> if (it.content.isEmpty()) 0 else 1
                        is SynchronizedBreakToken -> it.whitespaceLength
                        is ClosingSynchronizedBreakToken -> it.whitespaceLength
                        is LeafNodeToken -> it.textLength
                        is KDocContentToken -> it.textLength
                        else -> 0
                    }
                }
                .sum()
}

private class BlockStackElement(
    internal val state: State,
    tokens: MutableList<Token> = mutableListOf()
) : StackElement(tokens) {
    internal fun replaceSynchronizedBreaks() {
        if (shouldConvertSynchronizedBreaksToForcedBreaks()) {
            var level = 0
            tokens.replaceAll {
                when {
                    it is SynchronizedBreakToken && level == 0 -> ForcedBreakToken(count = 1)
                    it is ClosingSynchronizedBreakToken && level == 0 -> ClosingForcedBreakToken
                    it is BeginToken -> {
                        level++
                        it
                    }
                    it is EndToken -> {
                        level--
                        it
                    }
                    else -> it
                }
            }
        }
    }

    private fun shouldConvertSynchronizedBreaksToForcedBreaks() =
        tokens.any { it.forcesSynchronizedBreakConversionToForcedBreak() }

    private fun Token.forcesSynchronizedBreakConversionToForcedBreak(): Boolean =
        this is ForcedBreakToken || this is ClosingForcedBreakToken ||
            (this is KDocContentToken && content.contains('\n'))
}

private class WhitespaceStackElement(internal val content: String) : StackElement() {
    private val contentLength: Int = if (content.isEmpty()) 0 else 1

    internal val totalLength: Int
        get() = contentLength + initialTextLength

    private val initialTextLength: Int
        get() = tokens.firstOrNull()?.textLength ?: 0
}

private class MarkerElement : StackElement()

private val Token.textLength: Int
    get() =
        when (this) {
            is LeafNodeToken -> textLength
            is KDocContentToken -> textLength
            is BeginToken -> length
            else -> 0
        }
