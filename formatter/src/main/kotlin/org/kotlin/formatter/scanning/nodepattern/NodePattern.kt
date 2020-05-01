package org.kotlin.formatter.scanning.nodepattern

import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.kotlin.formatter.Token

class NodePattern internal constructor(private val initialState: State) {
    fun matchSequence(nodes: Iterable<ASTNode>): List<Token> {
        var paths = listOf<PathStep>(InitialPathStep(initialState))
        for (node in nodes) {
            paths = epsilonStep(paths)
            paths = setNodeOnPaths(paths, node)
            paths = step(paths, node)
        }
        paths = epsilonStep(paths)
        paths = step(paths, TerminalNode)
        return paths.firstOrNull { it is FinalPathStep }?.runActions()?.tokens
            ?: throw Exception("Could not match node sequence ${nodes.toList()}")
    }

    private fun setNodeOnPaths(paths: List<PathStep>, node: ASTNode): List<PathStep> =
        paths.map { it.withNode(node) }

    private fun epsilonStep(paths: List<PathStep>): List<PathStep> =
        paths.flatMap { if (it is PathStepOnState) epsilonStepForPath(it) else listOf(it) }

    private fun epsilonStepForPath(startingPath: PathStepOnState): List<PathStep> {
        val result = mutableListOf<PathStep>(startingPath)
        var allStates = setOf(startingPath.state)
        var nextStates: List<State>
        do {
            result.addAll(result.filterIsInstance<PathStepOnState>().flatMap { path ->
                path.state.immediateNextStates.minus(allStates)
                    .map { state -> ContinuingPathStep(state, path) }
            })
            allStates = result.filterIsInstance<PathStepOnState>().map { it.state }.toSet()
            nextStates = allStates.flatMap { it.immediateNextStates }
        } while (!allStates.containsAll(nextStates))
        return result
    }

    private fun step(paths: List<PathStep>, node: ASTNode): List<PathStep> =
        paths.flatMap { path ->
            if (path is PathStepOnState) {
                path.state.matchingNextStates(node).map { state ->
                    if (state.isTerminal) {
                        FinalPathStep(state, path)
                    } else {
                        ContinuingPathStep(state, path)
                    }
                }
            } else {
                listOf()
            }
        }
}

private sealed class PathStep {
    abstract fun runActions(): Evaluation

    abstract fun withNode(node: ASTNode): PathStep
}

private abstract class PathStepOnState(
    internal val state: State,
    internal val node: ASTNode?
) : PathStep()

private class InitialPathStep(state: State, node: ASTNode? = null) : PathStepOnState(state, node) {
    override fun runActions(): Evaluation = state.action(Evaluation(listOf(), listOf()), node)

    override fun withNode(node: ASTNode): PathStep = InitialPathStep(state, node)
}

private class ContinuingPathStep(
    state: State,
    internal val previous: PathStep,
    node: ASTNode? = null
) : PathStepOnState(state, node) {
    override fun runActions(): Evaluation = state.action(previous.runActions(), node)

    override fun withNode(node: ASTNode): PathStep = ContinuingPathStep(state, previous, node)
}

private class FinalPathStep(internal val state: State, val previous: PathStep) : PathStep() {
    override fun runActions(): Evaluation = previous.runActions()

    override fun withNode(node: ASTNode): PathStep = this
}

internal object TerminalNode: LeafPsiElement(KtTokens.EOF, "")
