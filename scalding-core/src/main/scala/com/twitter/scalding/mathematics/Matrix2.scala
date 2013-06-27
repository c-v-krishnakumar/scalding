package com.twitter.scalding.mathematics

import cascading.pipe.Pipe
import cascading.tuple.Fields
import com.twitter.scalding.TDsl._
import com.twitter.scalding._
import com.twitter.algebird.{ Monoid, Ring }
import scala.collection.mutable.HashMap

object Matrix2 {
  sealed abstract class Matrix2(val sizeHint: SizeHint = NoClue) {
    def +(that: Matrix2): Matrix2 = Sum(this, that)
    def *(that: Matrix2): Matrix2 = Product(this, that)
    val tpipe: TypedPipe[(Int, Int, Double)]
    def transpose: Matrix2 = Literal(tpipe.map(x => (x._2, x._1, x._3)), sizeHint)
  }

  case class Product(left: Matrix2, right: Matrix2, optimal: Boolean = false) extends Matrix2 {
    def toPipe()(implicit ring: Ring[Double], ord: Ordering[(Int, Int)]): TypedPipe[(Int, Int, Double)] = {
      val one = left.tpipe.groupBy(x => x._2)
      val two = right.tpipe.groupBy(x => x._1)

      one.join(two).mapValueStream(x => x.map(y => (y._1._1, y._2._2, ring.times(y._1._3, y._2._3)))).values.
        groupBy(w => (w._1, w._2)).mapValueStream(s => Iterator(s.reduce((a, b) => (a._1, a._2, ring.plus(a._3, b._3))))).values

    }

    override lazy val tpipe = toPipe()
    override val sizeHint = left.sizeHint * right.sizeHint
  }

  case class Sum(left: Matrix2, right: Matrix2) extends Matrix2 {
    def toPipe()(implicit mon: Monoid[Double], ord: Ordering[(Int, Int)]): TypedPipe[(Int, Int, Double)] = {
      if (left.equals(right)) {
        left.tpipe.map(v => (v._1, v._2, mon.plus(v._3, v._3)))
      } else {
        (left.tpipe ++ right.tpipe).groupBy(x => (x._1, x._2)).mapValueStream(vals => {
          if (vals.size == 1) vals else {
            val l = vals.next()
            val r = vals.next()
            val res = mon.plus(l._3, r._3)
            if (res == mon.zero) Iterator() else Iterator((l._1, l._2, res))
          }
        }).toTypedPipe.map(y => y._2)
      }
    }

    override lazy val tpipe = toPipe()
    override val sizeHint = left.sizeHint + right.sizeHint
  }

  case class Literal(override val tpipe: TypedPipe[(Int, Int, Double)], override val sizeHint: SizeHint) extends Matrix2 {
    def this(fields: Fields, inPipe: Pipe, sizeHint: SizeHint) = this(inPipe.toTypedPipe[(Int, Int, Double)](fields), sizeHint)
  }

  /**
   * The original prototype that employs the standard O(n^3) dynamic programming
   * procedure to optimize a matrix chain factorization
   */
  def optimizeProductChain(p: IndexedSeq[Literal]): (Long, Matrix2) = {

    val subchainCosts = HashMap.empty[(Int, Int), Long]

    val splitMarkers = HashMap.empty[(Int, Int), Int]

    def computeCosts(p: IndexedSeq[Literal], i: Int, j: Int): Long = {
      if (subchainCosts.contains((i, j))) subchainCosts((i, j))
      if (i == j) subchainCosts.put((i, j), 0)
      else {
        subchainCosts.put((i, j), Long.MaxValue)
        for (k <- i to (j - 1)) {
          val cost = computeCosts(p, i, k) + computeCosts(p, k + 1, j) +
            (p(i).sizeHint * (p(k).sizeHint * p(j).sizeHint)).total.get
          if (cost < subchainCosts((i, j))) {
            subchainCosts.put((i, j), cost)
            splitMarkers.put((i, j), k)
          }
        }
      }

      subchainCosts((i, j))
    }

    def generatePlan(i: Int, j: Int): Matrix2 = {
      if (i == j) p(i)
      else {
        val k = splitMarkers((i, j))
        val left = generatePlan(i, k)
        val right = generatePlan(k + 1, j)
        val result = Product(left, right, true)
        result
      }

    }

    val best = computeCosts(p, 0, p.length - 1)

    (best, generatePlan(0, p.length - 1))
  }

  /**
   * This function walks the input tree, finds basic blocks to optimize,
   * i.e. matrix product chains that are not interrupted by summations.
   * One example:
   * A*B*C*(D+E)*(F*G) => "basic blocks" are ABC, D, E, and FG
   */
  def optimize(mf: Matrix2): (Long, Matrix2) = {

    /**
     * Helper function that either returns an optimized product chain
     * or the last visited place in the tree
     */
    def chainOrLast(chain: List[Literal], last: Option[(Long, Matrix2)]): (Long, Matrix2) = {
      if (chain.isEmpty) last.get
      else optimizeProductChain(chain.toIndexedSeq)
    }

    /**
     * Recursive function - returns a flatten product chain so far and the rest of the connected tree
     */
    def optimizeBasicBlocks(mf: Matrix2): (List[Literal], Option[(Long, Matrix2)]) = {
      mf match {
        // basic block of one matrix
        case element: Literal => (List(element), None)
        // two potential basic blocks connected by a sum
        case Sum(left, right) => {
          val (lastLChain, leftTemp) = optimizeBasicBlocks(left)
          val (lastRChain, rightTemp) = optimizeBasicBlocks(right)
          val (cost1, newLeft) = chainOrLast(lastLChain, leftTemp)
          val (cost2, newRight) = chainOrLast(lastRChain, rightTemp)
          (Nil, Some(cost1 + cost2, Sum(newLeft, newRight)))
        }
        // basic block A*B
        case Product(leftp: Literal, rightp: Literal, _) => {
          (List(leftp, rightp), None)
        }
        // potential chain (...something...)*right or just two basic blocks connected by a product
        case Product(left: Product, right: Literal, _) => {
          val (lastLChain, leftTemp) = optimizeBasicBlocks(left)
          if (lastLChain.isEmpty) {
            val (cost, newLeft) = leftTemp.get
            val interProduct = Product(newLeft, right, true)
            (Nil, Some(cost, interProduct))
          } else {
            (lastLChain ++ List(right), leftTemp)
          }
        }
        // potential chain left*(...something...) or just two basic blocks connected by a product
        case Product(left: Literal, right: Product, _) => {
          val (lastRChain, rightTemp) = optimizeBasicBlocks(right)
          if (lastRChain.isEmpty) {
            val (cost, newRight) = rightTemp.get
            val interProduct = Product(left, newRight, true)
            (Nil, Some(cost, interProduct))
          } else {
            (left :: lastRChain, rightTemp)
          }
        }
        // potential chain (...something...)*(...something...) or just two basic blocks connected by a product
        case Product(left, right, _) => {
          val (lastLChain, leftTemp) = optimizeBasicBlocks(left)
          val (lastRChain, rightTemp) = optimizeBasicBlocks(right)
          if (lastLChain.isEmpty) {
            val (cost1, newLeft) = leftTemp.get
            val (cost2, newRight) = chainOrLast(lastRChain, rightTemp)
            (Nil, Some(cost1 + cost2, Product(newLeft, newRight, true)))
          } else {
            if (lastRChain.isEmpty) {
              val (cost1, newLeft) = optimizeProductChain(lastLChain.toIndexedSeq)
              val (cost2, newRight) = rightTemp.get
              (Nil, Some(cost1 + cost2, Product(newLeft, newRight, true)))
            } else {
              (lastLChain ++ lastRChain, None)
            }
          }
        }
      }
    }
    val (lastChain, form) = optimizeBasicBlocks(mf)

    chainOrLast(lastChain, form)
  }

}
