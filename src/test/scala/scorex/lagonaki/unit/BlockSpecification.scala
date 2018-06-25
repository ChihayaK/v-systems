package scorex.lagonaki.unit

import com.wavesplatform.state2.ByteStr
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FunSuite, Matchers}
import scorex.account.PrivateKeyAccount
import scorex.block.Block
import scorex.consensus.nxt.NxtLikeConsensusBlockData
import scorex.transaction._
import scorex.transaction.assets.TransferTransaction

import scala.util.Random

class BlockSpecification extends FunSuite with Matchers with MockFactory {

  test(" block with txs bytes/parse roundtrip") {

    val reference = Array.fill(Block.BlockIdLength)(Random.nextInt(100).toByte)
    val gen = PrivateKeyAccount(reference)

    val mt = System.currentTimeMillis()/10000*10000000000000L
    val gs = Array.fill(Block.GeneratorSignatureLength)(Random.nextInt(100).toByte)


    val ts = System.currentTimeMillis()*1000000L+System.nanoTime()%1000000L - 5000000000L
    val sender = PrivateKeyAccount(reference.dropRight(2))
    val tx: Transaction = PaymentTransaction.create(sender, gen, 5, 1000, ts).right.get
    val tr: TransferTransaction = TransferTransaction.create(None, sender, gen, 5, ts + 1, None, 2, Array()).right.get
    val assetId = Some(ByteStr(Array.fill(AssetIdLength)(Random.nextInt(100).toByte)))
    val tr2: TransferTransaction = TransferTransaction.create(assetId, sender, gen, 5, ts + 2, None, 2, Array()).right.get

    val tbd = Seq(tx, tr, tr2)
    val cbd = NxtLikeConsensusBlockData(mt, gs)

    List(1, 2).foreach { version =>
      val timestamp = System.currentTimeMillis()*1000000L+System.nanoTime()%1000000L

      val block = Block.buildAndSign(version.toByte, timestamp, ByteStr(reference), cbd, tbd, gen)
      val parsedBlock = Block.parseBytes(block.bytes).get
      assert(Signed.validateSignatures(block).isRight)
      assert(Signed.validateSignatures(parsedBlock).isRight)
      assert(parsedBlock.consensusData.generationSignature.sameElements(gs))
      assert(parsedBlock.version.toInt == version)
      assert(parsedBlock.signerData.generator.publicKey.sameElements(gen.publicKey))
    }
  }
}
