package com.wavesplatform.it.transactions

import com.wavesplatform.it.util._
import com.wavesplatform.it.{IntegrationSuiteWithThreeAddresses, Node}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.traverse
import scala.concurrent.duration._

class BurnTransactionSpecification(override val allNodes: Seq[Node], override val notMiner: Node)
  extends IntegrationSuiteWithThreeAddresses {

  private val defaultQuantity = 100000
  test("burning assets changes issuer's asset balance; issuer's waves balance is decreased by fee") {
    val f = for {
      _ <- assertBalances(firstAddress, 100.vee, 100.vee)

      issuedAssetId <- sender.issue(firstAddress, "name", "description", defaultQuantity, 2, reissuable = false, fee = 1.vee).map(_.id)

      height <- traverse(allNodes)(_.height).map(_.max)
      _ <- traverse(allNodes)(_.waitForHeight(height + 1))
      _ <- traverse(allNodes)(_.waitForTransaction(issuedAssetId))

      _ <- assertBalances(firstAddress, 99.vee, 99.vee)
      _ <- assertAssetBalance(firstAddress, issuedAssetId, defaultQuantity)

      burnId <- sender.burn(firstAddress, issuedAssetId, defaultQuantity / 2, fee = 1.vee).map(_.id)

      height <- traverse(allNodes)(_.height).map(_.max)
      _ <- traverse(allNodes)(_.waitForHeight(height + 1))
      _ <- traverse(allNodes)(_.waitForTransaction(burnId))

      _ <- assertBalances(firstAddress, 98.vee, 98.vee)

      _ <- assertAssetBalance(firstAddress, issuedAssetId, defaultQuantity / 2)
    } yield succeed

    Await.result(f, 1.minute)
  }
}
