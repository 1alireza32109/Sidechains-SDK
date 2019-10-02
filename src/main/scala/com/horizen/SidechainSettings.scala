package com.horizen

import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.lang.{Byte => JByte, Long => JLong}
import java.util.{ArrayList => JArrayList, HashMap => JHashMap}

import javafx.util.{Pair => JPair}
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.horizen.block.{SidechainBlock, SidechainBlockSerializer}
import com.horizen.box.{NoncedBox, RegularBox}
import com.horizen.companion.SidechainTransactionsCompanion
import com.horizen.proof.Signature25519
import com.horizen.proposition.{Proposition, PublicKey25519Proposition}
import com.horizen.secret.{PrivateKey25519, PrivateKey25519Creator}
import com.horizen.transaction.{RegularTransaction, SidechainTransaction, TransactionSerializer}
import com.horizen.utils.BytesUtils
import scorex.core.settings.{ScorexSettings, SettingsReaders}
import scorex.core.settings.ScorexSettings.readConfigFromPath
import scorex.util.ScorexLogging
import scorex.util._

case class WebSocketClientSettings(
                                    remoteAddress: InetSocketAddress = new InetSocketAddress("127.0.0.1", 8888),
                                    connectionTimeout : Long = 5000,
                                    connectionTimeUnit :String = "MILLISECONDS",
                                    responseTimeout : Long = 7000,
                                    responseTimeUnit :String = "MILLISECONDS")

case class SidechainSettings(scorexSettings: ScorexSettings, webSocketClientSettings: WebSocketClientSettings) {

  protected val sidechainTransactionsCompanion: SidechainTransactionsCompanion = SidechainTransactionsCompanion(new JHashMap[JByte, TransactionSerializer[SidechainTypes#SCBT]]())

  val secretKey = PrivateKey25519Creator.getInstance().generateSecret("genesis_seed%d".format(123).getBytes)

  val targetSecretKey1 = PrivateKey25519Creator.getInstance().generateSecret("target1".getBytes)
  val targetSecretKey2 = PrivateKey25519Creator.getInstance().generateSecret("target2".getBytes)

  private def getGenesisTransactions: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]] = {
    val fee = 0
    val timestamp = 1547798549470L

    val from = new JArrayList[JPair[RegularBox, PrivateKey25519]]
    val to = new JArrayList[JPair[PublicKey25519Proposition, JLong]]

    val creator = PrivateKey25519Creator.getInstance

    from.add(new JPair[RegularBox, PrivateKey25519](new RegularBox(secretKey.publicImage, 1, 30000L), secretKey))

    to.add(new JPair[PublicKey25519Proposition, JLong](targetSecretKey1.publicImage, 10000L))
    to.add(new JPair[PublicKey25519Proposition, JLong](targetSecretKey2.publicImage, 20000L))

    val transaction = RegularTransaction.create(from, to, fee, timestamp)
    val id = transaction.id
    Seq(transaction.asInstanceOf[SidechainTransaction[Proposition, NoncedBox[Proposition]]])
  }

  lazy val genesisBlock : Option[SidechainBlock] = Some(
    new SidechainBlockSerializer(sidechainTransactionsCompanion).parseBytes(
      // Hex representation of Sidehcain block generated by commented code below and above.
      BytesUtils.fromHexString("0000000000000000000000000000000000000000000000000000000000000000aaf3d3d40bac1702a6179e170000002082353a6a8c5e4740668f635fd82f4555868ee392dbc61901ee43681000000000ea62636b38e21e51db58c217f62012e0f6d6485aaae63f8e2c8562d3e73232490000000000000000000000000000000000000000000000000000000000000000d576b95cccbe1b1c000000000000000060001d750000000000001900000000000000000000000000fd40050071b0b2b7c4ee9ca29a015e72c6e5e07c779fbcf6149beec89a0640bcda2cb2f9fa71d399804211ae2406f40a4a521003593d8c96887cd2f2c1e3ed9421442816c4dd4d1719fbb926d380d6704d5f0b725f40e70aff8963ca1e859bcd5ce13d05d3b51452727d54cb0ce0f19ce209e8be8b5dd26f05f717a936fb9fb4c41d9685e4e889d772e640ccba27eb5717c5ecffe7281ff807d34851215c960ea3efe42dddba66ed5f382f01bb58d03e37043bfa6f020be21d7919c9d1bbef7b06e584560e1f194d9628117fcd2b242cf0a009c41108dbde1f0ac51e30e5025517b4f4363dcba5f356081e510686d8dd756bb96967c0e7c4d97a427cfdea5209280d487d9927975ce1a1fd6c3dd560c9e6903f4d17231adbe00fe715dffc8625914547dabb957f0bf20ee912d79ad0d6e2e4d4e577fbd7684389755f019d1e337aea07a060f59b177545c1e0649d6d5f3d96950636bedaf31e00dbf26a9490a2593f95a760b2c47b11154d2fb23019afb37281b2b7c1fd01473b5612d01d648f1fb931c1afa00543c1ff465f85dca7b5a86d26d966262b1236bb9714875bc15975de73807504331475fc7bc11a72a128fc07936c54477301fc5f6fd14cce6449f677280bf3e3a69f0ab65b2dab04fdb88b3ce554795bf59ce1c1a6a5fd81e9a8b5e67e71f84c4b99ef58b194cd24dc8ba8136cf4cc8a6b3b953341079a758e7c90ff52907de28286144050b23956ff76279ab1b3821709811bfb67dc3ee4d88a20347e7bc60edecae396ac48b9814b4454523968dd7aa0d1b99e47efffa22e2b709df641f81718f7529a1b3f92a4ef0bc422b5de87ec518bbe24721e523a6aa7021c06ba178eeb037a4d9434b552529b3f4d8301bdcc5e1ac60e2fa113f41998777bdba16bb50d0b84ebc698ac7222d67dca19d4074afe471341784efdede43070a8a1009882e772c6072ce462c1329fcd5b80d745c8af470da7c643e438a6ebf145f77ffb49cb3dfbd958b4a31de89d4d2e7d1f81ea7ed76356671dae9ce5d5ea625594432990ec1ed39a0686967241618a06add85743061a55ef9f2f99c1bdad633e90d5fdc518121a904e09b5afeb46ad8d2b75c4c7c2a56e7d1a2a5d1538242bb1cc393658202d23e602fd5c313c68bfe459300f30f572d934a9b40deac3d61bcd6437de160b98279605fb3c252804bf0e970432470e7db0bd7a76529d992bd9873317b09c1fa50d14fc89e979c9fda89865f806198a872598917db924a962c9e347967733588a0808ac3c7d720b23bd283204cb6450da799272decfb70d04d7b0be78d2b1fee242a880e338796dc996484321f91ebb2d21bbc1d0de45335b446bcd4d9c1a51eb335ecc5e2fce181fbfd715aaf64600d62727ffd6bc45ebd4ecf3da8c5f4dd4d8bbae696a8374b75db22c0be017cd03a42dbd8511c0cc00ded91a14ea3d7bdd19d0c359cb548821e4ab627e3f2e5dd94dd6794ebb2d19559f6ba395c1c56573fc3256a9760264b8039d36dffd2ba010be8bee67d69de6c119960513fd3b67102a444c13472c670904b3ccf6e9629f88623e3b27176afa876d546a01dfe61687cb3f7f0e3a977fef5e1345f425924ce054a7de37c2994bb232eac8d888d3460e14229a130059a77fb9187afc4c2a8f1c7aed8b0c9a5eee04ad09adaa34015b942cc52225fd5260b23eec532e9d114d974b1d04bad1c0b4697910395d3a0e8531a3751337faa68073675047ba84e338943b122c3b13f8e14f3308d4cd66c3a5b70e3a764bfb31600d69b66dd0618c598ec10231f72e575f707b1dae7b2a9f1ddec959d78fd4275593f83a240aad5f3dcda5119b1db5db0a9c5994d5522c6b94ffb9759eb1562c18cc7fbb1be14f7dcd9a04388ce9431e2da01b419e0000d00302ca03010000000000000000000001685ffb93de0000003202603cfbf879f515ff96c9031ebcbc006170dd6c30522a35d488c7472c4a75dc11480000000000000001000000000000753000000043044040bf126e8d1db806f1b7d6ec11449c7c9218228c80675c8a46ebcef53b57c4ee401d71af035aacbc874e10529cb5bf85e51f1d6583882d32d2a05683dfd776301600000000000027100000000000004e20000000430280019fbab74ace88f976e80c535095eae28f66f2b6357ff7102510694bde94cb8a384359cfdd83daff827213b29bf4a28f644fe462d8f29e402b2b95dd9ad18c450a3cfbf879f515ff96c9031ebcbc006170dd6c30522a35d488c7472c4a75dc1148110ca8b5f4ded642ff67d8879d7a5c00e0b83afaaa8df840c85521de67d448d8e665d7ead5613d0d8e9dd9c23f8e4f59b67eb3758ce14ae3d0a2b38d0a67620b")
    )
  )

  /*Some(new SidechainBlock(
    SidechainSettings.genesisParentBlockId,
    1565162709L, // Wednesday, August 7, 2019 7:25:09 AM
    Seq(),
    getGenesisTransactions,
    secretKey.publicImage(),
    new Signature25519(BytesUtils.fromHexString(
      "28f65fdffb6a0ecffd308445e1ef551935e614a45be9dc936467abcd82297fd5856a3395ae5854e13de9db576a88422da39970a93f0b21ba5b659b3f6cae0100")
    ),
    sidechainTransactionsCompanion
   )
  )*/
}

object SidechainSettings
  extends ScorexLogging
    with SettingsReaders
{

  val genesisParentBlockId : scorex.core.block.Block.BlockId = bytesToId(new Array[Byte](32))

  def read(userConfigPath: Option[String]): SidechainSettings = {
    fromConfig(readConfigFromPath(userConfigPath, "scorex"))
    //new SidechainSettings(ScorexSettings.read(userConfigPath))
  }

  private def fromConfig(config: Config): SidechainSettings = {
    val webSocketClientSettings = config.as[WebSocketClientSettings]("scorex.websocket")
    val scorexSettings = config.as[ScorexSettings]("scorex")
    SidechainSettings(scorexSettings, webSocketClientSettings)
  }
  /*
  implicit val networkSettingsValueReader: ValueReader[SDKSettings] =
    (cfg: Config, path: String) => fromConfig(cfg.getConfig(path))
  */

  /*
  private def fromConfig(config: Config): HybridSettings = {
    log.info(config.toString)
    val walletSettings = config.as[WalletSettings]("scorex.wallet")
    val miningSettings = config.as[HybridMiningSettings]("scorex.miner")
    val scorexSettings = config.as[ScorexSettings]("scorex")
    HybridSettings(miningSettings, walletSettings, scorexSettings)
  }*/
}