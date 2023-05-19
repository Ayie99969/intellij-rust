/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.mir.dataflow.move

import org.rust.lang.core.mir.WithIndex
import org.rust.lang.core.mir.schemas.*
import org.rust.lang.core.mir.util.IndexAlloc
import org.rust.lang.core.mir.util.IndexKeyMap
import org.rust.lang.core.mir.util.LocationMap
import org.rust.lang.core.types.ty.*
import org.rust.stdext.RsResult
import org.rust.stdext.RsResult.Err
import org.rust.stdext.RsResult.Ok
import org.rust.stdext.dequeOf

/**
 * [MovePath] is a canonicalized representation of a path that is moved or assigned to.
 *
 * It follows a tree structure.
 *
 * Given `struct X { m: M, n: N }` and `x: X`, moves like `move x.m;` move *out* of the place `x.m`.
 *
 * The [MovePath]s representing `x.m` and `x.n` are siblings (that is, one of them will link to
 * the other via the `next_sibling` field, and the other will have no entry in its `next_sibling`
 * field), and they both have the MovePath representing `x` as their parent.
 */
class MovePath(
    override val index: Int,
    val place: MirPlace,
    val parent: MovePath? = null,
    var nextSibling: MovePath? = null,
    var firstChild: MovePath? = null,
) : WithIndex {

    val ancestors: Sequence<MovePath>
        get() = generateSequence(this) { it.parent }

    fun findInMovePathOrItsDescendants(predicate: (MovePath) -> Boolean): MovePath? {
        if (predicate(this)) return this
        return findDescendant(predicate)
    }

    private fun findDescendant(predicate: (MovePath) -> Boolean): MovePath? {
        val firstChild = firstChild ?: return null
        val queue = dequeOf(firstChild)
        while (queue.isNotEmpty()) {
            val element = queue.pop()
            if (predicate(element)) return element
            element.firstChild?.let { queue.push(it) }
            element.nextSibling?.let { queue.push(it) }
        }
        return null
    }
}

/**
 * [MoveOut] represents a point in a program that moves out of some L-value; i.e., "creates" uninitialized memory.
 *
 * With respect to dataflow analysis:
 * - Generated by moves and declaration of uninitialized variables.
 * - Killed by assignments to the memory.
 */
class MoveOut(
    override val index: Int,
    val path: MovePath,
    val source: MirLocation,
) : WithIndex

/**
 * [Init] represents a point in a program that initializes some L-value;
 */
class Init(
    override val index: Int,
    /** path being initialized */
    val path: MovePath,
    /** location of initialization */
    val location: InitLocation,
    /** Extra information about this initialization */
    val kind: InitKind,
) : WithIndex

/**
 * Initializations can be from an argument or from a statement. Argument
 * do not have locations, in those cases the `Local` is kept
 */
sealed interface InitLocation {
    data class Argument(val local: MirLocal) : InitLocation
    data class Statement(val location: MirLocation) : InitLocation
}

enum class InitKind {
    /** Deep init, even on panic */
    Deep,

    /** Only does a shallow init */
    Shallow,

    /** This doesn't initialize the variable on panic (and a panic is possible) */
    NonPanicPathOnly,
}

interface MoveData {
    /**
     * All [MoveOut]'s grouped by [MoveOut.source].
     * (There can be multiple [MoveOut]'s for a given [MirLocation])
     */
    val locMap: Map<MirLocation, MutableList<MoveOut>>

    /** All [MoveOut]'s grouped by [MoveOut.path]. */
    val pathMap: Map<MovePath, MutableList<MoveOut>>

    /** Maps [MirPlace] to the nearest [MovePath] */
    val revLookup: MovePathLookup

    /** [Init]'s grouped by [Init.location] when the location is [InitLocation.Statement] */
    val initLocMap: Map<MirLocation, MutableList<Init>>

    /** All [Init]'s grouped by [Init.path] */
    val initPathMap: Map<MovePath, MutableList<Init>>

    /** The number of [MovePath]'s exists in this [MoveData] */
    val movePathsCount: Int

    /**
     * For the move path `initPath`, returns the root local variable (if any) that starts the path. (e.g., for a path
     * like `a.b.c` returns `Some(a)`)
     */
    fun baseLocal(initPath: MovePath): MirLocal? {
        var path = initPath
        while (true) {
            val local = path.place.local
            if (local != null) return local
            val parent = path.parent
            if (parent != null) {
                path = parent
                continue
            } else {
                return null
            }
        }
    }

    companion object {
        fun gatherMoves(body: MirBody): MoveData {
            val builder = MoveDataBuilder.new(body)

            // TODO gather args

            for (bb in body.basicBlocks) {
                for ((i, stmt) in bb.statements.withIndex()) {
                    val loc = MirLocation(bb, i)
                    builder.gatherStatement(loc, stmt)
                }
                val terminatorLoc = MirLocation(bb, bb.statements.size)
                builder.gatherTerminator(terminatorLoc, bb.terminator)
            }

            return builder.finalize()
        }
    }
}

private class MoveDataImpl(
    val movePaths: IndexAlloc<MovePath>,
    val moves: IndexAlloc<MoveOut>,
    override val locMap: LocationMap<MutableList<MoveOut>>,
    override val pathMap: IndexKeyMap<MovePath, MutableList<MoveOut>>,
    override val revLookup: MovePathLookup,
    val inits: IndexAlloc<Init>,
    override val initLocMap: LocationMap<MutableList<Init>>,
    override val initPathMap: IndexKeyMap<MovePath, MutableList<Init>>,
) : MoveData {
    override val movePathsCount: Int
        get() = movePaths.size
}

/** Tables mapping from a [MirPlace] to its [MovePath] */
class MovePathLookup(
    val locals: Map<MirLocal, MovePath>,
    val projections: MutableMap<Pair<MovePath, MirAbstractElem>, MovePath>,
) {
    fun find(place: MirPlace): LookupResult {
        var result = locals[place.local]!!
        for (elem in place.projections) {
            result = projections[result to elem.lift()]
                ?: return LookupResult.Parent(result)
        }
        return LookupResult.Exact(result)
    }
}


sealed interface LookupResult {
    data class Exact(val movePath: MovePath) : LookupResult
    data class Parent(val movePath: MovePath?) : LookupResult
}

sealed class MoveError

private class MoveDataBuilder(
    private val body: MirBody,
    private val data: MoveDataImpl,
) {
    private lateinit var loc: MirLocation

    private fun createMovePath(place: MirPlace) {
        movePathFor(place)
    }

    private fun movePathFor(place: MirPlace): RsResult<MovePath, MoveError> {
        var base = data.revLookup.locals[place.local]!!
        place.projections.forEachIndexed { i, elem ->
            val projectionBase = place.projections.subList(0, i)
            when (val placeTy = MirPlace.tyFrom(place.local, projectionBase).ty) {
                is TyReference, is TyPointer -> TODO()
                is TyAdt -> TODO()
                is TySlice -> TODO()
                is TyArray -> TODO()
                else -> Unit
            }

            base = addMovePath(base, elem) {
                MirPlace(place.local, place.projections.subList(0, i + 1))
            }
        }
        return Ok(base)
    }

    private fun addMovePath(base: MovePath, element: MirProjectionElem<Ty>, makePlace: () -> MirPlace): MovePath {
        val key = base to element.lift()
        return data.revLookup.projections.getOrPut(key) {
            newMovePath(data.movePaths, data.pathMap, data.initPathMap, base, makePlace())
        }
    }

    fun gatherStatement(loc: MirLocation, stmt: MirStatement) {
        this.loc = loc
        when (stmt) {
            is MirStatement.Assign -> {
                // TODO Rvalue::CopyForDeref
                createMovePath(stmt.place)
                // TODO Rvalue::ShallowInitBox
                gatherInit(stmt.place, InitKind.Deep)
                gatherRvalue(stmt.rvalue)
            }

            is MirStatement.FakeRead -> createMovePath(stmt.place)
            is MirStatement.StorageLive -> Unit
            is MirStatement.StorageDead -> gatherMove(MirPlace(stmt.local))
        }
    }

    fun gatherTerminator(loc: MirLocation, term: MirTerminator<*>) {
        this.loc = loc
        when (term) {
            is MirTerminator.Assert -> gatherOperand(term.cond)
            is MirTerminator.SwitchInt -> gatherOperand(term.discriminant)
            is MirTerminator.FalseUnwind -> Unit
            is MirTerminator.Goto -> Unit
            is MirTerminator.Resume -> Unit
            is MirTerminator.Return -> Unit
            is MirTerminator.Unreachable -> Unit
            is MirTerminator.Call -> TODO()
        }
    }

    private fun gatherInit(place: MirPlace, kind: InitKind) {
        // TODO union
        when (val lookup = data.revLookup.find(place)) {
            is LookupResult.Exact -> {
                val path = lookup.movePath
                val init = data.inits.allocate { Init(it, path, InitLocation.Statement(loc), kind) }
                data.initPathMap[path]!!.add(init)
                data.initLocMap.getOrPut(loc) { mutableListOf() }.add(init)
            }

            else -> Unit
        }
    }

    private fun gatherRvalue(rvalue: MirRvalue) {
        when (rvalue) {
            is MirRvalue.Use -> gatherOperand(rvalue.operand)
            is MirRvalue.Repeat -> gatherOperand(rvalue.operand)
            is MirRvalue.Aggregate -> {
                for (operand in rvalue.operands) {
                    gatherOperand(operand)
                }
            }

            is MirRvalue.BinaryOpUse -> {
                gatherOperand(rvalue.left)
                gatherOperand(rvalue.right)
            }

            is MirRvalue.CheckedBinaryOpUse -> {
                gatherOperand(rvalue.left)
                gatherOperand(rvalue.right)
            }

            is MirRvalue.UnaryOpUse -> gatherOperand(rvalue.operand)
            is MirRvalue.Ref -> Unit
        }
    }

    private fun gatherOperand(operand: MirOperand) {
        if (operand is MirOperand.Move) {
            gatherMove(operand.place)
        }
    }

    private fun gatherMove(place: MirPlace) {
        // TODO ProjectionElem::Subslice
        when (val path = movePathFor(place)) {
            is Ok -> recordMove(path.ok)
            is Err -> TODO()
        }
    }

    private fun recordMove(path: MovePath) {
        val moveOut = data.moves.allocate { MoveOut(it, path, loc) }
        data.pathMap[path]!!.add(moveOut)
        data.locMap.getOrPut(loc) { mutableListOf() }.add(moveOut)
    }

    fun finalize(): MoveDataImpl {
        return data
    }

    companion object {
        fun new(body: MirBody): MoveDataBuilder {
            val movePaths = IndexAlloc<MovePath>()
            val pathMap = IndexKeyMap<MovePath, MutableList<MoveOut>>()
            val initPathMap = IndexKeyMap<MovePath, MutableList<Init>>()

            return MoveDataBuilder(
                body,
                data = MoveDataImpl(
                    movePaths = movePaths,
                    moves = IndexAlloc(),
                    locMap = LocationMap(body),
                    pathMap = pathMap,
                    revLookup = MovePathLookup(
                        locals = IndexKeyMap.fromListUnchecked(body.localDecls.map {
                            newMovePath(movePaths, pathMap, initPathMap, null, MirPlace(it))
                        }),
                        projections = hashMapOf()
                    ),
                    inits = IndexAlloc(),
                    initLocMap = LocationMap(body),
                    initPathMap = initPathMap,
                ),
            )
        }

        private fun newMovePath(
            movePaths: IndexAlloc<MovePath>,
            pathMap: IndexKeyMap<MovePath, MutableList<MoveOut>>,
            initPathMap: IndexKeyMap<MovePath, MutableList<Init>>,
            parent: MovePath?,
            place: MirPlace,
        ): MovePath {
            val movePath = movePaths.allocate { MovePath(it, place, parent) }
            if (parent != null) {
                val nextSibling = parent.firstChild
                parent.firstChild = movePath
                movePath.nextSibling = nextSibling
            }
            pathMap[movePath] = mutableListOf()
            initPathMap[movePath] = mutableListOf()
            return movePath
        }
    }
}
