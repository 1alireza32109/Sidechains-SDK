package com.horizen.forge

import com.horizen.block.{MainchainBlockReference, SidechainBlock}
import com.horizen.box.NoncedBox
import com.horizen.companion.SidechainTransactionsCompanion
import com.horizen.consensus._
import com.horizen.params.NetworkParams
import com.horizen.proposition.Proposition
import com.horizen.transaction.SidechainTransaction
import com.horizen.vrf.VRFProof
import com.horizen.{ForgerDataWithSecrets, _}
import scorex.core.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import scorex.util.{ModifierId, ScorexLogging}

import scala.util.{Failure, Success}


class ForgeMessageBuilder(mainchainSynchronizer: MainchainSynchronizer,
                          companion: SidechainTransactionsCompanion,
                          val params: NetworkParams) extends ScorexLogging with TimeToEpochSlotConverter {
  type ForgeMessageType = GetDataFromCurrentView[SidechainHistory, SidechainState, SidechainWallet, SidechainMemoryPool, ForgeResult]

  def buildForgeMessageForEpochAndSlot(consensusEpochNumber: ConsensusEpochNumber, consensusSlotNumber: ConsensusSlotNumber): ForgeMessageType = {
      val forgingFunctionForEpochAndSlot: View => ForgeResult = tryToForgeNextBlock(consensusEpochNumber, consensusSlotNumber)

      val forgeMessage: ForgeMessageType =
        GetDataFromCurrentView[SidechainHistory, SidechainState, SidechainWallet, SidechainMemoryPool, ForgeResult](forgingFunctionForEpochAndSlot)

      forgeMessage
  }

  protected def tryToForgeNextBlock(nextConsensusEpochNumber: ConsensusEpochNumber, nextConsensusSlotNumber: ConsensusSlotNumber)(view: View): ForgeResult = {
    log.info(s"Try to forge block for epoch ${nextConsensusEpochNumber} with slot ${nextConsensusSlotNumber}")
    val bestBlockId = view.history.bestBlockId
    val bestBlockInfo = view.history.bestBlockInfo
    val bestBlockEpochAndSlot = timestampToEpochAndSlot(bestBlockInfo.timestamp)

    val nextBockTimestamp = getTimeStampForEpochAndSlot(nextConsensusEpochNumber, nextConsensusSlotNumber)
    val nextBlockEpochAndSlot: ConsensusEpochAndSlot = ConsensusEpochAndSlot(nextConsensusEpochNumber, nextConsensusSlotNumber)
    if(bestBlockEpochAndSlot >= nextBlockEpochAndSlot) {
      ForgeFailed(new IllegalArgumentException (s"Try to forge block with epochAndSlot ${nextBlockEpochAndSlot} but current best block epochAndSlot are: ${bestBlockEpochAndSlot}"))
    }

    if ((nextConsensusEpochNumber - timeStampToEpochNumber(bestBlockInfo.timestamp)) > 1) log.warn("Forging is not possible: whole consensus epoch(s) are missed")

    val consensusInfo: FullConsensusEpochInfo = view.history.getFullConsensusEpochInfoForNextBlock(bestBlockId, nextConsensusEpochNumber)
    val totalStake = consensusInfo.stakeConsensusEpochInfo.totalStake
    val vrfMessage = buildVrfMessage(nextConsensusSlotNumber, consensusInfo.nonceConsensusEpochInfo)

    val availableForgersDataWithSecret: Seq[ForgerDataWithSecrets] = view.vault.getForgingDataWithSecrets(nextConsensusEpochNumber).getOrElse(Seq())

    val forgingDataOpt: Option[(ForgerDataWithSecrets, VRFProof)] = availableForgersDataWithSecret
      .toStream
      .map(forgerDataWithSecrets => (forgerDataWithSecrets, forgerDataWithSecrets.vrfSecret.prove(vrfMessage))) //get secrets thus filter forger boxes not owned by node
      .find{case (forgerDataWithSecrets, vrfProof) => vrfProofCheckAgainstStake(forgerDataWithSecrets.forgerBox.value(), vrfProof, totalStake)} //check our forger boxes against stake

    val forgingResult = forgingDataOpt
                                      .map{case (forgerDataWithSecrets, vrfProof) => forgeBlock(view, bestBlockId, nextBockTimestamp, forgerDataWithSecrets, vrfProof)}
                                      .getOrElse(SkipSlot)

    log.info(s"Forge result is: ${forgingResult}")
    forgingResult
  }

  protected def forgeBlock(view: View, parentBlockId: ModifierId, timestamp: Long, forgerDataWithSecrets: ForgerDataWithSecrets, vrfProof: VRFProof): ForgeResult = {
    var withdrawalEpochMcBlocksLeft = params.withdrawalEpochLength - view.history.bestBlockInfo.withdrawalEpochInfo.lastEpochIndex
    if(withdrawalEpochMcBlocksLeft == 0) // current best block is the last block of the epoch
      withdrawalEpochMcBlocksLeft = params.withdrawalEpochLength

    val mainchainBlockRefToInclude: Seq[MainchainBlockReference] = mainchainSynchronizer.getNewMainchainBlockReferences(
      view.history,
      Math.min(SidechainBlock.MAX_MC_BLOCKS_NUMBER, withdrawalEpochMcBlocksLeft) // to not to include mcblock references from different withdrawal epochs
    )

    val txsToInclude: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]] =
      if(mainchainBlockRefToInclude.size == withdrawalEpochMcBlocksLeft) { // SC block is going to become the last block of the withdrawal epoch
        Seq() // no SC Txs allowed
      } else { // SC block is in the middle of the epoch
        view.pool.take(SidechainBlock.MAX_SIDECHAIN_TXS_NUMBER) // TO DO: problems with types
          .map(t => t.asInstanceOf[SidechainTransaction[Proposition, NoncedBox[Proposition]]])
          .toSeq
      }

    val blockCreationResult = SidechainBlock.create(
      parentBlockId,
      timestamp,
      mainchainBlockRefToInclude.map(_.data),
      txsToInclude,
      mainchainBlockRefToInclude.map(_.header),
      Seq(),
      forgerDataWithSecrets.forgerBoxRewardPrivateKey,
      forgerDataWithSecrets.forgerBox,
      vrfProof,
      forgerDataWithSecrets.merklePath,
      companion,
      params)

    blockCreationResult match {
      case Success(block) => ForgeSuccess(block)
      case Failure(exception) => ForgeFailed(exception)
    }
  }
}



