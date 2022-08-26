package io.ergodex.core.sim.lqmining

import io.ergodex.core.sim.{LedgerCtx, LedgerState}
import io.ergodex.core.sim.lqmining.LMPool.MaxCapTT

final case class LMConfig(
  frameLen: Int,
  epochLen: Int,
  epochNum: Int,
  programStart: Int,
  programBudget: Long
) {
  val programEnd: Int  = programStart + frameLen * epochNum * epochLen
  val framesNum: Int   = epochNum * epochLen
  val epochAlloc: Long = programBudget / epochNum
}

final case class PoolReserves(X: Long, LQ: Long, vLQ: Long, TT: Long) {
  val emissionTT: Long = MaxCapTT - TT
}

final case class StakingBundle(vLQ: Long, TT: Long)

object Token {
  type X
  type LQ
  type TT
}

final case class AssetInput[T](value: Long)
final case class AssetOutput[T](value: Long)
final case class BurnAsset[T](value: Long)

final case class LMPool[Ledger[_]: LedgerState](
  conf: LMConfig,
  reserves: PoolReserves,
  lqAllocSum: Long,
  lastUpdatedAtFrameIx: Int,
  lastUpdatedAtEpochIx: Int
) {
  import LMPool._

  def updateReserves(fn: PoolReserves => PoolReserves): LMPool[Ledger] =
    copy(reserves = fn(reserves))

  private def frameAndEpochIx(ctx: LedgerCtx): (Int, Int) = {
    val curFrameIxNum = ctx.height - conf.programStart + 1
    val curFrameIxRem = curFrameIxNum % conf.frameLen
    val curFrameIxR   = curFrameIxNum / conf.frameLen
    val curFrameIx    = if (curFrameIxRem > 0) curFrameIxR + 1 else curFrameIxR
    val curEpochIxRem = curFrameIx    % conf.epochLen
    val curEpochIxR   = curFrameIx / conf.epochLen
    val curEpochIx    = if (curEpochIxRem > 0) curEpochIxR + 1 else curEpochIxR
    curFrameIx -> curEpochIx
  }

  def deposit(lq: AssetInput[Token.LQ]): Ledger[VerifiedST[(LMPool[Ledger], StakingBundle)]] =
    LedgerState.withLedgerState { ctx =>
      if (ctx.height < conf.programEnd) {
        val (curFrameIx, curEpochIx) = frameAndEpochIx(ctx)
        val epochsToCompound         = conf.epochNum - curEpochIx
        if (reserves.X - epochsToCompound * conf.epochAlloc <= conf.epochAlloc) {
          val releasedVLQ = lq.value
          val releasedTT  = conf.framesNum - math.max(0, curFrameIx)
          val (lqAllocSum_, frameIx_, epochIx_) =
            if (ctx.height < conf.programStart) {
              (lqAllocSum, lastUpdatedAtFrameIx, lastUpdatedAtEpochIx)
            } else if (curEpochIx != lastUpdatedAtEpochIx) {
              val passedFrames = curFrameIx - (curEpochIx - 1) * conf.epochLen
              (passedFrames * reserves.LQ, curFrameIx, curFrameIx)
            } else if (curFrameIx != lastUpdatedAtFrameIx) {
              val passedFrames = curFrameIx - lastUpdatedAtFrameIx
              (lqAllocSum + passedFrames * reserves.LQ, curFrameIx, lastUpdatedAtEpochIx)
            } else {
              (lqAllocSum, lastUpdatedAtFrameIx, lastUpdatedAtEpochIx)
            }
          Right(
            copy(
              reserves = reserves.copy(
                LQ  = reserves.LQ + lq.value,
                vLQ = reserves.vLQ - releasedVLQ,
                TT  = reserves.TT - releasedTT
              ),
              lqAllocSum           = lqAllocSum_,
              lastUpdatedAtFrameIx = frameIx_,
              lastUpdatedAtEpochIx = epochIx_
            ) ->
            StakingBundle(releasedVLQ, releasedTT.toLong)
          )
        } else {
          Left(PrevEpochNotWithdrawn)
        }
      } else Left(ProgramEnded)
    }

  def compound(
    bundle: StakingBundle,
    epoch: Int
  ): Ledger[VerifiedST[(LMPool[Ledger], StakingBundle, AssetOutput[Token.X], BurnAsset[Token.TT])]] =
    LedgerState.withLedgerState { ctx =>
      val (_, curEpochIx)  = frameAndEpochIx(ctx)
      val epochsToCompound = conf.epochNum - epoch
      if (epoch <= curEpochIx - 1) {
        if (reserves.X - epochsToCompound * conf.epochAlloc <= conf.epochAlloc) {
          val inputEpochTT = bundle.TT - conf.epochLen * epochsToCompound
          val lqAllocSum_ =
            if (lastUpdatedAtEpochIx != (curEpochIx - 1)) {
              reserves.LQ * conf.epochLen // reserves haven't been updated for the whole past epoch.
            } else if (lastUpdatedAtFrameIx != epoch * conf.epochLen) {
              val framesUntouched = epoch * conf.epochLen - lastUpdatedAtFrameIx
              reserves.LQ * framesUntouched + lqAllocSum
            } else {
              lqAllocSum
            }
          val reward =
            (BigInt(conf.epochAlloc) * inputEpochTT * bundle.vLQ * conf.epochLen /
              (BigInt(lqAllocSum_) * conf.epochLen)).toLong
          Right(
            (
              updateReserves(r => PoolReserves(X = r.X - reward, r.LQ, r.vLQ, TT = r.TT)),
              bundle.copy(TT = bundle.TT - inputEpochTT),
              AssetOutput(reward),
              BurnAsset(inputEpochTT)
            )
          )
        } else {
          Left(PrevEpochNotWithdrawn)
        }
      } else {
        Left(IllegalEpoch)
      }
    }

  def redeem(bundle: StakingBundle): Ledger[VerifiedST[(LMPool[Ledger], AssetOutput[Token.LQ])]] =
    LedgerState.withLedgerState { ctx =>
      val releasedLQ      = bundle.vLQ
      val (curFrameIx, _) = frameAndEpochIx(ctx)
      val lqAllocSum_ =
        if (lastUpdatedAtFrameIx == curFrameIx) lqAllocSum - releasedLQ
        else lqAllocSum
      Right(
        copy(
          reserves = reserves.copy(
            LQ  = reserves.LQ - bundle.vLQ,
            vLQ = reserves.vLQ + bundle.vLQ,
            TT  = reserves.TT + bundle.TT
          ),
          lqAllocSum = lqAllocSum_
        ) ->
        AssetOutput(releasedLQ)
      )
    }
}

object LMPool {
  val MaxCapVLQ: Long = Long.MaxValue
  val MaxCapTT: Long  = Long.MaxValue

  sealed trait LMPoolErr
  case object ProgramEnded extends LMPoolErr
  case object PrevEpochNotWithdrawn extends LMPoolErr
  case object EpochAlreadyWithdrawn extends LMPoolErr
  case object IllegalEpoch extends LMPoolErr

  type VerifiedST[+A] = Either[LMPoolErr, A]

  def init[Ledger[_]: LedgerState](
    frameLen: Int,
    epochLen: Int,
    epochNum: Int,
    programStart: Int,
    programBudget: Long
  ): LMPool[Ledger] =
    LMPool(
      LMConfig(frameLen, epochLen, epochNum, programStart, programBudget),
      PoolReserves(programBudget, 0L, MaxCapVLQ, MaxCapTT),
      lqAllocSum           = 0L,
      lastUpdatedAtFrameIx = 0,
      lastUpdatedAtEpochIx = 0
    )
}
