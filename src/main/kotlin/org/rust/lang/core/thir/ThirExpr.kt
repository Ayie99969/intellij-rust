/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.thir

import org.rust.lang.core.mir.schemas.MirBorrowKind
import org.rust.lang.core.mir.schemas.MirSpan
import org.rust.lang.core.psi.RsLitExpr
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.ArithmeticOp
import org.rust.lang.core.psi.ext.BinaryOperator
import org.rust.lang.core.psi.ext.LogicOp
import org.rust.lang.core.psi.ext.UnaryOperator
import org.rust.lang.core.types.consts.Const
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.regions.Scope as RegionScope

sealed class ThirExpr(val ty: Ty, val span: MirSpan) {

    /**
     * The lifetime of this expression if it should be spilled into a temporary;
     * Should be `null` only if in a constant context
     */
    val tempLifetime: RegionScope? = null  // TODO

    class Scope(
        val regionScope: RegionScope,
        val expr: ThirExpr,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Literal(
        val literal: RsLitExpr,
        val neg: Boolean,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** For literals that don't correspond to anything in the HIR */
    class NonHirLiteral(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A literal of a ZST type. */
    class ZstLiteral(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** Associated constants and named constants */
    class NamedConst(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class ConstParam(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /**
     * A literal containing the address of a `static`.
     * This is only distinguished from `Literal` so that we can register some info for diagnostics.
     */
    class StaticRef(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Unary(
        val op: UnaryOperator,
        val arg: ThirExpr,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Binary(
        val op: ArithmeticOp,
        val left: ThirExpr,
        val right: ThirExpr,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Logical(
        val op: LogicOp,
        val left: ThirExpr,
        val right: ThirExpr,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Block(
        val block: ThirBlock,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class If(
        val ifThenScope: RegionScope.IfThen,
        val cond: ThirExpr,
        val then: ThirExpr,
        val `else`: ThirExpr?,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Array(
        val fields: List<ThirExpr>,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Repeat(
        val value: ThirExpr,
        val count: Const,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Tuple(
        val fields: List<ThirExpr>,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** Access to a field of a struct, a tuple, an union, or an enum */
    class Field(
        val expr: ThirExpr,
        /** This can be a named (`.foo`) or unnamed (`.0`) field */
        val fieldIndex: MirFieldIndex,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Loop(
        val body: ThirExpr,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class NeverToAny(
        val spanExpr: ThirExpr,
        ty: Ty,
        span: MirSpan
    ) : ThirExpr(ty, span)

    class Break(
        val label: RegionScope,
        val expr: ThirExpr?,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Continue(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Return(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class VarRef(
        val local: LocalVar,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** Used to represent upvars mentioned in a closure/generator */
    class UpvarRef(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Assign(
        val left: ThirExpr,
        val right: ThirExpr,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A non-overloaded operation assignment, e.g. `lhs += rhs` */
    class AssignOp(
        val op: BinaryOperator,
        val left: ThirExpr,
        val right: ThirExpr,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Adt(
        val definition: RsStructItem,
        // TODO: more properties here
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Borrow(
        val kind: MirBorrowKind,
        val arg: ThirExpr,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A `&raw [const|mut] $place_expr` raw borrow resulting in type `*[const|mut] T`. */
    class AddressOf(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A `box <value>` expression. */
    class Box(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A function call. Method calls and overloaded operators are converted to plain function calls. */
    class Call(
        val fnTy: Ty,
        val callee: ThirExpr,
        val args: List<ThirExpr>,
        val fromCall: Boolean,
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A *non-overloaded* dereference. */
    class Deref(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A cast: `<source> as <type>`. The type we cast to is the type of the parent expression. */
    class Cast(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Use(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Pointer(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Let(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Match(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A *non-overloaded* indexing operation. */
    class Index(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** An inline `const` block, e.g. `const {}`. */
    class ConstBlock(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A type ascription on a place. */
    class PlaceTypeAscription(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** A type ascription on a value, e.g. `42: i32`. */
    class ValueTypeAscription(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Closure(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** Inline assembly, i.e. `asm!()`. */
    class InlineAsm(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** Field offset (`offset_of!`) */
    class OffsetOf(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    /** An expression taking a reference to a thread local. */
    class ThreadLocalRef(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)

    class Yield(
        ty: Ty,
        span: MirSpan,
    ) : ThirExpr(ty, span)
}

typealias MirFieldIndex = Int
