package scorex.transaction.lease

import com.google.common.primitives.{Bytes, Longs}
import com.wavesplatform.state2.ByteStr
import play.api.libs.json.{JsObject, Json}
import scorex.account.{AddressOrAlias, Address, PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.EllipticCurveImpl
import scorex.transaction.TransactionParser._
import scorex.transaction._

import scala.util.{Failure, Success, Try}

case class LeaseTransaction private(sender: PublicKeyAccount,
                                    amount: Long,
                                    fee: Long,
                                    timestamp: Long,
                                    recipient: AddressOrAlias,
                                    signature: ByteStr)
  extends SignedTransaction {

  override val transactionType: TransactionType.Value = TransactionType.LeaseTransaction

  lazy val toSign: Array[Byte] = Bytes.concat(Array(transactionType.id.toByte),
    sender.publicKey,
    recipient.bytes.arr,
    Longs.toByteArray(amount),
    Longs.toByteArray(fee),
    Longs.toByteArray(timestamp))

  override lazy val json: JsObject = jsonBase() ++ Json.obj(
    "amount" -> amount,
    "recipient" -> recipient.stringRepr,
    "fee" -> fee,
    "timestamp" -> timestamp
  )

  // TODO
  // add feeScale in assetFee, need to change 100 later
  override val assetFee: (Option[AssetId], Long, Short) = (None, fee, 100)
  override lazy val bytes: Array[Byte] = Bytes.concat(toSign, signature.arr)

}

object LeaseTransaction {

  def parseTail(bytes: Array[Byte]): Try[LeaseTransaction] = Try {
    import EllipticCurveImpl._
    val sender = PublicKeyAccount(bytes.slice(0, KeyLength))
    (for {
      recRes <- AddressOrAlias.fromBytes(bytes, KeyLength)
      (recipient, recipientEnd) = recRes
      quantityStart = recipientEnd
      quantity = Longs.fromByteArray(bytes.slice(quantityStart, quantityStart + 8))
      fee = Longs.fromByteArray(bytes.slice(quantityStart + 8, quantityStart + 16))
      timestamp = Longs.fromByteArray(bytes.slice(quantityStart + 16, quantityStart + 24))
      signature = ByteStr(bytes.slice(quantityStart + 24, quantityStart + 24 + SignatureLength))
      lt <- LeaseTransaction.create(sender, quantity, fee, timestamp, recipient, signature)
    } yield lt).fold(left => Failure(new Exception(left.toString)), right => Success(right))
  }.flatten

  def create(sender: PublicKeyAccount,
             amount: Long,
             fee: Long,
             timestamp: Long,
             recipient: AddressOrAlias,
             signature: ByteStr): Either[ValidationError, LeaseTransaction] = {
    if (amount <= 0) {
      Left(ValidationError.NegativeAmount)
    } else if (Try(Math.addExact(amount, fee)).isFailure) {
      Left(ValidationError.OverflowError)
    } else if (fee <= 0) {
      Left(ValidationError.InsufficientFee)
    } else if (recipient.isInstanceOf[Address] && sender.stringRepr == recipient.stringRepr) {
      Left(ValidationError.ToSelf)
    } else {
      Right(LeaseTransaction(sender, amount, fee, timestamp, recipient, signature))
    }
  }

  def create(sender: PrivateKeyAccount,
             amount: Long,
             fee: Long,
             timestamp: Long,
             recipient: AddressOrAlias): Either[ValidationError, LeaseTransaction] = {
    create(sender, amount, fee, timestamp, recipient, ByteStr.empty).right.map { unsigned =>
      unsigned.copy(signature = ByteStr(EllipticCurveImpl.sign(sender, unsigned.toSign)))
    }
  }
}
