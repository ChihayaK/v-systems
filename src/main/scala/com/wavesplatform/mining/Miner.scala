package com.wavesplatform.mining

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicBoolean

import com.wavesplatform.network._
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state2.reader.StateReader
import com.wavesplatform.{Coordinator, UtxPool}
import io.netty.channel.group.ChannelGroup
import kamon.Kamon
import kamon.metric.instrument
import monix.eval.Task
import monix.execution._
import monix.execution.cancelables.{CompositeCancelable, SerialCancelable}
import scorex.account.PrivateKeyAccount
import scorex.block.Block
import scorex.consensus.nxt.NxtLikeConsensusBlockData
import scorex.transaction.PoSCalc._
import scorex.transaction.{BlockchainUpdater, CheckpointService, History}
import scorex.utils.{ScorexLogging, Time}
import scorex.wallet.Wallet

import scala.concurrent.duration._
import scala.math.Ordering.Implicits._

class Miner(
               allChannels: ChannelGroup,
               blockchainReadiness: AtomicBoolean,
               blockchainUpdater: BlockchainUpdater,
               checkpoint: CheckpointService,
               history: History,
               stateReader: StateReader,
               settings: WavesSettings,
               timeService: Time,
               utx: UtxPool,
               wallet: Wallet) extends ScorexLogging {

  import Miner._

  private implicit val scheduler = Scheduler.fixedPool(name = "miner-pool", poolSize = 2)

  private val minerSettings = settings.minerSettings
  private val blockchainSettings = settings.blockchainSettings
  private lazy val processBlock = Coordinator.processBlock(checkpoint, history, blockchainUpdater, timeService, stateReader, utx, blockchainReadiness, Miner.this, settings) _

  private val scheduledAttempts = SerialCancelable()

  private val blockBuildTimeStats = Kamon.metrics.histogram("block-build-time", instrument.Time.Milliseconds)

  //Here use transfor to milliseconds
  //minerSettings.intervalAfterLastBlockThenGenerationIsAllowed is in milliseconds
  private def checkAge(parentHeight: Int, parent: Block): Either[String, Unit] =
    Either
      .cond(parentHeight == 1, (), Duration.between(Instant.ofEpochMilli(parent.timestamp/1000000L), Instant.ofEpochMilli(timeService.correctedTime()/1000000L)))
      .left.flatMap(blockAge => Either.cond(blockAge <= minerSettings.intervalAfterLastBlockThenGenerationIsAllowed, (),
      s"BlockChain is too old (last block ${parent.uniqueId} generated $blockAge ago)"
    ))

  private def generateOneBlockTask(account: PrivateKeyAccount, parentHeight: Int, parent: Block,
                                   greatGrandParent: Option[Block], balance: Long)(delay: FiniteDuration): Task[Either[String, Block]] = Task {
    val pc = allChannels.size()
    lazy val lastBlockKernelData = parent.consensusData
    // will use as timestamp, should in nanoseonds
    val currentTime = timeService.correctedTime()
    // start only use to record the duration
    val start = System.currentTimeMillis()
    log.debug(s"${start*1000000L}: Corrected time: $currentTime (in Nanoseonds)")
    // in SPOS case, comparison between h and t is useless
    lazy val h = calcHit(lastBlockKernelData, account)
    lazy val t = calcTarget(parent, currentTime, balance)
    for {
      _ <- Either.cond(pc >= minerSettings.quorum, (), s"Quorum not available ($pc/${minerSettings.quorum}, not forging block with ${account.address}")
      //_ <- Either.cond(h < t, (), s"${System.currentTimeMillis()} (in Millisecond): Hit $h was NOT less than target $t, not forging block with ${account.address}")
      //_ = log.debug(s"Forging with ${account.address}, H $h < T $t, balance $balance, prev block ${parent.uniqueId}")
      //_ = log.debug(s"Previous block ID ${parent.uniqueId} at $parentHeight with target ${lastBlockKernelData.baseTarget}")
      _ <- Either.cond(balance >= 1000000, (), s"${System.currentTimeMillis()} (in Millisecond): Balance $balance was NOT greater than target 1000000, not forging block with ${account.address}")
      _ = log.debug(s"Forging with ${account.address}, balance $balance, prev block ${parent.uniqueId}")
      _ = log.debug(s"Previous block ID ${parent.uniqueId} at $parentHeight with target ${lastBlockKernelData.baseTarget}")
      avgBlockDelay = blockchainSettings.genesisSettings.averageBlockDelay
      btg = calcBaseTarget(avgBlockDelay, parentHeight, parent, greatGrandParent, currentTime)
      gs = calcGeneratorSignature(lastBlockKernelData, account)
      consensusData = NxtLikeConsensusBlockData(btg, gs)
      unconfirmed = utx.packUnconfirmed()
      _ = log.debug(s"Adding ${unconfirmed.size} unconfirmed transaction(s) to new block")
      block = Block.buildAndSign(Version, currentTime, parent.uniqueId, consensusData, unconfirmed, account)
      // call currentTimeMillis to record the duration
      _ = blockBuildTimeStats.record(System.currentTimeMillis() - start)
    } yield block
  }.delayExecution(delay)

  private def generateBlockTask(account: PrivateKeyAccount): Task[Unit] = {
    val height = history.height()
    val lastBlock = history.lastBlock.get
    val grandParent = history.parent(lastBlock, 2)
    (for {
      _ <- checkAge(height, lastBlock)
      //ts <- nextBlockGenerationTime(height, stateReader, blockchainSettings.functionalitySettings, lastBlock, account)
      // useful to deal with the delay cases
      ts = 1000000000L
    } yield ts) match {
      case Right(ts) =>
        // offset control the next attempt, we can set it to constant 60s here.
        //val offset = calcOffset(timeService, ts)
        val offset = (5000-System.currentTimeMillis()%1000).millis
        log.debug(s"Next attempt for acc=$account in $offset")
        val balance = generatingBalance(stateReader, blockchainSettings.functionalitySettings, account, height)
        generateOneBlockTask(account, height, lastBlock, grandParent, balance)(offset).flatMap {
          case Right(block) => Task.now {
            processBlock(block, true) match {
              case Left(err) => log.warn(err.toString)
              case Right(score) =>
                allChannels.broadcast(LocalScoreChanged(score))
                allChannels.broadcast(BlockForged(block))
            }
          }
          case Left(err) =>
            log.debug(s"No block generated because $err, retrying")
            generateBlockTask(account)
        }
      case Left(err) =>
        log.debug(s"Not scheduling block mining because $err")
        Task.unit
    }
  }

  def lastBlockChanged(): Unit = if (settings.minerSettings.enable) {
    log.debug("Miner notified of new block, restarting all mining tasks")
    scheduledAttempts := CompositeCancelable.fromSet(
      wallet.privateKeyAccounts().map(generateBlockTask).map(_.runAsync).toSet)
  } else {
    log.debug("Miner is disabled, ignoring last block change")
  }

  def shutdown(): Unit = ()
}

object Miner extends ScorexLogging {

  val Version: Byte = 2
  val MinimalGenerationOffsetMillis: Long = 1001

  def calcOffset(timeService: Time, calculatedTimestamp: Long): FiniteDuration = {
    // calculatedTimestamp in nanoseconds
    val calculatedGenerationTimestamp = (Math.ceil(calculatedTimestamp / 1000000000.0) * 1000000000L).toLong
    log.debug(s"CalculatedTS $calculatedTimestamp: CalculatedGenerationTS: $calculatedGenerationTimestamp")
    val calculatedOffset = calculatedGenerationTimestamp - timeService.correctedTime()
    // will return a duration, so millis is ok
    Math.max(MinimalGenerationOffsetMillis, calculatedOffset/1000000L).millis
  }
}
