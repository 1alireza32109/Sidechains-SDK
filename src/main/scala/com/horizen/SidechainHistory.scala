package com.horizen

import com.horizen.block.{ProofOfWorkVerifier, SidechainBlock}
import com.horizen.params.NetworkParams
import com.horizen.storage.SidechainHistoryStorage
import com.horizen.utils.BytesUtils
import scorex.core.NodeViewModifier
import scorex.util.ModifierId
import scorex.core.consensus.History._
import scorex.core.consensus.{History, ModifierSemanticValidity}
import scorex.core.validation.RecoverableModifierError
import scorex.util.idToBytes

import scala.util.{Failure, Success, Try}


class SidechainHistory(val storage: SidechainHistoryStorage, params: NetworkParams)
  extends scorex.core.consensus.History[
      SidechainBlock,
      SidechainSyncInfo,
      SidechainHistory] with scorex.core.utils.ScorexEncoding {

  override type NVCT = SidechainHistory

  require(NodeViewModifier.ModifierIdSize == 32, "32 bytes ids assumed")

  val height: Int = storage.height
  val bestBlockId: ModifierId = storage.bestBlockId
  val bestBlock: SidechainBlock = storage.bestBlock // note: maybe make it lazy?


  // Note: if block already exists in History it will be declined inside NodeViewHolder before appending.
  override def append(block: SidechainBlock): Try[(SidechainHistory, ProgressInfo[SidechainBlock])] = Try {
    // TO DO: validate against Praos consensus rules.
    if(!block.semanticValidity(params))
      throw new IllegalArgumentException("Semantic validation failed for block %s".format(BytesUtils.toHexString(idToBytes(block.id))))
    if(!ProofOfWorkVerifier.checkNextWorkRequired(block, this, params))
      throw new IllegalArgumentException("Containing MC Blocks PoW difficulty is invalid for block %s".format(BytesUtils.toHexString(idToBytes(block.id))))

    val (newStorage: Try[SidechainHistoryStorage], progressInfo: ProgressInfo[SidechainBlock]) = {
      if(isGenesisBlock(block.id)) {
        (
          storage.update(block, (1L << 32) + 1), // 1 MC block ref and 1 SC Block
          ProgressInfo(None, Seq(), Seq(block), Seq())
        )
      }
      else {
        storage.blockInfoById(block.parentId) match {
          case Some(parentBlockInfo) =>
            val chainScore: Long = calculateChainScore(block, parentBlockInfo.score)
            // Check if we retrieved the next block of best chain
            if(block.parentId.equals(bestBlockId)) {
              (
                storage.update(block, chainScore),
                ProgressInfo(None, Seq(), Seq(block), Seq())
              )
            } else {
              // Check if retrieved block is the best one, but from another chain
              if(isBestBlock(block, parentBlockInfo.score)) {
                  bestForkChanges(block) match { // get info to switch to another chain
                    case Success(progInfo) =>
                      (
                        storage.update(block, chainScore),
                        progInfo
                      )
                    case Failure(e) =>
                      //log.error("New best block found, but it can not be applied: %s".format(e.getMessage))
                      (
                        storage.update(block, chainScore),
                        // TO DO: we should somehow prevent growing of such chain (penalize the peer?)
                        ProgressInfo[SidechainBlock](None, Seq(), Seq(), Seq())
                      )

                  }
              } else {
                // We retrieved block from another chain that is not the best one
                (
                  storage.update(block, chainScore),
                  ProgressInfo[SidechainBlock](None, Seq(), Seq(), Seq())
                )
              }
            }
          case None =>
            // Parent is not present inside history
            // TO DO: Request for common chain till current unknown block. Check Scorex RC4 for possible solution
            // TO DO: do we need to save it to history storage to prevent double downloading or it's cached somewhere?
            (
              storage,
              ProgressInfo[SidechainBlock](None, Seq(), Seq(), Seq())
            )
        }
      }
    }
    new SidechainHistory(newStorage.get, params) -> progressInfo
  }

  def isGenesisBlock(blockId: ModifierId): Boolean = {
    blockId.equals(params.sidechainGenesisBlockId)
  }

  def isBestBlock(block: SidechainBlock, parentScore: Long): Boolean = {
    val currentScore = storage.chainScoreFor(bestBlockId).get
    val newScore = calculateChainScore(block, parentScore)
    newScore > currentScore
  }

  // score is a long value, where
  // first 4 bytes contain number of MCBlock references included into blockchain up to passed block (including)
  // last 4 bytes contain heights of passed block
  def calculateChainScore(block: SidechainBlock, parentScore: Long): Long = {
    parentScore + (block.mainchainBlocks.size.toLong << 32 + 1)
  }

  def bestForkChanges(block: SidechainBlock): Try[ProgressInfo[SidechainBlock]] = Try {
    val (newChainSuffix, currentChainSuffix) = commonBlockSuffixes(modifierById(block.parentId).get)
    if(newChainSuffix.isEmpty && currentChainSuffix.isEmpty)
      throw new IllegalArgumentException("Cannot retrieve fork changes. Fork length is more than params.maxHistoryRewritingLength")

    val newChainSuffixValidity: Boolean = !newChainSuffix.tail.map(isSemanticallyValid)
      .contains(ModifierSemanticValidity.Invalid)

    if(newChainSuffixValidity) {
      val rollbackPoint = newChainSuffix.headOption
      val toRemove = currentChainSuffix.tail.map(id => storage.blockById(id).get)
      val toApply = newChainSuffix.tail.map(id => storage.blockById(id).get) ++ Seq(block)

      require(toRemove.nonEmpty)
      require(toApply.nonEmpty)

      ProgressInfo[SidechainBlock](rollbackPoint, toRemove, toApply, Seq())
    } else {
      //log.info(s"Orphaned block $block from invalid suffix")
      ProgressInfo[SidechainBlock](None, Seq(), Seq(), Seq())
    }
  }

  // Find common suffixes for two chains - starting from forkBlock and from bestBlock.
  // Returns last common block and then variant blocks for two chains.
  def commonBlockSuffixes(forkBlock: SidechainBlock): (Seq[ModifierId], Seq[ModifierId]) = {
    def inCurrentChain(blockId: ModifierId): Boolean = storage.isInActiveChain(blockId)

    chainBack(forkBlock.id, inCurrentChain, Int.MaxValue) match {
      case Some(newBestChain) =>
        val commonBlockHeight = storage.blockInfoById(newBestChain.head).get.height
        if(height - commonBlockHeight > params.maxHistoryRewritingLength)
          // fork length is more than params.maxHistoryRewritingLength
          (Seq[ModifierId](), Seq[ModifierId]())
        else
          (newBestChain, storage.activeChainFrom(newBestChain.head))

      case None => (Seq[ModifierId](), Seq[ModifierId]())
    }
  } ensuring { res =>
    // verify, that both sequences starts from common block
    (res._1.isEmpty && res._2.isEmpty) || res._1.head == res._2.head
  }

  // Go back though chain and get block ids until condition 'until' or reaching the limit
  // None if parent block is not in chain
  // Note: work faster for active chain back (looks inside memory) and slower for fork chain (looks inside disk)
  private def chainBack(blockId: ModifierId,
                        until: ModifierId => Boolean,
                        limit: Int): Option[Seq[ModifierId]] = { // to do
    var acc: Seq[ModifierId] = Seq(blockId)
    var id = blockId

    while(acc.size < limit && !until(id)) {
      storage.parentBlockId(id) match {
        case Some(parentId) =>
          acc = parentId +: acc
          id = parentId
        case _ =>
          //log.warn(s"Parent block for ${encoder.encode(block.id)} not found ")
          return None
      }
    }
    Some(acc)
  }

  override def reportModifierIsValid(block: SidechainBlock): SidechainHistory = {
    Try {
      var newStorage = storage.updateSemanticValidity(block, ModifierSemanticValidity.Valid).get
      newStorage = newStorage.updateBestBlock(block, storage.blockInfoById(block.id).get).get
      new SidechainHistory(newStorage, params)
    } match {
      case Success(newHistory) => newHistory
      case Failure(e) =>
        //log.error(s"Failed to update validity for block ${encoder.encode(block.id)} with error ${e.getMessage}.")
        new SidechainHistory(storage, params)
    }
  }

  override def reportModifierIsInvalid(modifier: SidechainBlock, progressInfo: History.ProgressInfo[SidechainBlock]): (SidechainHistory, History.ProgressInfo[SidechainBlock]) = { // to do
    val newHistory: SidechainHistory = Try {
      val newStorage = storage.updateSemanticValidity(modifier, ModifierSemanticValidity.Invalid).get
      new SidechainHistory(newStorage, params)
    } match {
      case Success(history) => history
      case Failure(e) =>
        //log.error(s"Failed to update validity for block ${encoder.encode(block.id)} with error ${e.getMessage}.")
        new SidechainHistory(storage, params)
    }

    // In case when we try to apply some fork, which contains at least one invalid block, we should return to the State and History to the "state" before fork.
    // Calculate new ProgressInfo:
    // Set branch point as previous one
    // Remove blocks, that were applied before current invalid one
    // Apply blocks, that were part of ActiveChain
    // skip blocks to Download, that are part of wrong chain we tried to apply.
    val newProgressInfo = ProgressInfo(progressInfo.branchPoint, progressInfo.toApply.takeWhile(block => !block.id.equals(modifier.id)), progressInfo.toRemove, Seq())
    newHistory -> newProgressInfo
  }

  override def isEmpty: Boolean = height <= 0

  override def applicableTry(block: SidechainBlock): Try[Unit] = {
    if (!contains(block.parentId))
      Failure(new RecoverableModifierError("Parent block is not in history yet"))
    else
      Success()
  }

  override def modifierById(blockId: ModifierId): Option[SidechainBlock] = storage.blockById(blockId)

  override def isSemanticallyValid(blockId: ModifierId): ModifierSemanticValidity = {
    storage.semanticValidity(blockId)
  }

  override def openSurfaceIds(): Seq[ModifierId] = {
    if(isEmpty)
      Seq()
    else
      Seq(bestBlockId)
  }

  override def continuationIds(info: SidechainSyncInfo, size: Int): Option[ModifierIds] = {
    info.knownBlockIds.find(id => storage.isInActiveChain(id)) match {
      case Some(commonBlockId) =>
        Some(storage.activeChainFrom(commonBlockId).take(size).map(id => (SidechainBlock.ModifierTypeId, id)))
      case None =>
        //log.warn("Found chain without common block ids from remote")
        None
    }
  }

  // see https://en.bitcoin.it/wiki/Protocol_documentation#getblocks
  private def knownBlocksHeightToSync(): Seq[Int] = {
    if (isEmpty)
      return Seq()

    var indexes: Seq[Int] = Seq()

    var step: Int = 1
    // Start at the top of the chain and work backwards.
    var index: Int = height
    while (index > 1) {
      indexes = indexes :+ index
      // Push top 10 indexes first, then back off exponentially.
      if(indexes.size >= 10)
        step *= 2
      index -= step
    }
    // Push the genesis block index.
    indexes :+ 1
  }

  override def syncInfo: SidechainSyncInfo = {
    // collect control points of block ids like in bitcoin (last 10, then increase step exponentially until genesis block)
    SidechainSyncInfo(
      // return a sequence of block ids for given blocks height backward starting from blockId
      knownBlocksHeightToSync().map(height => storage.activeChainBlockId(height).get)
    )

  }

  // get divergent suffix until we reach the end of otherBlockIds or known block in otherBlockIds.
  // return last common block + divergent suffix
  // Note: otherBlockIds ordered from most recent to oldest block
  private def divergentSuffix(otherBlockIds: Seq[ModifierId]): Seq[ModifierId] = {
    var suffix = Seq[ModifierId]()
    var blockId: ModifierId = null
    var restOfOtherBlockIds = otherBlockIds

    while(restOfOtherBlockIds.nonEmpty) {
      blockId = restOfOtherBlockIds.head
      restOfOtherBlockIds = restOfOtherBlockIds.tail
      suffix = blockId +: suffix

      if(storage.isInActiveChain(blockId))
        return suffix
    }
    Seq() // we didn't find common block (even genesis one is different) -> we have totally different chains.
  }

  /** From Scorex History:
    * Whether another's node syncinfo shows that another node is ahead or behind ours
    *
    * @param other other's node sync info
    * @return Equal if nodes have the same history, Younger if another node is behind, Older if a new node is ahead
    */
  override def compare(other: SidechainSyncInfo): History.HistoryComparisonResult = {
    val dSuffix = divergentSuffix(other.knownBlockIds)

    dSuffix.size match {
      case 0 =>
        // log.warn(Nonsence situation..._)
        Nonsense
      case 1 =>
        if(dSuffix.head.equals(bestBlockId))
          Equal
        else
          Younger
      case _ =>
        val otherBestBlockHeight = storage.heightOf(dSuffix.reverse.find(id => storage.heightOf(id).isDefined).get).get
        if (storage.height < otherBestBlockHeight)
          Older
        else if (storage.height == otherBestBlockHeight)
          Equal // TO DO: should be a fork? Look for RC4
        else
          Younger
    }
  }
}