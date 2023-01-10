package io.ergodex.core.cfmm3.t2t

import io.ergodex.core.BoxRuntime.NonRunnable
import io.ergodex.core.RuntimeState.withRuntimeState
import io.ergodex.core.syntax._
import io.ergodex.core.{AnyBox, BoxSim, RuntimeState, TryFromBox}

final class CfmmPoolBox[F[_]: RuntimeState](
  override val id: Coll[Byte],
  override val value: Long,
  override val creationHeight: Int,
  override val tokens: Coll[(Coll[Byte], Long)],
  override val registers: Map[Int, Any],
  override val constants: Map[Int, Any],
  override val validatorBytes: String
) extends BoxSim[F] {

  override val validator: F[Boolean] =
    withRuntimeState { implicit ctx =>
      val InitiallyLockedLP = 0x7fffffffffffffffL

      val feeNum0  = SELF.R4[Int].get
      val FeeDenom = 1000

      val ergs0       = SELF.value
      val poolNFT0    = SELF.tokens(0)
      val reservedLP0 = SELF.tokens(1)
      val tokenX0     = SELF.tokens(2)
      val tokenY0     = SELF.tokens(3)

      val successor = OUTPUTS(0)

      val feeNum1 = successor.R4[Int].get

      val ergs1       = successor.value
      val poolNFT1    = successor.tokens(0)
      val reservedLP1 = successor.tokens(1)
      val tokenX1     = successor.tokens(2)
      val tokenY1     = successor.tokens(3)

      val validSuccessorScript = successor.propositionBytes == SELF.propositionBytes
      val preservedFeeConfig   = feeNum1 == feeNum0
      val preservedErgs        = ergs1 >= ergs0
      val preservedPoolNFT     = poolNFT1 == poolNFT0
      val validLP              = reservedLP1._1 == reservedLP0._1
      val validPair            = tokenX1._1 == tokenX0._1 && tokenY1._1 == tokenY0._1

      val supplyLP0 = InitiallyLockedLP - reservedLP0._2
      val supplyLP1 = InitiallyLockedLP - reservedLP1._2

      val reservesX0 = tokenX0._2
      val reservesY0 = tokenY0._2
      val reservesX1 = tokenX1._2
      val reservesY1 = tokenY1._2

      val deltaSupplyLP  = supplyLP1 - supplyLP0
      val deltaReservesX = reservesX1 - reservesX0
      val deltaReservesY = reservesY1 - reservesY0

      val validDepositing = {
        val sharesUnlocked = min(
          deltaReservesX.toBigInt * supplyLP0 / reservesX0,
          deltaReservesY.toBigInt * supplyLP0 / reservesY0
        )
        deltaSupplyLP <= sharesUnlocked
      }

      val validRedemption = {
        val _deltaSupplyLP = deltaSupplyLP.toBigInt
        // note: _deltaSupplyLP and deltaReservesX, deltaReservesY are negative
        deltaReservesX.toBigInt * supplyLP0 >= _deltaSupplyLP * reservesX0 && deltaReservesY.toBigInt * supplyLP0 >= _deltaSupplyLP * reservesY0
      }

      val validSwap =
        if (deltaReservesX > 0)
          reservesY0.toBigInt * deltaReservesX * feeNum0 >= -deltaReservesY * (reservesX0.toBigInt * FeeDenom + deltaReservesX * feeNum0)
        else
          reservesX0.toBigInt * deltaReservesY * feeNum0 >= -deltaReservesX * (reservesY0.toBigInt * FeeDenom + deltaReservesY * feeNum0)

      val validAction =
        if (deltaSupplyLP == 0)
          validSwap
        else if (deltaReservesX > 0 && deltaReservesY > 0) validDepositing
        else validRedemption

      sigmaProp(
        validSuccessorScript &&
        preservedFeeConfig &&
        preservedErgs &&
        preservedPoolNFT &&
        validLP &&
        validPair &&
        validAction
      )
    }
}

object CfmmPoolBox {
  def apply[F[_]: RuntimeState, G[_]](bx: BoxSim[G]): CfmmPoolBox[F]      =
    new CfmmPoolBox(bx.id, bx.value, bx.creationHeight, bx.tokens, bx.registers, bx.constants, bx.validatorBytes)
  implicit def tryFromBox[F[_]: RuntimeState]: TryFromBox[CfmmPoolBox, F] =
    AnyBox.tryFromBox.translate(apply[F, NonRunnable])
}
