package io.ergodex.core.sim.lqmining

import io.ergodex.core.sim.ToLedger._
import io.ergodex.core.sim.lqmining.Token._
import io.ergodex.core.sim.{LedgerPlatform, RuntimeCtx}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class LqMiningPoolBoxSpec extends AnyFlatSpec with should.Matchers with ScalaCheckPropertyChecks with LedgerPlatform {

  val pool01: LMPool[Ledger] =
    LMPool.init(frameLen = 1, epochLen = 2, epochNum = 3, programStart = 2, programBudget = 900000000L)

  val input0: AssetInput[LQ] = AssetInput(1000000)
  val input1: AssetInput[LQ] = AssetInput(2000000)

  it should "validate deposit behaviour mirrored from simulation" in {
    val startAtHeight          = 2
    val action                 = pool01.deposit(input0)
    val (_, Right((pool1, _))) = action.run(RuntimeCtx.at(startAtHeight)).value
    val poolBox0               = pool01.toLedger[Ledger]
    val poolBox1               = pool1.toLedger[Ledger]
    val (_, isValid)           = poolBox0.validator.run(RuntimeCtx(startAtHeight, outputs = List(poolBox1))).value
    isValid shouldBe true
  }

  it should "validate compound behaviour mirrored from simulation" in {
    val startAtHeight = 2
    val epochStep     = 2
    val action = for {
      Right((pool1, sb1))  <- pool01.deposit(input0)
      Right((pool2, sb2))  <- pool1.deposit(input1)
      _                    <- Ledger.extendBy(epochStep)
      Right((pool3, _, _)) <- pool2.compound(sb1, epoch = 1)
      Right((pool4, _, _)) <- pool3.compound(sb2, epoch = 1)
    } yield (pool2, pool3, pool4)
    val (_, (pool2, pool3, pool4)) = action.run(RuntimeCtx.at(startAtHeight)).value

    val poolBox2 = pool2.toLedger[Ledger]
    val poolBox3 = pool3.toLedger[Ledger]
    val poolBox4 = pool4.toLedger[Ledger]

    val (_, isValidFirstCompounding) =
      poolBox2.validator.run(RuntimeCtx(startAtHeight + epochStep, vars = Map(0 -> 1), outputs = List(poolBox3))).value
    val (_, isValidSecondCompounding) =
      poolBox3.validator.run(RuntimeCtx(startAtHeight + epochStep, vars = Map(0 -> 1), outputs = List(poolBox4))).value

    isValidFirstCompounding shouldBe true
    isValidSecondCompounding shouldBe true
  }
}
