package tools

import vee.settings.{GenesisSettings, GenesisTransactionSettings}
import com.wavesplatform.state2.ByteStr
import scorex.account.{Address, AddressScheme, PrivateKeyAccount}
import scorex.block.Block
import scorex.consensus.nxt.NxtLikeConsensusBlockData
import scorex.crypto.hash.FastCryptographicHash.DigestSize
import scorex.transaction.GenesisTransaction
import scorex.transaction.TransactionParser.SignatureLength
import scorex.wallet.Wallet
import scala.concurrent.duration._

object GenesisBlockGenerator extends App {

  val genesisSigner = PrivateKeyAccount(Array.empty)
  val reference = ByteStr(Array.fill(SignatureLength)(-1: Byte))
  val distributions = Map(
    1 -> Seq(1000000000000000L),
    2 -> Seq(800000000000000L, 200000000000000L),
    3 -> Seq(650000000000000L, 200000000000000L, 150000000000000L),
    4 -> Seq(700000000000000L, 200000000000000L, 150000000000000L, 50000000000000L)
  )

  // add test use wallet address
  val test_wallet_addresses = Array (
      "3N1YJ6RaYDkmh1fiy8ww7qCXDnySqyxceDS",
      "3NCorpZy4JhrtXtKeqLTft7Li79vehDssvr",
      "3MvRSHqRtn4sWgwr3EnDrP6VjphnQrrEB6t"

  )

  def generateFullAddressInfo(n: Int) = {
    println("n=" + n + ", address = " + test_wallet_addresses(n))

    val seed = Array.fill(32)((scala.util.Random.nextInt(256)).toByte)
    val acc = Wallet.generateNewAccount(seed, 0)
    val privateKey = ByteStr(acc.privateKey)
    val publicKey = ByteStr(acc.publicKey)
    // change address value for testnet
    //    val address = acc.toAddress
    val address = Address.fromString(test_wallet_addresses(n)).right.get  //ByteStr(Base58.decode(test_wallet_addresses(n)).get)

    (ByteStr(seed), ByteStr(acc.seed), privateKey, publicKey, address)
  }

  def generate(networkByte: Char, accountsTotal: Int, baseTraget: Long, averageBlockDelay: FiniteDuration) = {
    scorex.account.AddressScheme.current = new AddressScheme {
      override val chainId: Byte = networkByte.toByte
    }
    val timestamp = System.currentTimeMillis()
    val initialBalance = 1000000000000000L

    val accounts = Range(0, accountsTotal).map(n => n -> generateFullAddressInfo(n))
    val genesisTxs = accounts.map { case (n, (_, _, _, _, address)) => GenesisTransaction(address, distributions(accountsTotal)(n), timestamp, ByteStr.empty) }
    val genesisBlock = Block.buildAndSign(1, timestamp, reference, NxtLikeConsensusBlockData(baseTraget, Array.fill(DigestSize)(0: Byte)), genesisTxs, genesisSigner)
    val signature = genesisBlock.signerData.signature

    (accounts, GenesisSettings(timestamp, timestamp, initialBalance, Some(signature),
      genesisTxs.map(tx => GenesisTransactionSettings(tx.recipient.stringRepr, tx.amount)), baseTraget, averageBlockDelay))

  }

  def print(accs: Seq[(Int, (ByteStr, ByteStr, ByteStr, ByteStr, Address))], settings: GenesisSettings): Unit = {

    println("Addresses:")
    accs.foreach { case (n, (seed, accSeed, priv, pub, addess)) =>
      println(
        s"""($n):
           | seed: $seed
           | accSeed: $accSeed
           | priv: $priv
           | pub : $pub
           | addr: ${addess.address}
           |
       """.stripMargin)
    }

    println(
      s"""GenesisSettings:
         | timestamp: ${settings.timestamp}
         | blockTimestamp: ${settings.blockTimestamp}
         | averageBlockDelay: ${settings.averageBlockDelay}
         | initialBalance: ${settings.initialBalance}
         | initialBaseTarget: ${settings.initialBaseTarget}
         | signature: ${settings.signature}
         | transactions: ${settings.transactions.mkString("\n   ", "\n   ", "")}
     """.stripMargin)
  }

  val (a, s) = generate('T', 3, 153722867, 60.seconds)
  print(a, s)


}
