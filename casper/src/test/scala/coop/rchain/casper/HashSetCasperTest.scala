package coop.rchain.casper

import java.nio.file.Files

import cats.{Applicative, Monad}
import cats.data.EitherT
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.casper.Estimator.Validator
import coop.rchain.casper.genesis.Genesis
import coop.rchain.casper.genesis.contracts._
import coop.rchain.casper.MultiParentCasper.ignoreDoppelgangerCheck
import coop.rchain.casper.helper.HashSetCasperTestNode.Effect
import coop.rchain.casper.helper.{BlockDagStorageTestFixture, BlockUtil, HashSetCasperTestNode}
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.{BondingUtil, ProtoUtil}
import coop.rchain.casper.util.ProtoUtil.{signBlock, toJustification}
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.casper.util.rholang.InterpreterUtil.mkTerm
import coop.rchain.catscontrib.TaskContrib.TaskOps
import coop.rchain.comm.rp.ProtocolHelper.packet
import coop.rchain.comm.{transport, CommError, TimeOut}
import coop.rchain.crypto.{PrivateKey, PublicKey}
import coop.rchain.crypto.codec.Base16
import coop.rchain.crypto.hash.{Blake2b256, Keccak256}
import coop.rchain.crypto.signatures.{Ed25519, Secp256k1}
import coop.rchain.p2p.EffectsTestInstances.LogicalTime
import coop.rchain.rholang.interpreter.{accounting, Runtime}
import coop.rchain.models.{Expr, Par}
import coop.rchain.shared.StoreType
import coop.rchain.shared.PathOps.RichPath
import coop.rchain.catscontrib._
import coop.rchain.catscontrib.Catscontrib._
import coop.rchain.catscontrib.eitherT._
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{Assertion, FlatSpec, Inspectors, Matchers}
import coop.rchain.casper.scalatestcontrib._
import coop.rchain.casper.util.comm.TestNetwork
import coop.rchain.catscontrib.ski.kp2
import coop.rchain.comm.rp.Connect.Connections
import coop.rchain.metrics
import coop.rchain.metrics.Metrics
import coop.rchain.shared.Log
import org.scalatest

import scala.collection.immutable
import scala.util.Random
import scala.concurrent.duration._

class HashSetCasperTest extends FlatSpec with Matchers with Inspectors {

  import HashSetCasperTest._

  implicit val timeEff = new LogicalTime[Effect]

  private val (otherSk, otherPk)          = Ed25519.newKeyPair
  private val (validatorKeys, validators) = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
  private val (ethPivKeys, ethPubKeys)    = (1 to 4).map(_ => Secp256k1.newKeyPair).unzip
  private val ethAddresses =
    ethPubKeys.map(pk => "0x" + Base16.encode(Keccak256.hash(pk.bytes.drop(1)).takeRight(20)))
  private val wallets     = ethAddresses.map(addr => PreWallet(addr, BigInt(10001)))
  private val bonds       = createBonds(validators)
  private val minimumBond = 100L
  private val genesis =
    buildGenesis(wallets, bonds, minimumBond, Long.MaxValue, Faucet.basicWalletFaucet, 0L)

  //put a new casper instance at the start of each
  //test since we cannot reset it
  "MultiParentCasper" should "not allow multiple threads to process the same block" in {
    val scheduler = Scheduler.fixedPool("three-threads", 3)
    val node      = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)(scheduler)
    val casper    = node.casperEff

    val testProgram = for {
      deploy <- ConstructDeploy.basicDeployData[Effect](0)
      _      <- casper.deploy(deploy)
      block  <- casper.createBlock.map { case Created(block) => block }
      result <- EitherT(
                 Task
                   .racePair(
                     casper.addBlock(block, ignoreDoppelgangerCheck[Effect]).value,
                     casper.addBlock(block, ignoreDoppelgangerCheck[Effect]).value
                   )
                   .flatMap {
                     case Left((statusA, running)) =>
                       running.join.map((statusA, _).tupled)

                     case Right((running, statusB)) =>
                       running.join.map((_, statusB).tupled)
                   }
               )
    } yield result
    val threadStatuses: (BlockStatus, BlockStatus) =
      testProgram.value.unsafeRunSync(scheduler).right.get

    threadStatuses should matchPattern { case (Processing, Valid) | (Valid, Processing) => }
    node.tearDown().value.unsafeRunSync
  }

  it should "create blocks based on deploys" in effectTest {
    val node            = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    implicit val casper = node.casperEff

    for {
      deploy <- ConstructDeploy.basicDeployData[Effect](0)
      _      <- MultiParentCasper[Effect].deploy(deploy)

      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
      deploys           = block.body.get.deploys.flatMap(_.deploy)
      parents           = ProtoUtil.parentHashes(block)
      storage           <- blockTuplespaceContents(block)

      _      = parents.size should be(1)
      _      = parents.head should be(genesis.blockHash)
      _      = deploys.size should be(1)
      _      = deploys.head should be(deploy)
      result = storage.contains("@{0}!(0)") should be(true)
      _      <- node.tearDown()
    } yield result
  }

  it should "accept signed blocks" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy               <- ConstructDeploy.basicDeployData[Effect](0)
      _                    <- MultiParentCasper[Effect].deploy(deploy)
      createBlockResult    <- MultiParentCasper[Effect].createBlock
      Created(signedBlock) = createBlockResult
      _                    <- MultiParentCasper[Effect].addBlock(signedBlock, ignoreDoppelgangerCheck[Effect])
      _                    = logEff.warns.isEmpty should be(true)
      dag                  <- MultiParentCasper[Effect].blockDag
      estimate             <- MultiParentCasper[Effect].estimator(dag)
      _                    = estimate shouldBe IndexedSeq(signedBlock.blockHash)
      _                    <- node.tearDown()
    } yield ()
  }

  it should "be able to use the registry" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node.casperEff

    def now = System.currentTimeMillis()
    val registerDeploy = ConstructDeploy.sourceDeploy(
      """new uriCh, rr(`rho:registry:insertArbitrary`), hello in {
        |  contract hello(@name, return) = { return!("Hello, ${name}!" %% {"name" : name}) } |
        |  rr!(bundle+{*hello}, *uriCh)
        |}
      """.stripMargin,
      1539788365118L, //fix the timestamp so that `uriCh` is known
      accounting.MAX_VALUE
    )

    for {
      _                 <- casperEff.deploy(registerDeploy)
      createBlockResult <- casperEff.createBlock
      Created(block)    = createBlockResult
      blockStatus       <- casperEff.addBlock(block, ignoreDoppelgangerCheck[Effect])
      id <- casperEff
             .storageContents(ProtoUtil.postStateHash(block))
             .map(
               _.split('|')
                 .find(
                   _.contains(
                     //based on the timestamp of registerDeploy, this is uriCh
                     "@{Unforgeable(0xe2615f3fd74c2b83230a3bfc44001d38aded3d2ade5e9b15ffd20e8566d3426f)}!"
                   )
                 )
                 .get
                 .split('`')(1)
             )
      callDeploy = ConstructDeploy.sourceDeploy(
        s"""new rl(`rho:registry:lookup`), helloCh, out in {
           |  rl!(`$id`, *helloCh) |
           |  for(hello <- helloCh){ hello!("World", *out) }
           |}
      """.stripMargin,
        now,
        accounting.MAX_VALUE
      )
      _                  <- casperEff.deploy(callDeploy)
      createBlockResult2 <- casperEff.createBlock
      Created(block2)    = createBlockResult2
      block2Status       <- casperEff.addBlock(block2, ignoreDoppelgangerCheck[Effect])
      _                  = blockStatus shouldBe Valid
      _                  = block2Status shouldBe Valid
      result <- casperEff
                 .storageContents(ProtoUtil.postStateHash(block2))
                 .map(_.contains("Hello, World!")) shouldBeF true

      _ <- node.tearDown()
    } yield result
  }

  it should "be able to create a chain of blocks from different deploys" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._

    val start = System.currentTimeMillis()

    val deployDatas = Vector(
      "contract @\"add\"(@x, @y, ret) = { ret!(x + y) }",
      "new unforgable in { @\"add\"!(5, 7, *unforgable) }"
    ).zipWithIndex.map(s => ConstructDeploy.sourceDeploy(s._1, start + s._2, accounting.MAX_VALUE))

    for {
      createBlockResult1 <- MultiParentCasper[Effect].deploy(deployDatas.head) *> MultiParentCasper[
                             Effect
                           ].createBlock
      Created(signedBlock1) = createBlockResult1
      _                     <- MultiParentCasper[Effect].addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      createBlockResult2 <- MultiParentCasper[Effect].deploy(deployDatas(1)) *> MultiParentCasper[
                             Effect
                           ].createBlock
      Created(signedBlock2) = createBlockResult2
      _                     <- MultiParentCasper[Effect].addBlock(signedBlock2, ignoreDoppelgangerCheck[Effect])
      storage               <- blockTuplespaceContents(signedBlock2)

      _        = logEff.warns should be(Nil)
      _        = ProtoUtil.parentHashes(signedBlock2) should be(Seq(signedBlock1.blockHash))
      dag      <- MultiParentCasper[Effect].blockDag
      estimate <- MultiParentCasper[Effect].estimator(dag)
      _        = estimate shouldBe IndexedSeq(signedBlock2.blockHash)
      result   = storage.contains("!(12)") should be(true)
      _        <- node.tearDown()
    } yield result
  }

  it should "allow multiple deploys in a single block" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._

    val startTime = System.currentTimeMillis()
    val source    = " for(@x <- @0){ @0!(x) } | @0!(0) "
    val deploys = (source #:: source #:: Stream.empty[String]).zipWithIndex
      .map(s => ConstructDeploy.sourceDeploy(s._1, startTime + s._2, accounting.MAX_VALUE))
    for {
      _                 <- deploys.traverse_(MultiParentCasper[Effect].deploy(_))
      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
      _                 <- MultiParentCasper[Effect].addBlock(block, ignoreDoppelgangerCheck[Effect])
      result            <- MultiParentCasper[Effect].contains(block) shouldBeF true
      _                 <- node.tearDown()
    } yield result
  }

  it should "reject unsigned blocks" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      basicDeployData <- ConstructDeploy.basicDeployData[Effect](0)
      createBlockResult <- MultiParentCasper[Effect].deploy(basicDeployData) *> MultiParentCasper[
                            Effect
                          ].createBlock
      Created(block) = createBlockResult
      invalidBlock   = block.withSig(ByteString.EMPTY)
      status         <- MultiParentCasper[Effect].addBlock(invalidBlock, ignoreDoppelgangerCheck[Effect])
      _              = status shouldBe InvalidUnslashableBlock
      _              <- node.casperEff.contains(invalidBlock) shouldBeF false
      _              = logEff.warns.head.contains("Ignoring block") should be(true)
      _              <- node.tearDownNode()
    } yield ()
  }

  it should "not request invalid blocks from peers" in effectTest {

    val List(data0, data1) =
      (0 to 1)
        .map(i => ConstructDeploy.sourceDeploy(s"@$i!($i)", i.toLong, accounting.MAX_VALUE))
        .toList

    for {
      nodes              <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)
      List(node0, node1) = nodes.toList

      unsignedBlock <- (node0.casperEff.deploy(data0) *> node0.casperEff.createBlock)
                        .map {
                          case Created(block) =>
                            block.copy(sigAlgorithm = "invalid", sig = ByteString.EMPTY)
                        }

      _ <- node0.casperEff.addBlock(unsignedBlock, ignoreDoppelgangerCheck[Effect])
      _ <- node1.transportLayerEff.clear(node1.local) //node1 misses this block

      signedBlock <- (node0.casperEff.deploy(data1) *> node0.casperEff.createBlock)
                      .map { case Created(block) => block }

      _ <- node0.casperEff.addBlock(signedBlock, ignoreDoppelgangerCheck[Effect])
      _ <- node1.receive() //receives block1; should not ask for block0

      _ <- node0.casperEff.contains(unsignedBlock) shouldBeF false
      _ <- node1.casperEff.contains(unsignedBlock) shouldBeF false

    } yield ()
  }

  it should "reject blocks not from bonded validators" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, otherSk)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      basicDeployData <- ConstructDeploy.basicDeployData[Effect](0)
      createBlockResult <- MultiParentCasper[Effect].deploy(basicDeployData) *> MultiParentCasper[
                            Effect
                          ].createBlock
      Created(signedBlock) = createBlockResult
      _                    <- MultiParentCasper[Effect].addBlock(signedBlock, ignoreDoppelgangerCheck[Effect])
      _                    = logEff.warns.head.contains("Ignoring block") should be(true)
      _                    <- node.tearDownNode()
    } yield ()
  }

  it should "propose blocks it adds to peers" in effectTest {
    for {
      nodes                <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)
      deployData           <- ConstructDeploy.basicDeployData[Effect](0)
      createBlockResult    <- nodes(0).casperEff.deploy(deployData) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      _                    <- nodes(0).casperEff.addBlock(signedBlock, ignoreDoppelgangerCheck[Effect])
      _                    <- nodes(1).receive()
      result               <- nodes(1).casperEff.contains(signedBlock) shouldBeF true
      _                    <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              blockStore.get(signedBlock.blockHash) shouldBeF Some(signedBlock)
            }(nodes(0).metricEff, nodes(0).logEff)
          }
    } yield result
  }

  it should "add a valid block from peer" in effectTest {
    for {
      nodes                      <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)
      deployData                 <- ConstructDeploy.basicDeployData[Effect](1)
      createBlockResult          <- nodes(0).casperEff.deploy(deployData) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult
      _                          <- nodes(0).casperEff.addBlock(signedBlock1Prime, ignoreDoppelgangerCheck[Effect])
      _                          <- nodes(1).receive()
      _                          = nodes(1).logEff.infos.count(_ startsWith "Added") should be(1)
      result                     = nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(0)
      _                          <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              blockStore.get(signedBlock1Prime.blockHash) shouldBeF Some(signedBlock1Prime)
            }(nodes(0).metricEff, nodes(0).logEff)
          }
    } yield result
  }

  it should "have a working faucet (in testnet)" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node.casperEff

    //val skStr = "6061f3ea36d0419d1e9e23c33bba88ed1435427fa2a8f7300ff210b4e9f18a14"
    val pkStr = "16989775f3f207a717134216816d3c9d97b0bfb8d560b29485f23f6ead435f09"
    val sigStr = "51c2b091559745d51c7270189911d9d894d538f76150ed67d164705dcf0af52" +
      "e101fa06396db2b2ac21a4bfbe3461567b5f8b3d2e666c377cb92d96bc38e2c08"
    val amount = 157L
    val createWalletCode =
      s"""new
         |  walletCh, rl(`rho:registry:lookup`), SystemInstancesCh, faucetCh,
         |  rs(`rho:registry:insertSigned:ed25519`), uriOut
         |in {
         |  rl!(`rho:id:wdwc36f4ixa6xacck3ddepmgueum7zueuczgthcqp6771kdu8jogm8`, *SystemInstancesCh) |
         |  for(@(_, SystemInstancesRegistry) <- SystemInstancesCh) {
         |    @SystemInstancesRegistry!("lookup", "faucet", *faucetCh) |
         |    for(faucet <- faucetCh){ faucet!($amount, "ed25519", "$pkStr", *walletCh) } |
         |    for(@[wallet] <- walletCh){ walletCh!!(wallet) }
         |  } |
         |  rs!(
         |    "$pkStr".hexToBytes(),
         |    (9223372036854775807, bundle-{*walletCh}),
         |    "$sigStr".hexToBytes(),
         |    *uriOut
         |  )
         |}""".stripMargin

    //with the fixed user+timestamp we know that walletCh is registered at `rho:id:mrs88izurkgki71dpjqamzg6tgcjd6sk476c9msks7tumw4a6e39or`
    val createWalletDeploy = ConstructDeploy.sourceDeploy(
      source = createWalletCode,
      timestamp = 1540570144121L,
      phlos = accounting.MAX_VALUE,
      sec = PrivateKey(
        Base16.unsafeDecode("6061f3ea36d0419d1e9e23c33bba88ed1435427fa2a8f7300ff210b4e9f18a14")
      )
    )

    for {
      createBlockResult <- casperEff.deploy(createWalletDeploy) *> casperEff.createBlock
      Created(block)    = createBlockResult
      blockStatus       <- casperEff.addBlock(block, ignoreDoppelgangerCheck[Effect])
      balanceQuery = ConstructDeploy.sourceDeploy(
        s"""new
           |  rl(`rho:registry:lookup`), walletFeedCh
           |in {
           |  rl!(`rho:id:mrs88izurkgki71dpjqamzg6tgcjd6sk476c9msks7tumw4a6e39or`, *walletFeedCh) |
           |  for(@(_, walletFeed) <- walletFeedCh) {
           |    for(wallet <- @walletFeed) { wallet!("getBalance", "__SCALA__") }
           |  }
           |}""".stripMargin,
        0L,
        accounting.MAX_VALUE,
        sec = PrivateKey(
          Base16.unsafeDecode("6061f3ea36d0419d1e9e23c33bba88ed1435427fa2a8f7300ff210b4e9f18a14")
        )
      )
      newWalletBalance <- node.runtimeManager
                           .captureResults(
                             ProtoUtil.postStateHash(block),
                             balanceQuery
                           )
      _      = blockStatus shouldBe Valid
      result = newWalletBalance.head.exprs.head.getGInt shouldBe amount

      _ <- node.tearDown()
    } yield result
  }

  it should "estimate parent properly" in effectTest {
    val (otherSk, otherPk)          = Ed25519.newKeyPair
    val (validatorKeys, validators) = (1 to 5).map(_ => Ed25519.newKeyPair).unzip
    val (ethPivKeys, ethPubKeys)    = (1 to 5).map(_ => Secp256k1.newKeyPair).unzip
    val ethAddresses =
      ethPubKeys.map(pk => "0x" + Base16.encode(Keccak256.hash(pk.bytes.drop(1)).takeRight(20)))
    val wallets = ethAddresses.map(addr => PreWallet(addr, BigInt(10001)))
    val bonds = Map(
      validators(0) -> 3L,
      validators(1) -> 1L,
      validators(2) -> 5L,
      validators(3) -> 2L,
      validators(4) -> 4L
    )
    val minimumBond = 100L
    val genesis =
      buildGenesis(wallets, bonds, minimumBond, Long.MaxValue, Faucet.basicWalletFaucet, 0L)

    def deployment(i: Int, ts: Long): DeployData =
      ConstructDeploy.sourceDeploy(s"new x in { x!(0) }", ts, accounting.MAX_VALUE)

    def deploy(
        node: HashSetCasperTestNode[Effect],
        dd: DeployData
    ) = node.casperEff.deploy(dd)

    def create(
        node: HashSetCasperTestNode[Effect]
    ) =
      for {
        createBlockResult1    <- node.casperEff.createBlock
        Created(signedBlock1) = createBlockResult1
      } yield signedBlock1

    def add(node: HashSetCasperTestNode[Effect], signed: BlockMessage) =
      Sync[Effect].attempt(
        node.casperEff.addBlock(signed, ignoreDoppelgangerCheck[Effect])
      )

    val network = TestNetwork.empty[Effect]

    for {
      nodes <- HashSetCasperTestNode
                .networkEff(validatorKeys.take(3), genesis, testNetwork = network)
                .map(_.toList)
      v1   = nodes(0)
      v2   = nodes(1)
      v3   = nodes(2)
      _    <- deploy(v1, deployment(0, 1)) >> create(v1) >>= (v1c1 => add(v1, v1c1)) //V1#1
      v2c1 <- deploy(v2, deployment(0, 2)) >> create(v2) //V2#1
      _    <- v2.receive()
      _    <- v3.receive()
      _    <- deploy(v1, deployment(0, 4)) >> create(v1) >>= (v1c2 => add(v1, v1c2)) //V1#2
      v3c2 <- deploy(v3, deployment(0, 5)) >> create(v3) //V3#2
      _    <- v3.receive()
      _    <- add(v3, v3c2) //V3#2
      _    <- add(v2, v2c1) //V2#1
      _    <- v3.receive()
      r    <- deploy(v3, deployment(0, 6)) >> create(v3) >>= (b => add(v3, b))
      _    = r shouldBe Right(Valid)
      _    = v3.logEff.warns shouldBe empty

      _ <- nodes.map(_.tearDown()).sequence
    } yield ()
  }

  it should "allow paying for deploys" in effectTest {
    val node      = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    val (sk, pk)  = Ed25519.newKeyPair
    val user      = ByteString.copyFrom(pk)
    val timestamp = System.currentTimeMillis()
    val phloPrice = 1L
    val amount    = 847L
    val sigDeployData = ConstructDeploy
      .sourceDeploy(
        s"""new retCh in { @"blake2b256Hash"!([0, $amount, *retCh].toByteArray(), "__SCALA__") }""",
        timestamp,
        accounting.MAX_VALUE,
        sec = PrivateKey(sk)
      )

    for {
      capturedResults <- node.runtimeManager
                          .captureResults(
                            ProtoUtil.postStateHash(genesis),
                            sigDeployData
                          )
      sigData     = capturedResults.head.exprs.head.getGByteArray
      sig         = Base16.encode(Ed25519.sign(sigData.toByteArray, sk))
      pkStr       = Base16.encode(pk)
      paymentCode = s"""new
         |  paymentForward, walletCh, rl(`rho:registry:lookup`),
         |  SystemInstancesCh, faucetCh, posCh
         |in {
         |  rl!(`rho:id:wdwc36f4ixa6xacck3ddepmgueum7zueuczgthcqp6771kdu8jogm8`, *SystemInstancesCh) |
         |  for(@(_, SystemInstancesRegistry) <- SystemInstancesCh) {
         |    @SystemInstancesRegistry!("lookup", "pos", *posCh) |
         |    @SystemInstancesRegistry!("lookup", "faucet", *faucetCh) |
         |    for(faucet <- faucetCh; pos <- posCh){
         |      faucet!($amount, "ed25519", "$pkStr", *walletCh) |
         |      for(@[wallet] <- walletCh) {
         |        @wallet!("transfer", $amount, 0, "$sig", *paymentForward, Nil) |
         |        for(@purse <- paymentForward){ pos!("pay", purse, Nil) }
         |      }
         |    }
         |  }
         |}""".stripMargin
      paymentDeployData = ConstructDeploy
        .sourceDeploy(
          paymentCode,
          timestamp,
          accounting.MAX_VALUE,
          phloPrice = phloPrice,
          sec = PrivateKey(sk)
        )

      paymentQuery = ConstructDeploy
        .sourceDeploy(
          """new rl(`rho:registry:lookup`), SystemInstancesCh, posCh in {
        |  rl!(`rho:id:wdwc36f4ixa6xacck3ddepmgueum7zueuczgthcqp6771kdu8jogm8`, *SystemInstancesCh) |
        |  for(@(_, SystemInstancesRegistry) <- SystemInstancesCh) {
        |    @SystemInstancesRegistry!("lookup", "pos", *posCh) |
        |    for(pos <- posCh){ pos!("lastPayment", "__SCALA__") }
        |  }
        |}""".stripMargin,
          0L,
          accounting.MAX_VALUE,
          sec = PrivateKey(sk)
        )

      deployQueryResult <- deployAndQuery(
                            node,
                            paymentDeployData,
                            paymentQuery
                          )
      (blockStatus, queryResult) = deployQueryResult
      (codeHashPar, _, userIdPar, timestampPar) = ProtoUtil.getRholangDeployParams(
        paymentDeployData
      )
      phloPurchasedPar = Par(exprs = Seq(Expr(Expr.ExprInstance.GInt(phloPrice * amount))))

      _ <- node.tearDown()
    } yield {
      blockStatus shouldBe Valid

      queryResult.head.exprs.head.getETupleBody.ps match {
        case Seq(
            actualCodeHashPar,
            actualUserIdPar,
            actualTimestampPar,
            actualPhloPurchasedPar
            ) =>
          actualCodeHashPar should be(codeHashPar)
          actualUserIdPar should be(userIdPar)
          actualTimestampPar should be(timestampPar)
          actualPhloPurchasedPar should be(phloPurchasedPar)
      }
    }
  }

  it should "reject addBlock when there exist deploy by the same (user, millisecond timestamp) in the chain" in effectTest {
    for {
      nodes <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)
      deployDatas <- (0 to 2).toList
                      .traverse[Effect, DeployData](i => ConstructDeploy.basicDeployData[Effect](i))
      deployPrim0 = ConstructDeploy.sign(
        deployDatas(1)
          .withTimestamp(deployDatas(0).timestamp)
          .withDeployer(deployDatas(0).deployer)
      ) // deployPrim0 has the same (user, millisecond timestamp) with deployDatas(0)
      createBlockResult1 <- nodes(0).casperEff
                             .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      _                     <- nodes(0).casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      _                     <- nodes(1).receive() // receive block1

      createBlockResult2 <- nodes(0).casperEff
                             .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
      Created(signedBlock2) = createBlockResult2
      _                     <- nodes(0).casperEff.addBlock(signedBlock2, ignoreDoppelgangerCheck[Effect])
      _                     <- nodes(1).receive() // receive block2

      createBlockResult3 <- nodes(0).casperEff
                             .deploy(deployDatas(2)) *> nodes(0).casperEff.createBlock
      Created(signedBlock3) = createBlockResult3
      _                     <- nodes(0).casperEff.addBlock(signedBlock3, ignoreDoppelgangerCheck[Effect])
      _                     <- nodes(1).receive() // receive block3

      _ <- nodes(1).casperEff.contains(signedBlock3) shouldBeF true

      createBlockResult4 <- nodes(1).casperEff
                             .deploy(deployPrim0) *> nodes(1).casperEff.createBlock
      Created(signedBlock4) = createBlockResult4
      _ <- nodes(1).casperEff
            .addBlock(signedBlock4, ignoreDoppelgangerCheck[Effect]) // should succeed
      _ <- nodes(0).receive() // still receive signedBlock4

      result <- nodes(1).casperEff
                 .contains(signedBlock4) shouldBeF true // Invalid blocks are still added
      // TODO: Fix with https://rchain.atlassian.net/browse/RHOL-1048
      // nodes(0).casperEff.contains(signedBlock4) should be(false)
      //
      // nodes(0).logEff.warns
      //   .count(_ contains "found deploy by the same (user, millisecond timestamp) produced") should be(
      //   1
      // )
      _ <- nodes.map(_.tearDownNode()).toList.sequence

      _ = nodes.toList.traverse_[Effect, Assertion] { node =>
        validateBlockStore(node) { blockStore =>
          for {
            _      <- blockStore.get(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
            _      <- blockStore.get(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
            result <- blockStore.get(signedBlock3.blockHash) shouldBeF Some(signedBlock3)
          } yield result
        }(nodes(0).metricEff, nodes(0).logEff)
      }
    } yield result
  }

  it should "ask peers for blocks it is missing" in effectTest {
    for {
      nodes <- HashSetCasperTestNode.networkEff(validatorKeys.take(3), genesis)
      deployDatas = Vector(
        "for(_ <- @1){ Nil } | @1!(1)",
        "@2!(2)"
      ).zipWithIndex
        .map(
          d =>
            ConstructDeploy
              .sourceDeploy(d._1, System.currentTimeMillis() + d._2, accounting.MAX_VALUE)
        )

      createBlockResult1 <- nodes(0).casperEff
                             .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1

      _ <- nodes(0).casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).receive()
      _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block

      createBlockResult2 <- nodes(0).casperEff
                             .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
      Created(signedBlock2) = createBlockResult2

      _ <- nodes(0).casperEff.addBlock(signedBlock2, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).receive() //receives block2
      _ <- nodes(2).receive() //receives block2; asks for block1
      _ <- nodes(1).receive() //receives request for block1; sends block1
      _ <- nodes(2).receive() //receives block1; adds both block1 and block2

      _ <- nodes(2).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(2).casperEff.contains(signedBlock2) shouldBeF true

      _ = nodes(2).logEff.infos
        .count(_ startsWith "Requested missing block") should be(1)
      result = nodes(1).logEff.infos.count(
        s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
      ) should be(1)

      _ <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              for {
                _      <- blockStore.get(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
                result <- blockStore.get(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
              } yield result
            }(nodes(0).metricEff, nodes(0).logEff)
          }
    } yield result
  }

  /*
   *  DAG Looks like this:
   *
   *             h1
   *            /  \
   *           g1   g2
   *           |  X |
   *           f1   f2
   *            \  /
   *             e1
   *             |
   *             d1
   *            /  \
   *           c1   c2
   *           |  X |
   *           b1   b2
   *           |  X |
   *           a1   a2
   *            \  /
   *          genesis
   *
   * f2 has in its justifications list c2. This should be handled properly.
   *
   */
  it should "ask peers for blocks it is missing and add them" in effectTest {
    val deployDatasFs = Vector(
      "@2!(2)",
      "@1!(1)"
    ).zipWithIndex
      .map(
        d =>
          () =>
            ConstructDeploy
              .sourceDeploy(d._1, System.currentTimeMillis() + d._2, accounting.MAX_VALUE)
      )
    def deploy(node: HashSetCasperTestNode[Effect], dd: DeployData): Effect[BlockMessage] =
      for {
        createBlockResult1    <- node.casperEff.deploy(dd) *> node.casperEff.createBlock
        Created(signedBlock1) = createBlockResult1

        _ <- node.casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      } yield signedBlock1

    def stepSplit(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- deploy(nodes(0), deployDatasFs(0).apply())
        _ <- deploy(nodes(1), deployDatasFs(1).apply())

        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block
      } yield ()

    def stepSingle(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- deploy(nodes(0), deployDatasFs(0).apply())

        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block
      } yield ()

    def propagate(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).receive()
      } yield ()

    for {
      nodes <- HashSetCasperTestNode.networkEff(validatorKeys.take(3), genesis)

      _ <- stepSplit(nodes) // blocks a1 a2
      _ <- stepSplit(nodes) // blocks b1 b2
      _ <- stepSplit(nodes) // blocks c1 c2

      _ <- stepSingle(nodes) // block d1
      _ <- stepSingle(nodes) // block e1

      _ <- stepSplit(nodes) // blocks f1 f2
      _ <- stepSplit(nodes) // blocks g1 g2

      // this block will be propagated to all nodes and force nodes(2) to ask for missing blocks.
      br <- deploy(nodes(0), deployDatasFs(0).apply()) // block h1

      _ <- List.fill(22)(propagate(nodes)).toList.sequence // force the network to communicate

      _ <- nodes(2).casperEff.contains(br) shouldBeF true

      nr <- deploy(nodes(2), deployDatasFs(0).apply())
      _  = nr.header.get.parentsHashList shouldBe Seq(br.blockHash)
      _  <- nodes.map(_.tearDownNode()).toList.sequence
    } yield ()
  }

  it should "ignore adding equivocation blocks" in effectTest {
    for {
      nodes <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)

      // Creates a pair that constitutes equivocation blocks
      basicDeployData0 <- ConstructDeploy.basicDeployData[Effect](0)
      createBlockResult1 <- nodes(0).casperEff
                             .deploy(basicDeployData0) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      basicDeployData1      <- ConstructDeploy.basicDeployData[Effect](1)
      createBlockResult1Prime <- nodes(0).casperEff
                                  .deploy(basicDeployData1) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult1Prime

      _ <- nodes(0).casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).receive()
      _ <- nodes(0).casperEff.addBlock(signedBlock1Prime, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).receive()

      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      result <- nodes(1).casperEff
                 .contains(signedBlock1Prime) shouldBeF false // we still add the equivocation pair

      _ <- nodes(0).tearDownNode()
      _ <- nodes(1).tearDownNode()
      _ <- validateBlockStore(nodes(1)) { blockStore =>
            for {
              _      <- blockStore.get(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
              result <- blockStore.get(signedBlock1Prime.blockHash) shouldBeF None
            } yield result
          }(nodes(0).metricEff, nodes(0).logEff)
    } yield result
  }

  // See [[/docs/casper/images/minimal_equivocation_neglect.png]] but cross out genesis block
  it should "not ignore equivocation blocks that are required for parents of proper nodes" in effectTest {
    for {
      nodes <- HashSetCasperTestNode.networkEff(validatorKeys.take(3), genesis)
      deployDatas <- (0 to 5).toList
                      .traverse[Effect, DeployData](i => ConstructDeploy.basicDeployData[Effect](i))

      // Creates a pair that constitutes equivocation blocks
      createBlockResult1 <- nodes(0).casperEff
                             .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      createBlockResult1Prime <- nodes(0).casperEff
                                  .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult1Prime

      _ <- nodes(1).casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(0).transportLayerEff.clear(nodes(0).local) //nodes(0) misses this block
      _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block

      _ <- nodes(0).casperEff.addBlock(signedBlock1Prime, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(2).receive()
      _ <- nodes(1).transportLayerEff.clear(nodes(1).local) //nodes(1) misses this block

      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(2).casperEff.contains(signedBlock1) shouldBeF false

      _ <- nodes(1).casperEff.contains(signedBlock1Prime) shouldBeF false
      _ <- nodes(2).casperEff.contains(signedBlock1Prime) shouldBeF true

      createBlockResult2 <- nodes(1).casperEff
                             .deploy(deployDatas(2)) *> nodes(1).casperEff.createBlock
      Created(signedBlock2) = createBlockResult2
      createBlockResult3 <- nodes(2).casperEff
                             .deploy(deployDatas(3)) *> nodes(2).casperEff.createBlock
      Created(signedBlock3) = createBlockResult3

      _ <- nodes(2).casperEff.addBlock(signedBlock3, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).casperEff.addBlock(signedBlock2, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) ignores block2
      _ <- nodes(1).receive() // receives block3; asks for block1'
      _ <- nodes(2).receive() // receives request for block1'; sends block1'
      _ <- nodes(1).receive() // receives block1'; adds both block3 and block1'

      _ <- nodes(1).casperEff.contains(signedBlock3) shouldBeF true
      _ <- nodes(1).casperEff.contains(signedBlock1Prime) shouldBeF true

      createBlockResult4 <- nodes(1).casperEff
                             .deploy(deployDatas(4)) *> nodes(1).casperEff.createBlock
      Created(signedBlock4) = createBlockResult4
      _                     <- nodes(1).casperEff.addBlock(signedBlock4, ignoreDoppelgangerCheck[Effect])

      // Node 1 should contain both blocks constituting the equivocation
      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(1).casperEff.contains(signedBlock1Prime) shouldBeF true

      _ <- nodes(1).casperEff
            .contains(signedBlock4) shouldBeF true // However, marked as invalid

      _ = nodes(1).logEff.infos.count(_ startsWith "Added admissible equivocation") should be(1)
      _ = nodes(2).logEff.warns.size should be(0)
      _ = nodes(1).logEff.warns.size should be(1)
      _ = nodes(0).logEff.warns.size should be(0)

      _ <- nodes(1).casperEff
            .normalizedInitialFault(ProtoUtil.weightMap(genesis)) shouldBeF 1f / (1f + 3f + 5f + 7f)
      _ <- nodes.map(_.tearDownNode()).toList.sequence

      _ <- validateBlockStore(nodes(0)) { blockStore =>
            for {
              _ <- blockStore.get(signedBlock1.blockHash) shouldBeF None
              result <- blockStore.get(signedBlock1Prime.blockHash) shouldBeF Some(
                         signedBlock1Prime
                       )
            } yield result
          }(nodes(0).metricEff, nodes(0).logEff)
      _ <- validateBlockStore(nodes(1)) { blockStore =>
            for {
              _      <- blockStore.get(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
              result <- blockStore.get(signedBlock4.blockHash) shouldBeF Some(signedBlock4)
            } yield result
          }(nodes(1).metricEff, nodes(1).logEff)
      result <- validateBlockStore(nodes(2)) { blockStore =>
                 for {
                   _ <- blockStore.get(signedBlock3.blockHash) shouldBeF Some(signedBlock3)
                   result <- blockStore.get(signedBlock1Prime.blockHash) shouldBeF Some(
                              signedBlock1Prime
                            )
                 } yield result
               }(nodes(2).metricEff, nodes(2).logEff)
    } yield result
  }

  it should "prepare to slash an block that includes a invalid block pointer" in effectTest {
    for {
      nodes           <- HashSetCasperTestNode.networkEff(validatorKeys.take(3), genesis)
      deploys         <- (0 to 5).toList.traverse(i => ConstructDeploy.basicDeployData[Effect](i))
      deploysWithCost = deploys.map(d => ProcessedDeploy(deploy = Some(d))).toIndexedSeq

      createBlockResult <- nodes(0).casperEff
                            .deploy(deploys(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      signedInvalidBlock = BlockUtil.resignBlock(
        signedBlock.withSeqNum(-2),
        nodes(0).validatorId.privateKey
      ) // Invalid seq num

      blockWithInvalidJustification <- buildBlockWithInvalidJustification(
                                        nodes,
                                        deploysWithCost,
                                        signedInvalidBlock
                                      )

      _ <- nodes(1).casperEff
            .addBlock(blockWithInvalidJustification, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(0).transportLayerEff
            .clear(nodes(0).local) // nodes(0) rejects normal adding process for blockThatPointsToInvalidBlock

      signedInvalidBlockPacketMessage = packet(
        nodes(0).local,
        transport.BlockMessage,
        signedInvalidBlock.toByteString
      )
      _ <- nodes(0).transportLayerEff.send(nodes(1).local, signedInvalidBlockPacketMessage)
      _ <- nodes(1).receive() // receives signedInvalidBlock; attempts to add both blocks

      result = nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(1)
      _      <- nodes.map(_.tearDown()).toList.sequence
    } yield result
  }

  it should "handle a long chain of block requests appropriately" in effectTest {
    for {
      nodes <- HashSetCasperTestNode.networkEff(
                validatorKeys.take(2),
                genesis,
                storageSize = 1024L * 1024 * 10
              )

      _ <- (0 to 9).toList.traverse_[Effect, Unit] { i =>
            for {
              deploy <- ConstructDeploy.basicDeployData[Effect](i)
              createBlockResult <- nodes(0).casperEff
                                    .deploy(deploy) *> nodes(0).casperEff.createBlock
              Created(block) = createBlockResult

              _ <- nodes(0).casperEff.addBlock(block, ignoreDoppelgangerCheck[Effect])
              _ <- nodes(1).transportLayerEff.clear(nodes(1).local) //nodes(1) misses this block
            } yield ()
          }
      deployData10 <- ConstructDeploy.basicDeployData[Effect](10)
      createBlock11Result <- nodes(0).casperEff.deploy(deployData10) *> nodes(
                              0
                            ).casperEff.createBlock
      Created(block11) = createBlock11Result
      _                <- nodes(0).casperEff.addBlock(block11, ignoreDoppelgangerCheck[Effect])

      // Cycle of requesting and passing blocks until block #3 from nodes(0) to nodes(1)
      _ <- (0 to 8).toList.traverse_[Effect, Unit] { i =>
            nodes(1).receive() *> nodes(0).receive()
          }

      // We simulate a network failure here by not allowing block #2 to get passed to nodes(1)

      // And then we assume fetchDependencies eventually gets called
      _ <- nodes(1).casperEff.fetchDependencies
      _ <- nodes(0).receive()

      _ = nodes(1).logEff.infos.count(_ startsWith "Requested missing block") should be(10)
      result = nodes(0).logEff.infos.count(
        s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
      ) should be(10)

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield result
  }

  it should "increment last finalized block as appropriate in round robin" in effectTest {
    val stake      = 10L
    val equalBonds = validators.map(_ -> stake).toMap
    val genesisWithEqualBonds =
      buildGenesis(Seq.empty, equalBonds, 1L, Long.MaxValue, Faucet.noopFaucet, 0L)
    for {
      nodes       <- HashSetCasperTestNode.networkEff(validatorKeys.take(3), genesisWithEqualBonds)
      deployDatas <- (0 to 7).toList.traverse(i => ConstructDeploy.basicDeployData[Effect](i))

      createBlock1Result <- nodes(0).casperEff
                             .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(block1) = createBlock1Result
      _               <- nodes(0).casperEff.addBlock(block1, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(1).receive()
      _               <- nodes(2).receive()

      createBlock2Result <- nodes(1).casperEff
                             .deploy(deployDatas(1)) *> nodes(1).casperEff.createBlock
      Created(block2) = createBlock2Result
      _               <- nodes(1).casperEff.addBlock(block2, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(2).receive()

      createBlock3Result <- nodes(2).casperEff
                             .deploy(deployDatas(2)) *> nodes(2).casperEff.createBlock
      Created(block3) = createBlock3Result
      _               <- nodes(2).casperEff.addBlock(block3, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(1).receive()

      createBlock4Result <- nodes(0).casperEff
                             .deploy(deployDatas(3)) *> nodes(0).casperEff.createBlock
      Created(block4) = createBlock4Result
      _               <- nodes(0).casperEff.addBlock(block4, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(1).receive()
      _               <- nodes(2).receive()

      createBlock5Result <- nodes(1).casperEff
                             .deploy(deployDatas(4)) *> nodes(1).casperEff.createBlock
      Created(block5) = createBlock5Result
      _               <- nodes(1).casperEff.addBlock(block5, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(2).receive()

      _     <- nodes(0).casperEff.lastFinalizedBlock shouldBeF block1
      state <- nodes(0).casperState.read
      _     = state.deployHistory.size should be(1)

      createBlock6Result <- nodes(2).casperEff
                             .deploy(deployDatas(5)) *> nodes(2).casperEff.createBlock
      Created(block6) = createBlock6Result
      _               <- nodes(2).casperEff.addBlock(block6, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(1).receive()

      _     <- nodes(0).casperEff.lastFinalizedBlock shouldBeF block2
      state <- nodes(0).casperState.read
      _     = state.deployHistory.size should be(1)

      createBlock7Result <- nodes(0).casperEff
                             .deploy(deployDatas(6)) *> nodes(0).casperEff.createBlock
      Created(block7) = createBlock7Result
      _               <- nodes(0).casperEff.addBlock(block7, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(1).receive()
      _               <- nodes(2).receive()

      _ <- nodes(0).casperEff.lastFinalizedBlock shouldBeF block3
      _ = state.deployHistory.size should be(1)

      createBlock8Result <- nodes(1).casperEff
                             .deploy(deployDatas(7)) *> nodes(1).casperEff.createBlock
      Created(block8) = createBlock8Result
      _               <- nodes(1).casperEff.addBlock(block8, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(2).receive()

      _     <- nodes(0).casperEff.lastFinalizedBlock shouldBeF block4
      state <- nodes(0).casperState.read
      _     = state.deployHistory.size should be(1)

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "fail when deploying with insufficient phlos" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deployData        <- ConstructDeploy.basicDeployData[Effect](0, phlos = 1)
      _                 <- node.casperEff.deploy(deployData)
      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
    } yield assert(block.body.get.deploys.head.errored)
  }

  it should "succeed if given enough phlos for deploy" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deployData <- ConstructDeploy.basicDeployData[Effect](0, phlos = 100)
      _          <- node.casperEff.deploy(deployData)

      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
    } yield assert(!block.body.get.deploys.head.errored)
  }

  private def buildBlockWithInvalidJustification(
      nodes: IndexedSeq[HashSetCasperTestNode[Effect]],
      deploys: immutable.IndexedSeq[ProcessedDeploy],
      signedInvalidBlock: BlockMessage
  ): Effect[BlockMessage] = {
    val postState     = RChainState().withBonds(ProtoUtil.bonds(genesis)).withBlockNumber(1)
    val postStateHash = Blake2b256.hash(postState.toByteArray)
    val header = Header()
      .withPostStateHash(ByteString.copyFrom(postStateHash))
      .withParentsHashList(signedInvalidBlock.header.get.parentsHashList)
      .withDeploysHash(ProtoUtil.protoSeqHash(deploys))
    val blockHash = Blake2b256.hash(header.toByteArray)
    val body      = Body().withState(postState).withDeploys(deploys)
    val serializedJustifications =
      Seq(Justification(signedInvalidBlock.sender, signedInvalidBlock.blockHash))
    val serializedBlockHash = ByteString.copyFrom(blockHash)
    val blockThatPointsToInvalidBlock =
      BlockMessage(serializedBlockHash, Some(header), Some(body), serializedJustifications)
    nodes(1).casperEff.blockDag.flatMap { dag =>
      ProtoUtil.signBlock[Effect](
        blockThatPointsToInvalidBlock,
        dag,
        validators(1),
        validatorKeys(1),
        "ed25519",
        "rchain"
      )
    }
  }
}

object HashSetCasperTest {
  def validateBlockStore[R](
      node: HashSetCasperTestNode[Effect]
  )(f: BlockStore[Effect] => Effect[R])(implicit metrics: Metrics[Effect], log: Log[Effect]) =
    for {
      bs     <- BlockDagStorageTestFixture.createBlockStorage[Effect](node.blockStoreDir)
      result <- f(bs)
      _      <- bs.close()
      _      <- Sync[Effect].delay { node.blockStoreDir.recursivelyDelete() }
    } yield result

  def blockTuplespaceContents(
      block: BlockMessage
  )(implicit casper: MultiParentCasper[Effect]): Effect[String] = {
    val tsHash = ProtoUtil.postStateHash(block)
    MultiParentCasper[Effect].storageContents(tsHash)
  }

  def deployAndQuery(
      node: HashSetCasperTestNode[Effect],
      dd: DeployData,
      query: DeployData
  ): Effect[(BlockStatus, Seq[Par])] =
    for {
      createBlockResult <- node.casperEff.deploy(dd) >> node.casperEff.createBlock
      Created(block)    = createBlockResult
      blockStatus       <- node.casperEff.addBlock(block, ignoreDoppelgangerCheck[Effect])
      queryResult <- node.runtimeManager
                      .captureResults(ProtoUtil.postStateHash(block), query)
    } yield (blockStatus, queryResult)

  def createBonds(validators: Seq[Array[Byte]]): Map[Array[Byte], Long] =
    validators.zipWithIndex.map { case (v, i) => v -> (2L * i.toLong + 1L) }.toMap

  def createGenesis(bonds: Map[Array[Byte], Long]): BlockMessage =
    buildGenesis(Seq.empty, bonds, 1L, Long.MaxValue, Faucet.noopFaucet, 0L)

  def buildGenesis(
      wallets: Seq[PreWallet],
      bonds: Map[Array[Byte], Long],
      minimumBond: Long,
      maximumBond: Long,
      faucetCode: String => String,
      deployTimestamp: Long
  ): BlockMessage = {
    val initial                            = Genesis.withoutContracts(bonds, 1L, deployTimestamp, "rchain")
    val storageDirectory                   = Files.createTempDirectory(s"hash-set-casper-test-genesis")
    val storageSize: Long                  = 1024L * 1024
    implicit val log                       = new Log.NOPLog[Task]
    implicit val metricsEff: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]

    val activeRuntime =
      Runtime
        .createWithEmptyCost[Task, Task.Par](storageDirectory, storageSize, StoreType.LMDB)
        .unsafeRunSync
    val runtimeManager = RuntimeManager.fromRuntime[Task](activeRuntime).unsafeRunSync
    val emptyStateHash = runtimeManager.emptyStateHash
    val validators     = bonds.map(bond => ProofOfStakeValidator(bond._1, bond._2)).toSeq
    val genesis = Genesis
      .withContracts(
        initial,
        ProofOfStakeParams(minimumBond, maximumBond, validators),
        wallets,
        faucetCode,
        emptyStateHash,
        runtimeManager,
        deployTimestamp
      )
      .unsafeRunSync
    activeRuntime.close().unsafeRunSync
    genesis
  }
}
